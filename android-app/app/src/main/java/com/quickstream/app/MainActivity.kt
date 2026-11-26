package com.quickstream.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.quickstream.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch


// Servidor que se rellena por defecto la primera vez (o si el campo se guardó vacío).
private const val DEFAULT_SERVER_URL = "https://camstream-9ynp.onrender.com"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    // Configuración "pendiente": la que se usará para ARRANCAR la emisión. Una vez en
    // marcha, el estado real vive en BroadcastEngine.stateFlow y estos campos dejan de
    // consultarse (ver currentSource/currentMic).
    private var pendingSource: VideoSource = VideoSource.BACK_CAMERA
    private var pendingMicEnabled = true
    private var pendingStart = false

    /** Para qué se está pidiendo permiso de captura de pantalla ahora mismo. */
    private enum class ScreenPermissionPurpose { START, SWITCH_SOURCE }
    private var awaitingScreenPermissionFor: ScreenPermissionPurpose? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            proceedAfterPermissions()
        } else {
            Toast.makeText(this, "Hacen falta los permisos para poder emitir", Toast.LENGTH_LONG).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val purpose = awaitingScreenPermissionFor
        awaitingScreenPermissionFor = null

        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(this, "No se concedió permiso para capturar la pantalla", Toast.LENGTH_LONG).show()
            updateSourceButtonStates()
            updateAudioButtonStates()
            return@registerForActivityResult
        }

        when (purpose) {
            ScreenPermissionPurpose.START -> {
                pendingSource = VideoSource.SCREEN
                launchBroadcastService(result.data)
            }
            ScreenPermissionPurpose.SWITCH_SOURCE -> {
                pendingSource = VideoSource.SCREEN
                switchSourceLive(VideoSource.SCREEN, result.data)
            }
            null -> { /* nada que hacer */ }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("quickstream_prefs", Context.MODE_PRIVATE)
        restorePrefs()

        binding.btnRegenRoom.setOnClickListener {
            binding.inputRoomId.setText(randomRoomId())
        }
        binding.btnNewLink.setOnClickListener {
            if (BroadcastEngine.stateFlow.value.isRunning || pendingStart) return@setOnClickListener
            binding.inputRoomId.setText(randomRoomId())
            savePrefs()
            showPreviewLink()
            Toast.makeText(this, "Enlace nuevo creado", Toast.LENGTH_SHORT).show()
        }

        binding.btnSourceFront.setOnClickListener { onSourceButtonClicked(VideoSource.FRONT_CAMERA) }
        binding.btnSourceBack.setOnClickListener { onSourceButtonClicked(VideoSource.BACK_CAMERA) }
        binding.btnSourceScreen.setOnClickListener { onSourceButtonClicked(VideoSource.SCREEN) }

        binding.btnToggleMic.setOnClickListener { onMicButtonClicked() }

        binding.btnStartStop.setOnClickListener {
            if (BroadcastEngine.stateFlow.value.isRunning || pendingStart) {
                stopBroadcast()
            } else {
                startBroadcastFlow()
            }
        }

        binding.btnCopyUrl.setOnClickListener {
            val url = currentLink()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("URL", url))
            Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareUrl.setOnClickListener {
            val url = currentLink()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(intent, "Compartir enlace"))
        }

        binding.btnConversation.setOnClickListener { showConversationDialog() }
        binding.btnEndConversation.setOnClickListener { BroadcastEngine.stopConversation() }

        binding.btnChatHide.setOnClickListener { setChatHidden(true) }
        binding.btnChatShow.setOnClickListener { setChatHidden(false) }
        // Deja siempre sitio debajo del contenido para el chat flotante (o su
        // burbuja), así nada queda tapado: se puede bajar hasta el final igual.
        binding.cardChat.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollPadding() }

        binding.btnSendChat.setOnClickListener { sendChatFromInput() }
        binding.inputChatMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendChatFromInput()
                true
            } else {
                false
            }
        }

        updateSourceButtonStates()
        updateAudioButtonStates()
        if (!BroadcastEngine.stateFlow.value.isRunning) showPreviewLink()
        observeState()
        observeChat()
        observeConversation()
    }

    // ---- Modo conversación (ver y oír a otra persona aquí, sin acople) ----

    private var rendererReady = false
    private var attachedTrack: org.webrtc.VideoTrack? = null

    /** Cuadro propio (dialog_conversation.xml) con la paleta y la letra de la app. */
    private fun showConversationDialog() {
        val dialogBinding = com.quickstream.app.databinding.DialogConversationBinding.inflate(layoutInflater)
        // Si ya tienes copiado un enlace de QuickStream que no es el tuyo, se rellena solo.
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (clip != null && clip.contains("/watch/") && clip != currentLink()) {
            dialogBinding.inputConversationLink.setText(clip)
            dialogBinding.inputConversationLink.setSelection(clip.length)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        // Sin el marco blanco del diálogo estándar: solo se ve nuestra tarjeta.
        // El margen deja ver el fondo a los lados, como las tarjetas de la pantalla.
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.InsetDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), dp(20)
            )
        )
        dialogBinding.btnConversationCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConversationConnect.setOnClickListener {
            val error = BroadcastEngine.startConversation(dialogBinding.inputConversationLink.text?.toString().orEmpty())
            if (error != null) {
                dialogBinding.inputConversationLink.error = error
            } else {
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun observeConversation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BroadcastEngine.conversationState.collect { conv -> renderConversation(conv) }
            }
        }
    }

    private fun renderConversation(conv: ConversationState) {
        val live = BroadcastEngine.stateFlow.value.isLive
        binding.btnConversation.visibility =
            if (live && !conv.active) android.view.View.VISIBLE else android.view.View.GONE
        binding.cardConversation.visibility =
            if (conv.active) android.view.View.VISIBLE else android.view.View.GONE
        binding.txtConversationStatus.text = conv.status

        if (!conv.active) {
            detachRemoteVideo()
            if (rendererReady) {
                binding.remoteRenderer.release()
                rendererReady = false
            }
            volumeControlStream = android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
            return
        }

        // Las teclas de volumen regulan cómo se oye a la otra persona.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        if (!rendererReady) {
            val egl = BroadcastEngine.eglContext() ?: return
            binding.remoteRenderer.init(egl, null)
            binding.remoteRenderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            binding.remoteRenderer.setEnableHardwareScaler(true)
            rendererReady = true
        }
        if (conv.remoteVideo !== attachedTrack) {
            detachRemoteVideo()
            conv.remoteVideo?.let {
                try { it.addSink(binding.remoteRenderer); attachedTrack = it } catch (e: Exception) { /* pista ya liberada */ }
            }
        }
        val showImage = attachedTrack != null && conv.remoteHasVideo
        binding.txtRemoteAudioOnly.visibility = if (showImage) android.view.View.GONE else android.view.View.VISIBLE
        binding.txtRemoteAudioOnly.text = when {
            !conv.connected -> "⏳\nEsperando a la otra persona"
            !conv.remoteHasAudio && !conv.remoteHasVideo -> "🎙️\nEn pausa"
            else -> "🎙️\nSolo sonido"
        }
    }

    private fun detachRemoteVideo() {
        val track = attachedTrack ?: return
        attachedTrack = null
        // La pista puede estar ya liberada si la conexión se cerró: no pasa nada.
        try { track.removeSink(binding.remoteRenderer) } catch (e: Exception) { /* ignorar */ }
        if (rendererReady) binding.remoteRenderer.clearImage()
    }

    override fun onStop() {
        super.onStop()
        // Sin pantalla no se pinta nada; al volver, renderConversation la engancha otra vez.
        detachRemoteVideo()
    }

    // ---- Chat compartido (anfitrión + todos los espectadores ven lo mismo) ----

    private fun sendChatFromInput() {
        val text = binding.inputChatMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        BroadcastEngine.sendChat(text)
        binding.inputChatMessage.setText("")
    }

    private var renderedChatCount = 0

    // ---- Chat flotante: visible encima de la pantalla o plegado en una burbuja ----

    private var chatLive = false
    private var chatHidden = false
    private var unreadCount = 0

    private fun setChatHidden(hidden: Boolean) {
        chatHidden = hidden
        if (!hidden) {
            unreadCount = 0
            scrollChatToBottom()
        } else {
            hideKeyboard()
        }
        updateChatVisibility()
    }

    private fun updateChatVisibility() {
        val gone = android.view.View.GONE
        val visible = android.view.View.VISIBLE
        binding.cardChat.visibility = if (chatLive && !chatHidden) visible else gone
        binding.chatFab.visibility = if (chatLive && chatHidden) visible else gone
        binding.txtChatUnread.visibility = if (chatLive && chatHidden && unreadCount > 0) visible else gone
        binding.txtChatUnread.text = if (unreadCount > 99) "99+" else unreadCount.toString()
        updateScrollPadding()
    }

    private fun updateScrollPadding() {
        val bottom = when {
            binding.cardChat.visibility == android.view.View.VISIBLE -> binding.cardChat.height + dp(24)
            binding.chatFab.visibility == android.view.View.VISIBLE -> dp(96)
            else -> 0
        }
        if (binding.scrollMain.paddingBottom != bottom) {
            binding.scrollMain.post { binding.scrollMain.setPadding(0, 0, 0, bottom) }
        }
    }

    private fun scrollChatToBottom() {
        // scrollTo y no fullScroll(): fullScroll mueve el foco al ScrollView y Android le
        // pinta un resaltado gris de foco encima de los mensajes.
        binding.chatScroll.post {
            val bottom = binding.chatScroll.getChildAt(0)?.height ?: 0
            binding.chatScroll.smoothScrollTo(0, bottom)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.inputChatMessage.windowToken, 0)
        binding.inputChatMessage.clearFocus()
    }

    private fun observeChat() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BroadcastEngine.chatMessages.collect { messages ->
                    binding.txtChatEmpty.visibility =
                        if (messages.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

                    if (messages.size < renderedChatCount) {
                        // Se ha recortado el historial (límite de mensajes): repintar entero
                        // en vez de intentar calcular qué faltaba, es lo más simple y fiable.
                        binding.chatMessagesContainer.removeAllViews()
                        renderedChatCount = 0
                    }
                    // Solo añadimos los mensajes NUEVOS desde la última vez — evita reconstruir
                    // toda la lista de vistas en cada mensaje si el chat ya lleva un rato activo.
                    var newFromOthers = 0
                    for (i in renderedChatCount until messages.size) {
                        binding.chatMessagesContainer.addView(buildChatBubble(messages[i]))
                        if (!messages[i].fromHost) newFromOthers++
                    }
                    val added = messages.size > renderedChatCount
                    renderedChatCount = messages.size
                    if (added) scrollChatToBottom()
                    if (chatHidden && newFromOthers > 0) {
                        unreadCount += newFromOthers
                        updateChatVisibility()
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun buildChatBubble(msg: ChatMessage): TextView {
        val bubble = TextView(this)
        val name = if (msg.fromHost) "Anfitrión (tú)" else msg.name
        bubble.text = "$name\n${msg.text}"
        bubble.setLineSpacing(0f, 1.0f)
        bubble.textSize = 13f
        bubble.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8))
        bubble.setBackgroundResource(if (msg.fromHost) R.drawable.bg_chat_bubble_host else R.drawable.bg_chat_bubble)

        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(6)
        params.gravity = if (msg.fromHost) Gravity.END else Gravity.START
        bubble.layoutParams = params

        // Separamos visualmente el nombre del texto con negrita, aplicado tras fijar el
        // texto para no complicar el String de arriba.
        val spannable = android.text.SpannableString(bubble.text)
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0, name.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (msg.fromHost) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.state_on)),
                0, name.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        bubble.text = spannable

        return bubble
    }

    private fun restorePrefs() {
        binding.inputServerUrl.setText(
            prefs.getString("serverUrl", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
        )
        binding.inputRoomId.setText(prefs.getString("room", randomRoomId()))
        binding.inputPassword.setText(prefs.getString("password", ""))
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("serverUrl", binding.inputServerUrl.text?.toString()?.trim())
            .putString("room", binding.inputRoomId.text?.toString()?.trim())
            .putString("password", binding.inputPassword.text?.toString())
            .apply()
    }

    /** Enlace que verán los espectadores: el de la emisión en marcha o, si no
     *  hay ninguna, el que tendrá la próxima (servidor + sala guardados). */
    private fun currentLink(): String =
        BroadcastEngine.stateFlow.value.viewerUrl ?: BroadcastEngine.buildViewerUrl(
            binding.inputServerUrl.text?.toString()?.trim().orEmpty(),
            binding.inputRoomId.text?.toString()?.trim().orEmpty()
        )

    /** Muestra, antes de emitir, el enlace y el QR de la próxima transmisión. */
    private fun showPreviewLink() {
        val url = currentLink()
        binding.cardShare.visibility = android.view.View.VISIBLE
        binding.btnNewLink.visibility = android.view.View.VISIBLE
        binding.txtViewerUrl.text = url
        binding.txtViewerCount.text = ""
        loadQrIfNeeded(url)
    }

    private fun randomRoomId(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // ---- Fuente de vídeo (cámara frontal/trasera/pantalla) ----

    /**
     * El vídeo es opcional, igual que el micrófono: tocar la fuente que YA está
     * activa la apaga (deja la emisión solo con audio) en vez de volver a pedir
     * permiso o quedarse sin hacer nada — así los tres botones de fuente pueden
     * estar los tres apagados a la vez, cosa que antes no era posible (siempre
     * había una cámara/pantalla obligatoriamente encendida).
     */
    private fun onSourceButtonClicked(source: VideoSource) {
        val running = BroadcastEngine.stateFlow.value.isRunning
        val turningOff = currentSource() == source

        if (turningOff) {
            pendingSource = VideoSource.NONE
            if (running) {
                switchSourceLive(VideoSource.NONE)
            } else {
                updateSourceButtonStates()
                updateAudioButtonStates()
            }
            return
        }

        if (source == VideoSource.SCREEN) {
            requestScreenPermission(
                if (running) ScreenPermissionPurpose.SWITCH_SOURCE else ScreenPermissionPurpose.START
            )
            return
        }
        pendingSource = source
        if (running) {
            switchSourceLive(source)
        } else {
            updateSourceButtonStates()
            updateAudioButtonStates()
        }
    }

    /** Cambia de fuente con la emisión YA en marcha, sin cortarla. */
    private fun switchSourceLive(source: VideoSource, data: Intent? = null) {
        val intent = Intent(this, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_SWITCH_SOURCE
            putExtra(BroadcastService.EXTRA_SOURCE, source.name)
            putExtra(BroadcastService.EXTRA_PROJECTION_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestScreenPermission(purpose: ScreenPermissionPurpose) {
        awaitingScreenPermissionFor = purpose
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    // ---- Audio: micrófono ----

    private fun onMicButtonClicked() {
        if (BroadcastEngine.stateFlow.value.isRunning) {
            BroadcastEngine.setMicEnabled(!BroadcastEngine.stateFlow.value.micEnabled)
        } else {
            pendingMicEnabled = !pendingMicEnabled
            updateAudioButtonStates()
        }
    }

    // ---- Arranque de la emisión ----

    private fun startBroadcastFlow() {
        val serverUrl = binding.inputServerUrl.text?.toString()?.trim().orEmpty()
        val room = binding.inputRoomId.text?.toString()?.trim().orEmpty()

        if (serverUrl.isEmpty()) {
            binding.inputServerUrl.error = "Indica la URL de tu servidor"
            return
        }
        if (room.isEmpty()) {
            binding.inputRoomId.error = "Indica un nombre de sala"
            return
        }

        savePrefs()

        // Se piden cámara y micrófono siempre, sea cual sea la fuente inicial elegida:
        // así, cambiar a cámara EN CALIENTE más adelante (p. ej. mientras se comparte
        // pantalla) nunca choca con un permiso que falte a mitad de emisión.
        val needed = mutableListOf<String>()
        needed.add(Manifest.permission.RECORD_AUDIO)
        needed.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            proceedAfterPermissions()
        }
    }

    /**
     * La emisión sigue en segundo plano hasta que se detiene a mano, pero algunos
     * móviles (OnePlus, OPPO, Xiaomi…) cierran igualmente las apps "que gastan
     * batería" al rato. Se pide una vez al sistema que no la optimice.
     */
    private fun askToIgnoreBatteryOptimizationsOnce() {
        if (prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) { /* sin ese ajuste, seguimos igual */ }
    }

    private fun proceedAfterPermissions() {
        askToIgnoreBatteryOptimizationsOnce()
        if (pendingSource == VideoSource.SCREEN) {
            requestScreenPermission(ScreenPermissionPurpose.START)
        } else {
            launchBroadcastService(null)
        }
    }

    private fun launchBroadcastService(projectionData: Intent?) {
        pendingStart = true
        binding.btnStartStop.text = "Detener"
        tintStartButton(running = true)
        binding.txtStatus.text = "Conectando…"

        val intent = Intent(this, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_START
            putExtra(BroadcastService.EXTRA_SERVER_URL, binding.inputServerUrl.text?.toString()?.trim())
            putExtra(BroadcastService.EXTRA_ROOM, binding.inputRoomId.text?.toString()?.trim())
            putExtra(BroadcastService.EXTRA_PASSWORD, binding.inputPassword.text?.toString().orEmpty())
            putExtra(BroadcastService.EXTRA_SOURCE, pendingSource.name)
            putExtra(BroadcastService.EXTRA_MIC_ENABLED, pendingMicEnabled)
            putExtra(BroadcastService.EXTRA_PROJECTION_DATA, projectionData)
        }
        ContextCompat.startForegroundService(this, intent)

        setInputsEnabled(false)
    }

    private fun stopBroadcast() {
        pendingStart = false
        val intent = Intent(this, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_STOP
        }
        startService(intent)
        setInputsEnabled(true)
        binding.btnStartStop.text = "Iniciar transmisión"
        tintStartButton(running = false)
        showPreviewLink()
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.inputServerUrl.isEnabled = enabled
        binding.inputRoomId.isEnabled = enabled
        binding.btnRegenRoom.isEnabled = enabled
        binding.inputPassword.isEnabled = enabled
        // Los botones de fuente/audio se quedan SIEMPRE activos: antes de arrancar
        // sirven para elegir con qué fuente empezar, y con la emisión ya en marcha
        // sirven para cambiar en caliente sin cortar nada.
    }

    // ---- Pintado de los botones grandes de fuente/audio ----

    private fun currentSource(): VideoSource {
        val state = BroadcastEngine.stateFlow.value
        return if (state.isRunning) state.activeSource else pendingSource
    }

    private fun currentMic(): Boolean {
        val state = BroadcastEngine.stateFlow.value
        return if (state.isRunning) state.micEnabled else pendingMicEnabled
    }

    // Encendido = amarillo, apagado = morado — así se ve de un vistazo qué fuente/
    // audio está activo ahora mismo sin tener que leer texto.
    private fun tintButton(button: MaterialButton, on: Boolean, enabled: Boolean = true) {
        val bgColorRes = if (on) R.color.state_on else R.color.state_off
        val textColorRes = if (on) R.color.state_on_text else R.color.state_off_text
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgColorRes))
        button.setTextColor(ContextCompat.getColor(this, textColorRes))
        // Apagado: ciruela con un borde violeta fino, para que se distinga bien
        // de la tarjeta; encendido: amarillo liso.
        button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
        button.strokeWidth = if (on) 0 else dp(1) + dp(1) / 2
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    /** Iniciar = violeta; emitiendo ("Detener") = amarillo, como lo encendido. */
    private fun tintStartButton(running: Boolean) {
        tintButton(binding.btnStartStop, on = running)
        if (!running) {
            binding.btnStartStop.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
            binding.btnStartStop.setTextColor(ContextCompat.getColor(this, R.color.on_accent))
            binding.btnStartStop.strokeWidth = 0
        }
    }

    private fun updateSourceButtonStates() {
        val src = currentSource()
        tintButton(binding.btnSourceFront, src == VideoSource.FRONT_CAMERA)
        tintButton(binding.btnSourceBack, src == VideoSource.BACK_CAMERA)
        tintButton(binding.btnSourceScreen, src == VideoSource.SCREEN)
    }

    private fun updateAudioButtonStates() {
        tintButton(binding.btnToggleMic, currentMic())
    }

    // ---- Observar el estado de la emisión ----

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BroadcastEngine.stateFlow.collect { state ->
                    pendingStart = false
                    // "isRunning" es true desde que la cámara/micrófono ya están encendidos,
                    // aunque todavía no se haya confirmado la conexión con el servidor
                    // (fase "Conectando…"). Así el botón dice "Detener" durante todo el
                    // tiempo real que algo está grabando, no solo cuando ya hay espectadores.
                    binding.btnStartStop.text = if (state.isRunning) "Detener" else "Iniciar transmisión"
                    tintStartButton(running = state.isRunning)
                    binding.txtStatus.text = state.error ?: state.statusText

                    if (state.isLive && state.viewerUrl != null) {
                        binding.cardShare.visibility = android.view.View.VISIBLE
                        binding.btnNewLink.visibility = android.view.View.GONE
                        binding.txtViewerUrl.text = state.viewerUrl
                        binding.txtViewerCount.text = when (state.viewerCount) {
                            0 -> "Nadie está viendo la transmisión todavía"
                            1 -> "1 persona viendo"
                            else -> "${state.viewerCount} personas viendo"
                        }
                        loadQrIfNeeded(state.viewerUrl)
                        // El chat solo tiene sentido con la emisión ya en directo de verdad
                        // (señalización confirmada) — antes de eso no hay a quién mandarle nada.
                        if (!chatLive) {
                            chatLive = true
                            // El chat empieza plegado en su burbuja 💬: así no tapa los
                            // botones (Detener incluido). Avisa de mensajes nuevos con el
                            // contador y se abre con un toque.
                            chatHidden = true
                            updateChatVisibility()
                            // Al entrar en directo lo primero que hace falta es el enlace/QR
                            // para compartirlo: lo traemos a la vista, por encima del chat.
                            binding.scrollMain.postDelayed({
                                binding.scrollMain.smoothScrollTo(0, binding.cardShare.top - dp(12))
                            }, 700)
                        }
                    } else if (!state.isRunning) {
                        if (!pendingStart) showPreviewLink()
                        chatLive = false
                        chatHidden = false
                        unreadCount = 0
                        updateChatVisibility()
                        binding.chatMessagesContainer.removeAllViews()
                        renderedChatCount = 0
                        setInputsEnabled(true)
                    }

                    updateSourceButtonStates()
                    updateAudioButtonStates()
                    renderConversation(BroadcastEngine.conversationState.value)
                }
            }
        }
    }

    private var lastQrUrl: String? = null
    private fun loadQrIfNeeded(url: String) {
        if (url == lastQrUrl) return
        lastQrUrl = url
        lifecycleScope.launch {
            val bitmap = QrUtils.fetchQrBitmap(url)
            if (bitmap != null) {
                binding.imgQr.setImageBitmap(bitmap)
                binding.imgQr.visibility = android.view.View.VISIBLE
            } else {
                binding.imgQr.visibility = android.view.View.GONE
            }
        }
    }

    override fun onDestroy() {
        detachRemoteVideo()
        if (rendererReady) {
            binding.remoteRenderer.release()
            rendererReady = false
        }
        super.onDestroy()
        // La emisión sigue viva en el Service aunque se cierre la Activity;
        // solo se detiene si el usuario pulsa "Detener".
    }
}
