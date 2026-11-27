package com.quickstream.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.net.URI
import java.net.URLEncoder

private const val TAG = "ConversationClient"

/**
 * Modo conversación: recibe DENTRO de la app la emisión de otro móvil, como lo haría
 * la página del espectador (watch.html), para verla y oírla aquí mismo.
 *
 * El motivo de hacerlo en la app y no en el navegador es el acople: si la voz del
 * otro suena por el navegador, la app no sabe que está sonando y no puede quitarla
 * de lo que capta su micrófono, así que se la devuelve y se forma el pitido. Aquí la
 * reproduce el mismo motor de audio que graba el micrófono, y su cancelador de eco
 * la resta (ver WebRtcClient.setEchoCancellation).
 *
 * Usa el mismo protocolo que watch.html: entra en la sala del otro como "viewer",
 * recibe su oferta, responde y recibe vídeo y audio.
 */
class ConversationClient(
    private val rtc: WebRtcClient,
    private val serverUrl: String,
    private val room: String,
    private val password: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String, connected: Boolean)
        fun onRemoteVideo(track: VideoTrack?)
        fun onRemoteState(hasVideo: Boolean, hasAudio: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ws: WebSocketClient? = null
    private var pc: PeerConnection? = null
    private var stopped = false
    private var reconnectDelayMs = 1000L
    // Candidatos que llegan antes de tener la oferta aplicada: se guardan y se
    // añaden en cuanto la conexión está lista para ellos.
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    // Voz de la otra persona, para poder silenciarla sin cortar la conversación.
    private var remoteAudio: org.webrtc.AudioTrack? = null
    private var muted = false

    /** Silencia (o vuelve a oír) a la otra persona; ella sigue oyéndote a ti. */
    fun setMuted(value: Boolean) {
        muted = value
        try { remoteAudio?.setEnabled(!value) } catch (e: Exception) { /* pista ya liberada */ }
    }

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (stopped) return
            try { ws?.takeIf { it.isOpen }?.send("{\"type\":\"ping\"}") } catch (e: Exception) { /* ignorar */ }
            mainHandler.postDelayed(this, 25000)
        }
    }

    // Si la conexión con el otro móvil falla, antes solo se avisaba de que se
    // reintentaba, pero nadie lo hacía y se quedaba así para siempre. Ahora se
    // vuelve a entrar en su sala: al volver a entrar, su móvil manda una oferta
    // nueva y la conexión se negocia otra vez desde cero.
    private val restartRunnable = Runnable { restartSession() }

    private fun restartSession() {
        if (stopped) return
        closePeer()
        val old = ws
        ws = null // así su onClose no programa otra reconexión
        try { old?.close() } catch (e: Exception) { /* ignorar */ }
        scheduleReconnect()
    }

    fun start() {
        connect()
        mainHandler.postDelayed(pingRunnable, 25000)
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        try { ws?.close() } catch (e: Exception) { /* ignorar */ }
        ws = null
        closePeer()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun connect() {
        if (stopped) return
        listener.onStatus("Conectando con la sala $room…", false)
        val wsBase = serverUrl.removeSuffix("/")
            .replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        val uri = URI("$wsBase/ws?room=${enc(room)}&role=viewer&key=${enc(password)}")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                mainHandler.post { reconnectDelayMs = 1000L }
            }
            override fun onMessage(message: String?) {
                if (message != null) mainHandler.post { if (ws === this) handleMessage(message) }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                mainHandler.post { if (ws === this) scheduleReconnect() }
            }
            override fun onError(ex: Exception?) {
                Log.w(TAG, "Error de señalización: ${ex?.message}")
            }
        }
        client.connectionLostTimeout = 20
        ws = client
        try { client.connect() } catch (e: Exception) { scheduleReconnect() }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        ws = null
        val delay = reconnectDelayMs
        reconnectDelayMs = minOf(reconnectDelayMs * 2, 8000L)
        mainHandler.postDelayed({ connect() }, delay)
    }

    private fun send(json: JSONObject) {
        try { ws?.takeIf { it.isOpen }?.send(json.toString()) } catch (e: Exception) { /* ignorar */ }
    }

    private fun handleMessage(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "viewer-registered" -> {
                if (!msg.optBoolean("hostOnline", false)) {
                    listener.onStatus("Esperando a que la otra persona empiece a emitir…", false)
                }
            }
            "offer" -> handleOffer(msg.optString("sdp"))
            "candidate" -> {
                val c = msg.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(
                    if (c.isNull("sdpMid")) null else c.optString("sdpMid"),
                    c.optInt("sdpMLineIndex", 0),
                    c.optString("candidate")
                )
                val peer = pc
                if (peer != null && remoteDescriptionSet) peer.addIceCandidate(candidate)
                else pendingCandidates.add(candidate)
            }
            "state" -> listener.onRemoteState(msg.optBoolean("hasVideo", true), msg.optBoolean("hasAudio", true))
            "host-offline" -> {
                closePeer()
                listener.onStatus("La otra persona ha dejado de emitir. Seguimos esperando por si vuelve…", false)
            }
            "error" -> {
                val text = msg.optString("message", "Error del servidor")
                val shown = if (text == "Contraseña incorrecta.") {
                    "Esa sala tiene contraseña. Pega el enlace, un espacio y la contraseña."
                } else text
                listener.onStatus(shown, false)
                // No tiene sentido reintentar en bucle con la misma contraseña.
                stopped = true
                try { ws?.close() } catch (e: Exception) { /* ignorar */ }
            }
        }
    }

    private fun closePeer() {
        mainHandler.removeCallbacks(restartRunnable)
        // Primero se avisa (la pantalla suelta la imagen) y luego se libera la conexión.
        listener.onRemoteVideo(null)
        remoteAudio = null
        pc?.dispose()
        pc = null
        remoteDescriptionSet = false
        pendingCandidates.clear()
    }

    // Cuenta las conexiones: los avisos de una conexión ya sustituida se ignoran.
    private var peerGeneration = 0

    private fun handleOffer(sdp: String) {
        closePeer()
        val myGeneration = ++peerGeneration
        val peer = rtc.createReceiverPeerConnection(object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                mainHandler.post {
                    if (pc == null) return@post
                    send(
                        JSONObject().put("type", "candidate").put(
                            "candidate",
                            JSONObject()
                                .put("candidate", candidate.sdp)
                                .put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        )
                    )
                }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() ?: return
                if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    mainHandler.post { listener.onRemoteVideo(track as VideoTrack) }
                }
                // El audio no necesita nada más: el motor de audio de la app lo
                // reproduce solo, y por eso su cancelador de eco sabe que suena.
                // Solo se guarda para poder silenciarlo.
                if (track.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) {
                    mainHandler.post {
                        if (pc == null) return@post
                        remoteAudio = track as org.webrtc.AudioTrack
                        setMuted(muted)
                    }
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                mainHandler.post {
                    if (stopped || myGeneration != peerGeneration || pc == null) return@post
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            mainHandler.removeCallbacks(restartRunnable)
                            listener.onStatus("En conversación con la sala $room", true)
                        }
                        // Un corte breve suele arreglarse solo; si en unos segundos no
                        // vuelve, se empieza de cero.
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            listener.onStatus("Se ha perdido la conexión, reintentando…", false)
                            mainHandler.removeCallbacks(restartRunnable)
                            mainHandler.postDelayed(restartRunnable, 6000)
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            listener.onStatus("Se ha perdido la conexión, reintentando…", false)
                            mainHandler.removeCallbacks(restartRunnable)
                            restartSession()
                        }
                        else -> {}
                    }
                }
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
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }) ?: return
        pc = peer
        listener.onStatus("Conectando con la otra persona…", false)

        peer.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                mainHandler.post {
                    if (pc !== peer) return@post
                    remoteDescriptionSet = true
                    pendingCandidates.forEach { peer.addIceCandidate(it) }
                    pendingCandidates.clear()
                    createAnswer(peer)
                }
            }
            override fun onSetFailure(error: String?) {
                Log.w(TAG, "No se pudo aplicar la oferta: $error")
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun createAnswer(peer: PeerConnection) {
        peer.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                val tuned = SessionDescription(desc.type, rtc.tuneOpusForReceiving(desc.description))
                peer.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        mainHandler.post {
                            if (pc === peer) send(JSONObject().put("type", "answer").put("sdp", tuned.description))
                        }
                    }
                }, tuned)
            }
            override fun onCreateFailure(error: String?) {
                Log.w(TAG, "No se pudo crear la respuesta: $error")
            }
        }, MediaConstraints())
    }

    companion object {
        /**
         * Saca servidor, sala y contraseña de lo que pegue el usuario: el enlace de
         * QuickStream (https://servidor/watch/SALA) y, opcionalmente, un espacio y la
         * contraseña. Devuelve null si no hay ningún enlace de QuickStream.
         */
        fun parseLink(text: String): Triple<String, String, String>? {
            // Sin distinguir mayúsculas: el teclado del móvil suele poner «Https://».
            val m = Regex("(https?://[^\\s/]+)/watch/([^\\s?#/]+)", RegexOption.IGNORE_CASE).find(text) ?: return null
            val raw = m.groupValues[1]
            val sep = raw.indexOf("://")
            val server = raw.substring(0, sep).lowercase() + raw.substring(sep)
            val room = java.net.URLDecoder.decode(m.groupValues[2], "UTF-8")
            val after = text.substring(m.range.last + 1).trimStart('/')
            val keyParam = Regex("[?&]key=([^&\\s#]+)").find(after)?.groupValues?.get(1)
            val password = keyParam?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: after.substringAfter(' ', "").trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            return Triple(server, room, password)
        }
    }
}
