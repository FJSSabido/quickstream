package com.quickstream.app

import android.os.Handler
import android.os.Looper
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.net.URI
import java.net.URLEncoder

/** Candidato ICE recibido desde el otro lado (formato neutro, sin depender de WebRTC). */
data class IceCandidateData(val sdpMid: String?, val sdpMLineIndex: Int, val sdp: String)

interface SignalingListener {
    /**
     * El servidor acepta a este móvil como anfitrión de la sala.
     * [token]: secreto para poder RETOMAR la sala si la conexión se corta.
     * [resumed]: true si es una reconexión que ha retomado la sala tal cual estaba
     * (los espectadores conservan su vídeo); [viewers] es entonces la lista de
     * espectadores que hay AHORA, para cuadrarla con las conexiones existentes.
     */
    fun onHostReady(viewerCount: Int, token: String?, resumed: Boolean, viewers: List<String>)
    fun onViewerJoined(viewerId: String)
    fun onViewerLeft(viewerId: String)
    fun onAnswer(viewerId: String, sdp: String)
    fun onRemoteCandidate(viewerId: String, candidate: IceCandidateData)
    /** [fatal]: error del propio servidor (sala ocupada, etc.) — no tiene sentido
     *  reintentar. Si es false, es un fallo de red y el cierre llega a continuación. */
    fun onSignalingError(message: String, fatal: Boolean)
    fun onSignalingClosed()
    /** Mensaje de chat de un espectador (nunca del propio anfitrión: sus mensajes
     *  los añade la app localmente en cuanto los envía, no hace falta esperar a
     *  que vuelvan del servidor). */
    fun onChat(viewerId: String, name: String, text: String, ts: Long)
}

/**
 * Cliente WebSocket que habla con el servidor de señalización (server/server.js).
 * Solo transporta mensajes de "apretón de manos" de WebRTC (offer/answer/ICE);
 * el vídeo/audio en sí nunca pasa por aquí.
 */
class SignalingClient(
    serverUrl: String,
    room: String,
    password: String,
    resumeToken: String?,
    private val listener: SignalingListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client: WebSocketClient

    init {
        val wsBase = serverUrl.trim()
            .removeSuffix("/")
            .replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        val resumeParam = if (resumeToken.isNullOrEmpty()) "" else "&resume=${enc(resumeToken)}"
        val uri = URI("$wsBase/ws?room=${enc(room)}&role=host&key=${enc(password)}$resumeParam")

        client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                // Nada que hacer: el servidor manda "host-ready" en cuanto acepta la conexión.
            }

            override fun onMessage(message: String?) {
                if (message != null) handleMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                mainHandler.post { listener.onSignalingClosed() }
            }

            override fun onError(ex: Exception?) {
                mainHandler.post { listener.onSignalingError(ex?.message ?: "Error de conexión con el servidor", false) }
            }
        }
        // Latido: cada 20 s la librería manda un "ping" al servidor y, si no recibe
        // respuesta, da la conexión por muerta y la cierra (lo que dispara la
        // reconexión automática en BroadcastEngine). Así el WebSocket nunca pasa
        // minutos en silencio — que es lo que hacía que los proxies lo cortaran — y
        // un corte real se detecta en segundos en vez de quedarse colgado.
        client.connectionLostTimeout = 20
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun connect() {
        try {
            client.connect()
        } catch (e: Exception) {
            listener.onSignalingError(e.message ?: "No se pudo conectar", false)
        }
    }

    /**
     * Cierra la conexión. Con [bye] = true avisa antes al servidor de que es una
     * parada INTENCIONADA (el usuario pulsó "Detener"), para que dé la emisión por
     * terminada en el acto en vez de esperar a una posible reconexión.
     */
    fun disconnect(bye: Boolean = false) {
        if (bye) send(JSONObject().put("type", "bye"))
        try { client.close() } catch (e: Exception) { /* ignorar */ }
    }

    private fun handleMessage(raw: String) {
        val json = try { JSONObject(raw) } catch (e: Exception) { return }
        val type = json.optString("type")
        mainHandler.post {
            when (type) {
                "host-ready" -> {
                    val arr = json.optJSONArray("viewers")
                    val viewers = if (arr == null) emptyList() else List(arr.length()) { arr.optString(it) }
                    listener.onHostReady(
                        json.optInt("viewerCount", 0),
                        json.optString("token").ifEmpty { null },
                        json.optBoolean("resumed", false),
                        viewers
                    )
                }
                "viewer-joined" -> listener.onViewerJoined(json.optString("viewerId"))
                "viewer-left" -> listener.onViewerLeft(json.optString("viewerId"))
                "answer" -> listener.onAnswer(json.optString("viewerId"), json.optString("sdp"))
                "candidate" -> {
                    val c = json.optJSONObject("candidate") ?: return@post
                    listener.onRemoteCandidate(
                        json.optString("viewerId"),
                        IceCandidateData(
                            sdpMid = if (c.isNull("sdpMid")) null else c.optString("sdpMid"),
                            sdpMLineIndex = c.optInt("sdpMLineIndex", 0),
                            sdp = c.optString("candidate")
                        )
                    )
                }
                "error" -> listener.onSignalingError(json.optString("message", "Error del servidor"), true)
                "chat" -> listener.onChat(
                    json.optString("viewerId"),
                    json.optString("name", "Invitado"),
                    json.optString("text", ""),
                    json.optLong("ts", System.currentTimeMillis())
                )
            }
        }
    }

    fun sendOffer(viewerId: String, sdp: String) {
        send(JSONObject().put("type", "offer").put("viewerId", viewerId).put("sdp", sdp))
    }

    fun sendCandidate(viewerId: String, candidate: IceCandidate) {
        val c = JSONObject()
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .put("candidate", candidate.sdp)
        send(JSONObject().put("type", "candidate").put("viewerId", viewerId).put("candidate", c))
    }

    /**
     * Avisa de si hay vídeo y/o audio saliendo AHORA MISMO, para que cada espectador
     * pueda mostrar un icono de "sin señal" en vez de quedarse con la última imagen
     * congelada sin explicación. Sin [viewerId] se manda a TODOS los espectadores
     * conectados (para avisar de un cambio en caliente); con [viewerId], solo a ese
     * espectador en concreto (para ponerlo al día justo al conectarse).
     */
    fun sendState(hasVideo: Boolean, hasAudio: Boolean, source: String, viewerId: String? = null) {
        // "source" (FRONT_CAMERA, BACK_CAMERA, SCREEN, NONE): qué está emitiendo
        // ahora mismo, por si el visor lo necesita.
        val json = JSONObject().put("type", "state").put("hasVideo", hasVideo).put("hasAudio", hasAudio)
            .put("source", source)
        if (viewerId != null) json.put("viewerId", viewerId)
        send(json)
    }

    /**
     * Manda un mensaje de chat del ANFITRIÓN a todos los espectadores conectados
     * (chat compartido: lo ve todo el mundo, igual que en watch.html). El propio
     * mensaje NO vuelve aquí — [BroadcastEngine.sendChat] lo añade en local en
     * cuanto se llama a esto, sin esperar respuesta del servidor.
     */
    fun sendChat(text: String) {
        send(JSONObject().put("type", "chat").put("text", text))
    }

    private fun send(json: JSONObject) {
        if (client.isOpen) client.send(json.toString())
    }
}
