// Servidor de señalización WebRTC para QuickStream.
//
// Qué hace:
//  - Sirve una página web "visor" (public/watch.html) en /watch/:room
//  - Ofrece un WebSocket en /ws que hace de intermediario ("señalización")
//    entre el móvil que emite (host) y cada navegador que mira (viewer).
//  - El vídeo/audio NUNCA pasa por este servidor: solo viaja por aquí el
//    "apretón de manos" inicial de WebRTC (SDP + ICE candidates). El stream
//    real va directo entre el móvil y quien lo ve (peer-to-peer).
//
// Un móvil (host) = una sala (room). Varias personas pueden ver la misma
// sala a la vez (cada una es un "viewer" con su propia conexión WebRTC).

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const PUBLIC_DIR = path.join(__dirname, 'public');

// --- Credenciales TURN (Cloudflare Realtime) --------------------------------
// Sin esto, el móvil y quien mira solo tienen servidores STUN (ver watch.html y
// WebRtcClient.kt), que solo sirven para conexión DIRECTA (peer-to-peer) — en
// redes con NAT restrictivo (datos móviles, wifis corporativas...) esa conexión
// directa puede no llegar a establecerse nunca, y entonces no se ve ni se oye
// nada aunque todo lo demás funcione bien. Un servidor TURN sirve de "repetidor"
// para esos casos. Las credenciales son de corta duración y se generan aquí, en
// el servidor — el secreto (CF_TURN_API_TOKEN) NUNCA se manda al móvil ni al
// navegador, solo el resultado ya generado.
const CF_TURN_KEY_ID = process.env.CF_TURN_KEY_ID || '';
const CF_TURN_API_TOKEN = process.env.CF_TURN_API_TOKEN || '';
const CF_TURN_TTL_SECONDS = 86400; // 24h: de sobra para cualquier sesión de emisión

