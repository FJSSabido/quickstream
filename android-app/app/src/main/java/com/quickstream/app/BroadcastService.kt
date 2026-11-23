package com.quickstream.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano: mantiene viva la emisión (cámara/pantalla + micrófono)
 * aunque el usuario apague la pantalla o cambie de app, con una notificación
 * persistente y un botón para detenerla desde ahí mismo.
 *
 * IMPORTANTE: el servicio se mantiene en primer plano (con cámara/micrófono
 * reservados) mientras [BroadcastEngine.stateFlow] diga que hay una emisión
 * "en marcha" (isRunning). Si el motor se detiene solo — por ejemplo, porque
 * falla la conexión con el servidor de señalización — este servicio se entera
 * y se para también, para no dejar la cámara ni el micrófono encendidos de
 * fondo con la notificación mostrando información obsoleta.
 *
 * También es quien media los cambios de fuente EN CALIENTE hacia/desde pantalla
 * (ACTION_SWITCH_SOURCE): en Android 14+ hay que volver a declarar el tipo de
 * "foreground service" (cámara ↔ mediaProjection) ANTES de pedir la captura de
 * pantalla, o el sistema puede matar el servicio. Cambiar entre cámara delantera y
 * trasera no pasa por aquí — no cambia el tipo de servicio, así que la Activity
 * llama directamente a BroadcastEngine.switchSource().
 *
 * La emisión sigue en segundo plano INDEFINIDAMENTE, aunque se apague la pantalla
 * o se cierre la app deslizándola fuera de recientes: solo termina cuando se pulsa
 * "Detener" (en la app o en la notificación). La notificación persistente deja
 * siempre claro que se está emitiendo.
 */
class BroadcastService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wasRunning = false
    private var lastSource: VideoSource? = null
    private var initializedState = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notifiedUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        serviceScope.launch {
            BroadcastEngine.stateFlow.collect { state ->
                if (!initializedState) {
                    // Primera lectura al suscribirnos: BroadcastEngine es un singleton
                    // que vive todo el proceso, así que este primer valor puede venir
                    // de un intento ANTERIOR (por ejemplo, uno que terminó en error) y
                    // no de esta instancia del servicio. Si lo tratáramos igual que un
                    // cambio real, "isRunning=true" heredado de antes podía leerse como
                    // "wasRunning" y, en cuanto BroadcastEngine.start() hace su stop()
                    // inicial para limpiar cualquier emisión previa, este bloque lo
                    // interpretaba como "el motor se ha parado solo" y se autodestruía
                    // el servicio a los pocos milisegundos de arrancar la cámara — antes
                    // incluso de que la sesión de cámara terminara de abrirse. Por eso
                    // aquí solo memorizamos el punto de partida, sin actuar sobre él.
                    initializedState = true
                    lastSource = state.activeSource
                    wasRunning = state.isRunning
                    return@collect
                }
                if (wasRunning && !state.isRunning) {
                    // El motor ya se ha soltado a sí mismo (cámara/micrófono liberados);
                    // quitamos también la notificación y terminamos el servicio.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (state.isRunning && state.activeSource != lastSource) {
                    // Red de seguridad: si la fuente cambió sin pasar por
                    // ACTION_SWITCH_SOURCE (no debería, pero por si acaso), nos
                    // aseguramos igualmente de que el tipo declarado está al día.
                    startForegroundCompat(state.activeSource)
                }
                if (state.isRunning && state.viewerUrl != notifiedUrl) {
                    // Cuando ya hay enlace, la notificación lo muestra (y sigue
                    // ofreciendo "Detener transmisión").
                    notifiedUrl = state.viewerUrl
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification())
                }
                lastSource = state.activeSource
                wasRunning = state.isRunning
            }
        }
    }

    companion object {
        const val ACTION_START = "com.quickstream.app.action.START"
        const val ACTION_STOP = "com.quickstream.app.action.STOP"
        const val ACTION_SWITCH_SOURCE = "com.quickstream.app.action.SWITCH_SOURCE"
        private const val CHANNEL_ID = "quickstream_live"
        private const val NOTIF_ID = 42

        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_ROOM = "room"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_MIC_ENABLED = "micEnabled"
        const val EXTRA_PROJECTION_DATA = "projectionData"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BroadcastEngine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SWITCH_SOURCE) {
            val source = parseSource(intent.getStringExtra(EXTRA_SOURCE))
            // Declaramos el tipo de servicio correcto ANTES de tocar MediaProjection
            // (obligatorio en Android 14+ cuando se entra o se sale de pantalla).
            startForegroundCompat(source)
            val data: Intent? = intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            BroadcastEngine.switchSource(source, data)
            return START_NOT_STICKY
        }

        val source = parseSource(intent?.getStringExtra(EXTRA_SOURCE))
        startForegroundCompat(source)

        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: ""
        val room = intent?.getStringExtra(EXTRA_ROOM) ?: ""
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""
        val micEnabled = intent?.getBooleanExtra(EXTRA_MIC_ENABLED, true) ?: true
        val data: Intent? = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)

        BroadcastEngine.start(
            context = applicationContext,
            serverUrl = serverUrl,
            room = room,
            password = password,
            source = source,
            micEnabled = micEnabled,
            projectionData = data
        )

        return START_NOT_STICKY
    }

    private fun parseSource(name: String?): VideoSource =
        try {
            if (name != null) VideoSource.valueOf(name) else VideoSource.BACK_CAMERA
        } catch (e: Exception) {
            VideoSource.BACK_CAMERA
        }

    /**
     * Con la pantalla apagada, Android (y sobre todo capas como ColorOS) tiende a
     * "dormir" la CPU y el WiFi para ahorrar batería — y eso corta la emisión al
     * rato aunque la app siga en primer plano. Mientras dure este servicio (es
     * decir, solo mientras se emite) se mantienen despiertos los dos; se sueltan
     * en cuanto la emisión termina (ver onDestroy).
     */
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickStream:broadcast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) { /* sin wake lock seguimos igual que antes */ }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "QuickStream:broadcast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) { /* sin wifi lock seguimos igual que antes */ }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { /* ignorar */ }
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { /* ignorar */ }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        serviceScope.cancel()
        // Solo si sigue en marcha: si el motor ya se paró solo con un error (sala
        // ocupada, sin red...), pararlo otra vez borraría ese mensaje de la pantalla.
        if (BroadcastEngine.stateFlow.value.isRunning) BroadcastEngine.stop()
    }

    /**
     * El vídeo es opcional (igual que el micrófono, ver [VideoSource.NONE]), así
     * que el tipo de "foreground service" declarado en Android 14+ tiene que
     * reflejar SOLO el recurso que realmente se está usando: cámara únicamente
     * con cámara delantera/trasera activa, mediaProjection únicamente con
     * pantalla, y NINGUNO de los dos (solo micrófono) cuando no hay vídeo — pedir
     * un tipo que luego no se usa de verdad es justo lo que Android 14+ vigila.
     */
    private fun startForegroundCompat(source: VideoSource) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            val type = when (source) {
                VideoSource.SCREEN ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                VideoSource.FRONT_CAMERA, VideoSource.BACK_CAMERA ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                VideoSource.NONE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "Transmisión en directo", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, BroadcastService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val url = BroadcastEngine.stateFlow.value.viewerUrl
        val text = if (url != null) "Emitiendo en $url" else "Conectando…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuickStream está en directo")
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$text\nSigue emitiendo aunque cierres la app. Pulsa «Detener transmisión» para terminar."
                )
            )
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setColor(0xFF6D3FE0.toInt())
            .setContentIntent(openPending)
            .addAction(0, "Detener transmisión", stopPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
