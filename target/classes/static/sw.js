// Zavelo service worker.
// It lets the installed app open straight away from its saved copy, even while the server is asleep
// or the phone is offline. Chats, files and calls are never stored here; they always go to the server.
const CACHE = "zavelo-shell-v3";
const SHELL = ["/", "/index.html", "/style.css", "/app.js", "/manifest.json", "/icon.svg",
               "/icons/icon-192.png", "/icons/icon-512.png", "/icons/badge-96.png"];
const WAIT_MS = 2500;   // how long to wait for the server before showing the saved copy

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => Promise.all(SHELL.map((url) => fetch(url, { cache: "reload" }).then(async (res) => {
        if (await isGood(new Request(url), res)) await c.put(url, res);
      }).catch(() => {}))))
      .then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()));
});

/* Only keep a reply if it really is one of our files. While Render is waking the server up it answers with
   its own "starting" page; saving that would make the app open on Render's page every time. */
async function isGood(req, res) {
  if (!res.ok || res.type !== "basic") return false;
  const type = res.headers.get("content-type") || "";
  const isPage = req.mode === "navigate" || req.destination === "document" || req.url.endsWith("/") || req.url.endsWith("/index.html");
  if (!isPage) return !type.includes("text/html");
  if (!type.includes("text/html")) return false;
  return (await res.clone().text()).includes('id="zv-icon"');
}

async function handle(req) {
  const cache = await caches.open(CACHE);
  const saved = await cache.match(req, { ignoreSearch: true });

  const network = fetch(req).then(async (res) => {
    const good = await isGood(req, res);
    if (good) await cache.put(req, res.clone());
    return { res, good };
  });

  // Never opened before: nothing saved yet, so use whatever the server says.
  if (!saved) {
    try { return (await network).res; }
    catch { return (await cache.match("/index.html")) || Response.error(); }
  }

  // Opened before: use the server's copy if it answers quickly, otherwise open the saved copy right away.
  try {
    const first = await Promise.race([network, new Promise((resolve) => setTimeout(() => resolve(null), WAIT_MS))]);
    if (first && first.good) return first.res;
  } catch { /* offline: fall through to the saved copy */ }
  network.catch(() => {});   // let the request finish in the background so the saved copy stays fresh
  return saved;
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== "GET" || url.origin !== location.origin) return;
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/ws/")) return;
  e.respondWith(handle(req));
});

/* ---------- Notifications ---------- */

async function anyWindowVisible() {
  const list = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  return list.some((c) => c.visibilityState === "visible");
}

async function showPush(d) {
  const type = d.type || "message";
  const from = d.test ? "" : (d.from || "");
  const visible = await anyWindowVisible();

  if (type === "call-end") {
    // The caller gave up: stop showing "incoming call", and leave a "missed call" note.
    (await self.registration.getNotifications({ tag: "call-" + from })).forEach((n) => n.close());
    if (visible) return;
  } else if (visible && !d.test) {
    return;              // Zavelo is on screen, so the app itself already shows it
  }

  const call = type === "call";
  const tag = d.test ? "test" : call ? "call-" + from : type === "call-end" ? "missed-" + from : "msg-" + from;
  await self.registration.showNotification(d.title || "Zavelo", {
    body: d.body || "",
    tag,
    renotify: true,
    icon: "/icons/icon-192.png",
    badge: "/icons/badge-96.png",
    data: { from, type },
    requireInteraction: call,                                   // a call stays until you answer or dismiss it
    vibrate: call ? [400, 200, 400, 200, 400, 200, 400] : [150],
  });
}

self.addEventListener("push", (e) => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch { d = { body: e.data ? e.data.text() : "" }; }
  e.waitUntil(showPush(d));
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const from = (e.notification.data || {}).from || "";
  e.waitUntil((async () => {
    const list = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const c of list) {
      if (new URL(c.url).origin !== self.location.origin) continue;
      try { await c.focus(); } catch { /* already in front */ }
      if (from) c.postMessage({ type: "open-chat", key: from });
      return;
    }
    await self.clients.openWindow(from ? "/?chat=" + encodeURIComponent(from) : "/");
  })());
});

function keyToBytes(k) {
  const raw = atob((k + "=".repeat((4 - (k.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(raw, (ch) => ch.charCodeAt(0));
}

/* The browser sometimes replaces a phone's notification address. Sign it up again quietly. */
self.addEventListener("pushsubscriptionchange", (e) => {
  e.waitUntil((async () => {
    try {
      const { key } = await (await fetch("/api/push/key", { credentials: "same-origin" })).json();
      const sub = await self.registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyToBytes(key) });
      await fetch("/api/push/subscribe", {
        method: "POST", credentials: "same-origin",
        headers: { "Content-Type": "application/json" }, body: JSON.stringify(sub.toJSON()),
      });
    } catch { /* the page signs up again the next time Zavelo is opened */ }
  })());
});