async function fetchTurnIceServers() {
  if (!CF_TURN_KEY_ID || !CF_TURN_API_TOKEN) return []; // no configurado: seguimos solo con STUN
  try {
    const resp = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${CF_TURN_KEY_ID}/credentials/generate-ice-servers`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${CF_TURN_API_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ ttl: CF_TURN_TTL_SECONDS }),
      }
    );
    if (!resp.ok) {
      console.error(`Cloudflare TURN: respuesta ${resp.status} al pedir credenciales`);
      return [];
    }
    const data = await resp.json();
    return Array.isArray(data.iceServers) ? data.iceServers : [];
  } catch (err) {
    console.error('Cloudflare TURN: fallo al pedir credenciales', err);
    return []; // ante cualquier fallo, mejor seguir solo con STUN que romper la conexión
  }
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

function serveFile(res, filePath, statusCode = 200) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('No encontrado');
      return;
    }
    const ext = path.extname(filePath);
    res.writeHead(statusCode, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
    res.end(data);
  });
}

const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  let pathname = url.pathname;

  if (pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    return;
  }

  // Lo consultan tanto watch.html como la app antes de conectar, para saber si
  // hay un servidor TURN disponible además de los STUN fijos que ya llevan
  // incorporados. Si no está configurado (o falla), devuelve la lista vacía en
  // vez de un error — así nunca bloquea la conexión, solo deja de tener TURN.
  if (pathname === '/turn-credentials') {
    fetchTurnIceServers().then((iceServers) => {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ iceServers }));
    });
    return;
  }

  // /watch/ROOMID  ->  siempre sirve watch.html (el room id se lee en el navegador desde la URL)
  if (pathname.startsWith('/watch/')) {
    serveFile(res, path.join(PUBLIC_DIR, 'watch.html'));
    return;
  }

  if (pathname === '/' || pathname === '') {
    serveFile(res, path.join(PUBLIC_DIR, 'index.html'));
    return;
  }

  // Servir archivos estáticos normales (evitando salir de PUBLIC_DIR)
  const safePath = path.normalize(pathname).replace(/^(\.\.[/\\])+/, '');
  const filePath = path.join(PUBLIC_DIR, safePath);
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(400);
    res.end('Bad request');
    return;
  }
  serveFile(res, filePath);
});

const wss = new WebSocketServer({ server: httpServer, path: '/ws' });

// rooms: Map<roomId, { hostWs, hostKey: string|null, hostToken: string|null,
//                      hostGraceTimer, viewers: Map<viewerId, ws> }>
const rooms = new Map();

// --- Latido (heartbeat) -------------------------------------------------------
// Mientras se emite, por este WebSocket apenas pasa nada: el vídeo/audio va
// directo entre el móvil y cada espectador. Un WebSocket "en silencio" durante
// minutos es justo lo que los proxies intermedios (el de Render, el del operador
// móvil, el router...) cortan por inactividad — y eso era lo que hacía que la
// emisión se cayera sola cada cierto tiempo. Cada 25 s se manda un "ping" a cada
// conexión (el navegador y la app contestan solos con un "pong"); así nunca está
// en silencio, y si una conexión no contesta a tiempo se da por muerta y se
// cierra, en vez de quedarse "abierta" de mentira.
const HEARTBEAT_MS = 25000;
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch { /* ignorar */ }
  }
}, HEARTBEAT_MS);
wss.on('close', () => clearInterval(heartbeat));

// Si el WebSocket del móvil que emite se corta (un microcorte de red, cambio de
// wifi a datos...), NO damos la emisión por terminada en el acto: el vídeo sigue
// llegando a los espectadores por su conexión directa, así que se le da este
// margen a la app para reconectarse y retomar la sala donde estaba, sin que
// nadie note nada. Solo si no vuelve en este tiempo se avisa de que terminó.
const HOST_GRACE_MS = 30000;

function send(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

// Avisa a todos los espectadores de una sala de cuántos espectadores hay
// conectados ahora mismo (se muestra en la cabecera de watch.html).
function broadcastViewerCount(room) {
  const msg = { type: 'viewer-count', count: room.viewers.size };
  for (const viewer of room.viewers.values()) send(viewer, msg);
}

function getOrCreateRoom(roomId) {
  let room = rooms.get(roomId);
  if (!room) {
    room = { hostWs: null, hostKey: null, hostToken: null, hostGraceTimer: null, viewers: new Map() };
    rooms.set(roomId, room);
  }
  return room;
}

function cleanupEmptyRoom(roomId) {
  const room = rooms.get(roomId);
  if (room && !room.hostWs && !room.hostGraceTimer && room.viewers.size === 0) {
    rooms.delete(roomId);
  }
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const roomId = (url.searchParams.get('room') || '').trim();
  const role = url.searchParams.get('role') === 'host' ? 'host' : 'viewer';
  const key = url.searchParams.get('key') || '';
  // Solo lo manda la app al RECONECTARSE tras un corte: es el token secreto que
  // el servidor le dio en su "host-ready" anterior, y demuestra que es el mismo
  // móvil que ya estaba emitiendo en esta sala (y no otro intentando colarse).
  const resumeToken = url.searchParams.get('resume') || '';

  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  if (!roomId) {
    send(ws, { type: 'error', message: 'Falta el identificador de sala.' });
    ws.close();
    return;
  }

  const room = getOrCreateRoom(roomId);

  if (role === 'host') {
    const isResume = !!resumeToken && resumeToken === room.hostToken;
    if (isResume) {
      // El mismo móvil vuelve tras un corte: se retoma la sala tal cual estaba
      // (misma contraseña, mismos espectadores, sus conexiones de vídeo intactas).
      clearTimeout(room.hostGraceTimer);
      room.hostGraceTimer = null;
      const old = room.hostWs;
      room.hostWs = ws; // ANTES de cerrar la vieja, para que su 'close' no cuente como "se fue"
      if (old && old !== ws) { try { old.terminate(); } catch { /* ignorar */ } }
      ws.role = 'host';
      ws.roomId = roomId;
      send(ws, {
        type: 'host-ready',
        resumed: true,
        token: room.hostToken,
        viewerCount: room.viewers.size,
        viewers: [...room.viewers.keys()],
      });
      // Sin 'viewer-joined' uno a uno: la app compara la lista 'viewers' con las
      // conexiones que ya tiene, crea las que falten (quien entró durante el corte)
      // y cierra las que sobren (quien se fue durante el corte).
      return attachHandlers();
    }

    if ((room.hostWs && room.hostWs.readyState === room.hostWs.OPEN) || room.hostGraceTimer) {
      send(ws, { type: 'error', message: 'Esta sala ya tiene una emisión activa desde otro dispositivo.' });
      ws.close();
      return;
    }
    room.hostWs = ws;
    room.hostKey = key || null;
    room.hostToken = crypto.randomBytes(16).toString('hex');
    ws.role = 'host';
    ws.roomId = roomId;

    // Si ya había espectadores conectados ANTES de que este anfitrión pusiera
    // contraseña (por ejemplo, si abrieron el enlace justo antes de arrancar la
    // emisión, cuando la sala todavía no tenía ni contraseña ni dueño), hay que
    // volver a comprobarlos ahora: si lo que mandaron como contraseña no
    // coincide con la que se acaba de fijar, se les desconecta — su página lo
    // detecta solo y les vuelve a pedir la contraseña correcta (ver
    // showPasswordPrompt en watch.html). Sin esto, alguien podría quedarse
    // viendo la emisión sin haber acertado nunca la contraseña.
    if (room.hostKey) {
      for (const viewerWs of room.viewers.values()) {
        if (viewerWs.providedKey !== room.hostKey) {
          send(viewerWs, { type: 'error', message: 'Contraseña incorrecta.' });
          viewerWs.close();
        }
      }
    }

    send(ws, {
      type: 'host-ready',
      resumed: false,
      token: room.hostToken,
      viewerCount: room.viewers.size,
      viewers: [...room.viewers.keys()],
    });

    // Avisar al host de los espectadores que ya estaban esperando
    for (const viewerId of room.viewers.keys()) {
      send(ws, { type: 'viewer-joined', viewerId });
    }
  } else {
    // viewer
    if (room.hostKey && room.hostKey !== key) {
      send(ws, { type: 'error', message: 'Contraseña incorrecta.' });
      ws.close();
      return;
    }
    const viewerId = crypto.randomBytes(6).toString('hex');
    room.viewers.set(viewerId, ws);
    ws.role = 'viewer';
    ws.roomId = roomId;
    ws.viewerId = viewerId;
    // Se recuerda qué contraseña mandó, para poder revalidarlo si más tarde
    // llega (o cambia) el anfitrión con una contraseña distinta (ver arriba).
    ws.providedKey = key;

    send(ws, { type: 'viewer-registered', viewerId, hostOnline: !!room.hostWs || !!room.hostGraceTimer });
    broadcastViewerCount(room);

    if (room.hostWs) {
      send(room.hostWs, { type: 'viewer-joined', viewerId });
    }
  }

  attachHandlers();

  function attachHandlers() {
  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    // Latido a nivel de aplicación que mandan el navegador y la app (además de
    // los ping/pong del propio WebSocket): solo sirve para que la conexión
    // nunca parezca inactiva. No se reenvía a nadie.
    if (msg.type === 'ping') { ws.isAlive = true; return; }

    const r = rooms.get(ws.roomId);
    if (!r) return;

    if (ws.role === 'host') {
      if (msg.type === 'bye') {
        // El anfitrión ha pulsado "Detener" a propósito: aquí no hay que esperar
        // a ninguna reconexión, la emisión termina en el acto (ver 'close').
        ws.byeSent = true;
        return;
      }
      if ((msg.type === 'state' || msg.type === 'chat') && !msg.viewerId) {
        // A diferencia de offer/answer/candidate (dirigidos a un viewer concreto
        // con msg.viewerId), estos van a TODOS los espectadores conectados a la
        // vez: 'state' para que cada uno actualice sus iconos de "sin señal", y
        // 'chat' cuando el propio anfitrión escribe algo — es un chat compartido,
        // lo ve todo el mundo, no un mensaje privado a un espectador concreto.
        if (msg.type === 'chat') {
          const text = String(msg.text || '').slice(0, 500).trim();
          if (!text) return;
          msg.text = text;
          msg.name = 'Anfitrión';
          msg.ts = Date.now();
        }
        for (const viewer of r.viewers.values()) send(viewer, msg);
        return;
      }
      // El host manda el resto de mensajes dirigidos a un viewer concreto:
      // {type, viewerId, ...} — incluido 'state' cuando SÍ lleva viewerId (para
      // ponerse al día a un espectador que se acaba de conectar).
      const target = r.viewers.get(msg.viewerId);
      if (target) send(target, msg);
    } else {
      // El viewer manda mensajes al host; el servidor añade su viewerId
      msg.viewerId = ws.viewerId;
      if (msg.type === 'chat') {
        // Chat compartido: el mensaje de un espectador lo ve el anfitrión Y
        // también el resto de espectadores (como el chat de un directo normal),
        // no solo el anfitrión. Se sanea texto/nombre por si acaso.
        // Sin anfitrión conectado no hay emisión de verdad — el chat no debe
        // seguir "vivo" después de que termine (watch.html ya deshabilita el
        // campo en ese momento, esto es el mismo control por si acaso, del lado
        // del servidor).
        if (!r.hostWs) return;
        const text = String(msg.text || '').slice(0, 500).trim();
        if (!text) return;
        msg.text = text;
        msg.name = String(msg.name || '').slice(0, 40).trim() || 'Invitado';
        msg.ts = Date.now();
        send(r.hostWs, msg);
        for (const viewer of r.viewers.values()) send(viewer, msg);
        return;
      }
      if (r.hostWs) send(r.hostWs, msg);
    }
  });

  ws.on('close', () => {
    const r = rooms.get(ws.roomId);
    if (!r) return;
    if (ws.role === 'host' && r.hostWs === ws && ws.byeSent) {
      // Parada intencionada: se avisa a todos ya mismo, sin margen de reconexión.
      r.hostWs = null;
      r.hostKey = null;
      r.hostToken = null;
      for (const viewer of r.viewers.values()) {
        send(viewer, { type: 'host-offline' });
      }
    } else if (ws.role === 'host' && r.hostWs === ws) {
      r.hostWs = null;
      // No se da por terminada todavía (ver HOST_GRACE_MS): se espera a que la
      // app se reconecte. Si no vuelve a tiempo, entonces sí se avisa a todos.
      const roomId = ws.roomId;
      clearTimeout(r.hostGraceTimer);
      r.hostGraceTimer = setTimeout(() => {
        const rr = rooms.get(roomId);
        if (!rr) return;
        rr.hostGraceTimer = null;
        if (rr.hostWs) return; // ya volvió
        rr.hostKey = null;
        rr.hostToken = null;
        for (const viewer of rr.viewers.values()) {
          send(viewer, { type: 'host-offline' });
        }
        cleanupEmptyRoom(roomId);
      }, HOST_GRACE_MS);
    } else if (ws.role === 'viewer') {
      r.viewers.delete(ws.viewerId);
      if (r.hostWs) send(r.hostWs, { type: 'viewer-left', viewerId: ws.viewerId });
      broadcastViewerCount(r);
    }
    cleanupEmptyRoom(ws.roomId);
  });
  }
});

httpServer.listen(PORT, () => {
  console.log(`QuickStream escuchando en el puerto ${PORT}`);
});
