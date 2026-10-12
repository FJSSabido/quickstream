package com.miconstelacion.camstream

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Encapsula todo el motor WebRTC: cámara/pantalla como fuente de vídeo, micrófono
 * como fuente de audio, y una PeerConnection independiente por cada espectador
 * conectado (para poder emitir a varias personas a la vez desde un único móvil).
 *
 * La fuente de vídeo se puede cambiar EN CALIENTE (cámara delantera ↔ trasera ↔
 * pantalla) sin cortar ni renegociar ninguna conexión ya abierta: basta con darle a
 * cada RtpSender ya existente una pista de vídeo nueva ([RtpSender.setTrack]). Ver
 * [pushVideoTrackToExistingSenders].
 */
class WebRtcClient(
    private val context: Context,
    private val listener: Listener,
    // Servidores TURN adicionales, obtenidos del propio servidor de señalización
    // (ver BroadcastEngine.fetchTurnIceServers) antes de crear este cliente. Con
    // solo STUN (los de abajo) la conexión únicamente sirve si se consigue
    // establecer DIRECTA entre el móvil y quien mira — en redes con NAT
    // restrictivo eso puede no llegar a pasar nunca. Vacío si el servidor no
    // tiene TURN configurado (o si aún no se ha podido consultar): la app
    // sigue funcionando igual que antes, solo sin ese repetidor de más.
    extraIceServers: List<PeerConnection.IceServer> = emptyList()
) {
    interface Listener {
        fun onLocalIceCandidate(viewerId: String, candidate: IceCandidate)
        fun onLocalOffer(viewerId: String, sdp: String)
    }

    companion object {
        // Techo de RESOLUCIÓN de captura. Antes era 4K (2160p): en teoría más nítido,
        // pero en la práctica casi ninguna red de subida (ni wifi de casa ni datos
        // móviles) sostiene de forma fiable los ~20 Mbps que hacen falta para que 4K
        // se vea limpio — y en cuanto la red da menos de eso, son los MISMOS bits
        // repartidos entre muchos más píxeles, que es justo lo que se ve como imagen
        // "pixelada"/a bloques. Se confirmó que pasaba tanto en wifi como en datos
        // móviles y ya desde antes de tocar el TURN, así que no era un problema de
        // una red en concreto sino del techo de resolución en sí. Full HD (1080p) es
        // el máximo habitual de las apps de streaming en directo por el mismo motivo:
        // sigue siendo una imagen nítida, pero alcanzable con el bitrate que de verdad
        // suele haber disponible. pickBestSize() elige lo mejor que dé la cámara sin
        // pasar de este techo. El bitrate que acompaña a cada resolución se calcula
        // aparte (ver [bitrateForResolution] y [minBitrateForResolution]).
        private const val MAX_CAPTURE_WIDTH = 1920
        private const val MAX_CAPTURE_HEIGHT = 1080
        private const val TARGET_FPS = 30
    }

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    // Resolución que se está capturando AHORA MISMO — se usa para calcular el techo
    // de bitrate a juego (ver [bitrateForResolution]), tanto al conectar un
    // espectador nuevo como al cambiar de fuente en caliente.
    private var currentCaptureWidth: Int = MAX_CAPTURE_WIDTH
    private var currentCaptureHeight: Int = MAX_CAPTURE_HEIGHT

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val peerConnections = mutableMapOf<String, PeerConnection>()

    // Sender de vídeo "main" de cada espectador: guardarlo aquí es justo lo que
    // permite cambiar de fuente (cámara delantera/trasera/pantalla) EN CALIENTE, sin
    // cortar ni renegociar la conexión — basta con darle a cada sender ya existente
    // una pista de vídeo nueva (sender.setTrack) y sigue mandando por el mismo canal
    // RTP ya establecido con cada espectador.
    private val mainVideoSenders = mutableMapOf<String, RtpSender>()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    ) + extraIceServers

    fun initialize() {
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        // Por defecto, WebRTC captura el micrófono por la ruta "de llamada"
        // (VOICE_COMMUNICATION): pensada para videollamadas, con banda más estrecha y
        // una nivelación automática más agresiva orientada a que la voz se entienda
        // por teléfono, no a captar el sonido con fidelidad. CamStream no es una
        // llamada — es una emisión en un solo sentido — así que se usa en su lugar la
        // ruta CAMCORDER, la misma que usan las apps de cámara/vídeo: más sensible,
        // con más rango de frecuencias, y sin ese "aplanado" pensado para teléfono.
        // Por el mismo motivo se apaga la cancelación de eco por hardware: solo tiene
        // sentido si el propio móvil reproduce audio que le puede "hacer eco" al
        // micrófono (como en una llamada con altavoz) — aquí no se reproduce nada, así
        // que ese filtro no protege de nada y solo puede restar nitidez/sensibilidad
        // de más a la señal real.
        val adm = JavaAudioDeviceModule.builder(context)
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        // Igual que arriba: se apaga también la cancelación de eco por software (no
        // hace nada útil sin audio que reproducir de vuelta, y de paso deja de
        // "tocar" la señal sin necesidad). Se mantienen el control automático de
        // ganancia (para que ni los susurros queden inaudibles ni los picos
        // saturen — esto es justo lo que sube la SENSIBILIDAD a sonido flojo), la
        // supresión de ruido y el filtro de graves (para seguir sin runrún de fondo,
        // como se pidió antes). Las claves con "2" al final piden la versión más
        // nueva de cada algoritmo cuando el dispositivo la soporta (AGC2/NS2).
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("mic0", audioSource)
    }

    /**
     * Elige la mejor (mayor) resolución que ofrece la cámara indicada, sin pasar de
     * [MAX_CAPTURE_WIDTH]x[MAX_CAPTURE_HEIGHT] — en la práctica, la resolución máxima
     * real de la cámara si no supera ese techo de 4K.
     */
    private fun pickBestSize(
        enumerator: Camera2Enumerator,
        deviceName: String,
        maxWidth: Int = MAX_CAPTURE_WIDTH,
        maxHeight: Int = MAX_CAPTURE_HEIGHT
    ): Pair<Int, Int> {
        val formats = try { enumerator.getSupportedFormats(deviceName) } catch (e: Exception) { null }
        if (formats.isNullOrEmpty()) return maxWidth to maxHeight

        val withinCap = formats.filter { it.width <= maxWidth && it.height <= maxHeight }
        val best = (withinCap.ifEmpty { formats }).maxByOrNull { it.width.toLong() * it.height.toLong() }
        return if (best != null) best.width to best.height else maxWidth to maxHeight
    }

    /**
     * Arranca la cámara indicada. Si ya hay espectadores conectados, la nueva pista
     * se empuja EN CALIENTE a todos ellos (ver [pushVideoTrackToExistingSenders]) —
     * no hace falta parar ni volver a conectar nada.
     */
    fun startCameraCapture(useFrontCamera: Boolean) {
        stopVideoCapture()

        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull {
            if (useFrontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("Este dispositivo no tiene ninguna cámara disponible")

        val capturer = enumerator.createCapturer(deviceName, null)
        videoCapturer = capturer

        val source = factory.createVideoSource(false)
        videoSource = source

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        capturer.initialize(helper, context, source.capturerObserver)
        val (w, h) = pickBestSize(enumerator, deviceName)
        capturer.startCapture(w, h, TARGET_FPS)
        currentCaptureWidth = w
        currentCaptureHeight = h

        localVideoTrack = factory.createVideoTrack("video0", source)
        pushVideoTrackToExistingSenders()
    }

    /**
     * Arranca la captura de pantalla. Igual que [startCameraCapture]: si ya hay
     * espectadores conectados, la nueva pista se empuja en caliente sin cortar nada.
     */
    fun startScreenCapture(data: Intent) {
        stopVideoCapture()

        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                // El usuario ha detenido la captura desde el panel de sistema de Android.
            }
        })
        videoCapturer = capturer

        val source = factory.createVideoSource(true)
        videoSource = source

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        capturer.initialize(helper, context, source.capturerObserver)
        val metrics = context.resources.displayMetrics
        capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 20)
        currentCaptureWidth = metrics.widthPixels
        currentCaptureHeight = metrics.heightPixels

        localVideoTrack = factory.createVideoTrack("video0", source)
        pushVideoTrackToExistingSenders()
    }

    /**
     * Techo de bitrate a juego con la resolución que se esté capturando — subir la
     * resolución sin subir el bitrate solo cambia unos píxeles borrosos por otros
     * (los mismos bits repartidos entre más píxeles), así que van siempre unidos.
     * Son valores de partida razonables para vídeo en directo a [TARGET_FPS] fps;
     * como sigue siendo un TECHO, WebRTC solo los usa si la red aguanta — en redes
     * flojas reduce bitrate y/o resolución automáticamente por su cuenta.
     */
    private fun bitrateForResolution(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        return when {
            pixels >= 3840L * 2160L -> 20_000_000 // 4K / 2160p (por si en el futuro se vuelve a permitir)
            pixels >= 2560L * 1440L -> 12_000_000 // 1440p / QHD
            pixels >= 1920L * 1080L -> 8_000_000  // 1080p / Full HD
            pixels >= 1280L * 720L -> 4_000_000   // 720p
            else -> 2_500_000
        }
    }

    /**
     * Suelo MÍNIMO de bitrate, a juego con [bitrateForResolution] (que solo pone el
     * TECHO). Sin este suelo, WebRTC arranca cada conexión con un bitrate de partida
     * muy conservador (pensado para no saturar redes desconocidas) y lo va subiendo
     * poco a poco según va viendo que la red aguanta — ese "arranque lento" es OTRA
     * causa de pixelado al principio de cada emisión (o durante toda ella, si la
     * sesión es corta y nunca le da tiempo a terminar de subir), aunque la red vaya
     * sobrada. Fijar un suelo razonable (bastante por debajo del techo, para no forzar
     * más bitrate del que una red realmente floja pueda dar) hace que la imagen sea
     * decente desde el primer segundo en vez de solo "después de un rato".
     */
    private fun minBitrateForResolution(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        return when {
            pixels >= 2560L * 1440L -> 4_000_000  // 1440p+
            pixels >= 1920L * 1080L -> 3_000_000  // 1080p / Full HD
            pixels >= 1280L * 720L -> 1_500_000   // 720p
            else -> 800_000
        }
    }

    /**
     * Empuja [localVideoTrack] (la fuente que se acaba de arrancar) a los senders de
     * vídeo ya existentes de cada espectador conectado, SIN renegociar SDP — esto es
     * lo que hace posible cambiar de cámara/pantalla sin cortar la transmisión.
     */
    private fun pushVideoTrackToExistingSenders() {
        mainVideoSenders.values.forEach { sender ->
            try {
                sender.setTrack(localVideoTrack, false)
                applyBitrateLimits(sender, currentCaptureWidth, currentCaptureHeight)
            } catch (e: Exception) {
                // Si un sender concreto falla al recibir la pista nueva, seguimos con
                // el resto en vez de tirar abajo el cambio de fuente entero.
            }
        }
    }

    private fun stopVideoCapture() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) { /* ignorar */ }
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    /**
     * Apaga la cámara/pantalla y deja la emisión SOLO con audio, sin cortar la
     * conexión con los espectadores ya conectados — es tan válido como apagar el
     * micrófono ([setMicEnabled]): a cada sender de vídeo ya existente se le manda
     * una pista "nula" (mismo mecanismo que [pushVideoTrackToExistingSenders] usa
     * para cambiar de cámara en caliente), así que el espectador se queda sin
     * imagen pero sigue oyendo, sin tener que volver a conectar nada.
     */
    fun stopVideo() {
        stopVideoCapture()
        pushVideoTrackToExistingSenders()
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun createPeerConnectionForViewer(viewerId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalIceCandidate(viewerId, candidate)
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }) ?: return

        val sender = localVideoTrack?.let { track ->
            val s = pc.addTrack(track, listOf("main"))
            applyBitrateLimits(s, currentCaptureWidth, currentCaptureHeight)
            s
        }
        if (sender != null) mainVideoSenders[viewerId] = sender
        localAudioTrack?.let { pc.addTrack(it, listOf("main")) }

        peerConnections[viewerId] = pc

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                // Se sube la calidad de Opus AQUÍ, en la propia SDP, antes de fijarla —
                // es el único sitio donde de verdad se controla el bitrate de audio (ver
                // preferHighQualityOpus). Se manda la SDP ya retocada tanto en local como
                // al espectador, para que ambos lados estén de acuerdo en el mismo perfil.
                val tunedDesc = SessionDescription(desc.type, preferHighQualityOpus(desc.description))
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        // Red de seguridad: en cuanto la SDP local queda fijada, WebRTC
                        // ya tiene garantizado el "encoding" del sender creado, así que
                        // volvemos a aplicar los límites de bitrate por si la primera vez
                        // (justo tras addTrack) no tuviera efecto todavía.
                        applyBitrateLimits(sender, currentCaptureWidth, currentCaptureHeight)
                    }
                }, tunedDesc)
                listener.onLocalOffer(viewerId, tunedDesc.description)
            }
        }, MediaConstraints())
    }

    /**
     * Sube la calidad del audio (códec Opus) muy por encima de lo que WebRTC usa
     * por defecto (pensado de fábrica para ahorrar red, no para sonar lo mejor
     * posible) — se prioriza que se oiga nítido y sin cortes, aunque cueste algo
     * más de red que el mínimo:
     *  - maxaveragebitrate=128000: techo de bitrate mucho más alto que el ~32kbps
     *    por defecto (de sobra para que la voz suene nítida, sin ahogarse).
     *  - useinbandfec=1: el propio audio lleva "de propina" una copia reducida del
     *    paquete anterior, así que si la red pierde un paquete suelto se puede
     *    reconstruir sin pedirlo de nuevo — evita los cortes/robotización típicos
     *    de una red que va regular.
     *  - usedtx=0: NUNCA deja de mandar audio real en los silencios. El "ahorro"
     *    de DTX (dejar de emitir y mandar solo "ruido de confort") a veces se nota
     *    como un pequeño corte o un timbre raro al retomar la voz — mejor evitarlo
     *    si lo que se busca es sonar lo más limpio posible.
     *  - stereo=0: la voz no necesita dos canales; todo el bitrate disponible se
     *    dedica a nitidez en vez de a un estéreo que aquí no aporta nada.
     * Busca el "payload type" que le ha tocado a Opus en esta SDP en concreto (no
     * es siempre el mismo número) y le añade o sustituye su línea `a=fmtp`.
     */
    private fun preferHighQualityOpus(sdp: String): String {
        val opusPt = Regex("a=rtpmap:(\\d+) opus/48000").find(sdp)?.groupValues?.get(1) ?: return sdp
        val fmtpParams = "minptime=10;useinbandfec=1;stereo=0;maxaveragebitrate=128000;usedtx=0"
        val fmtpRegex = Regex("a=fmtp:$opusPt .*")
        return if (fmtpRegex.containsMatchIn(sdp)) {
            sdp.replace(fmtpRegex) { "a=fmtp:$opusPt $fmtpParams" }
        } else {
            // Poco habitual que falte la línea fmtp, pero por si acaso se añade justo
            // después del rtpmap de Opus en vez de dejarlo sin retocar.
            sdp.replace(
                "a=rtpmap:$opusPt opus/48000",
                "a=rtpmap:$opusPt opus/48000\r\na=fmtp:$opusPt $fmtpParams"
            )
        }
    }

    /**
     * Sube el techo de bitrate del envío (WebRTC solo lo usará si la red aguanta;
     * si va justa, su propio control de congestión reduce la calidad automáticamente)
     * y le dice qué prefiere recortar si la red no da para todo.
     *
     * Se han probado los dos extremos y ambos tienen un problema con 4K de por
     * medio: MAINTAIN_RESOLUTION (mantener siempre el tamaño de imagen) se veía muy
     * nítido pero a saltos en cuanto la red apretaba un poco, porque en vez de
     * encoger la imagen tiraba fotogramas enteros. MAINTAIN_FRAMERATE (mantener
     * siempre los fotogramas por segundo) iba fluido pero se pixelaba de más,
     * porque encogía la imagen agresivamente para no perder ni un fotograma aunque
     * sobrara red de sobra. BALANCED es el término medio que da WebRTC para esto: va
     * repartiendo el recorte entre resolución y fotogramas según lo que la red vaya
     * necesitando en cada momento (a veces cede algo de nitidez, a veces algo de
     * fluidez, nunca todo a un único lado), en vez de sacrificar siempre lo mismo.
     * No exige más al hardware que las otras dos opciones — es el mismo techo de
     * bitrate y la misma resolución de captura ([bitrateForResolution],
     * [MAX_CAPTURE_WIDTH]/[MAX_CAPTURE_HEIGHT]); solo cambia el CRITERIO que usa
     * WebRTC para repartir esos mismos recursos cuando la red no da para todo.
     */
    private fun applyBitrateLimits(sender: RtpSender?, width: Int, height: Int) {
        sender ?: return
        try {
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings.forEach {
                    it.maxBitrateBps = bitrateForResolution(width, height)
                    it.minBitrateBps = minBitrateForResolution(width, height)
                }
            }
            params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            sender.parameters = params
        } catch (e: Exception) {
            // Si algo falla aquí, WebRTC sigue funcionando con su bitrate por defecto.
        }
    }

    fun handleAnswer(viewerId: String, sdp: String) {
        peerConnections[viewerId]?.setRemoteDescription(
            SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleRemoteCandidate(viewerId: String, c: IceCandidateData) {
        peerConnections[viewerId]?.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.sdp))
    }

    fun closeViewer(viewerId: String) {
        mainVideoSenders.remove(viewerId)
        peerConnections.remove(viewerId)?.close()
    }

    fun release() {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        mainVideoSenders.clear()
        stopVideoCapture()
        audioSource?.dispose()
        audioSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        if (::factory.isInitialized) factory.dispose()
        if (::eglBase.isInitialized) eglBase.release()
    }
}

/** Implementación con los 4 métodos vacíos por defecto, para no repetirlos en cada callback. */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
