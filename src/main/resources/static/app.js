const $ = (s) => document.querySelector(s);
const state = { me: null, chat: null, chats: [], ws: null, retries: 0, lastDay: null, seen: new Set() };
const MAX_FILE = 10 * 1024 ** 3;   // 10 GB, the same limit the server enforces

/* ---------- Helpers ---------- */

const formatKey = (k) => k.replace(/(\d{3})(?=\d)/g, "$1 ");
const timeOf = (iso) => new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
const mmss = (sec) => `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, "0")}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function formatSize(bytes) {
  const units = ["B", "KB", "MB", "GB"];
  let i = 0, n = bytes;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n >= 10 || i === 0 ? Math.round(n) : n.toFixed(1)} ${units[i]}`;
}

function dayLabel(date) {
  if (date.toDateString() === new Date().toDateString()) return "Today";
  if (date.toDateString() === new Date(Date.now() - 864e5).toDateString()) return "Yesterday";
  return date.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" });
}

function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children);
  return node;
}

function icon(id) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "ic");
  svg.setAttribute("aria-hidden", "true");
  svg.innerHTML = `<use href="#${id}"/>`;
  return svg;
}

const setIcon = (btn, id) => btn.querySelector("use").setAttribute("href", "#" + id);

function paintAvatar(node, name, key) {
  node.textContent = (name.trim()[0] || "?").toUpperCase();
  const hue = 170 + (parseInt(key.slice(-3), 10) % 70);      // blues and teals
  node.style.background = `hsl(${hue} 55% 38%)`;
}

function flash(text) {
  const t = $("#toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(flash.timer);
  flash.timer = setTimeout(() => (t.hidden = true), 3500);
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    method: options.method || (options.body ? "POST" : "GET"),
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.message || "Something went wrong. Try again.");
  return data;
}

function mediaError(e) {
  if (!navigator.mediaDevices) return "Calls and recording need HTTPS (or localhost).";
  if (e && e.name === "NotAllowedError") return "Allow microphone and camera access in your browser, then try again.";
  if (e && e.name === "NotFoundError") return "No microphone or camera found.";
  return "Could not start the microphone or camera.";
}

function fileKind(mime) {
  if (/^image\//.test(mime)) return "image";
  if (/^video\//.test(mime)) return "video";
  if (/^audio\//.test(mime)) return "audio";
  return "doc";
}

/* ---------- Sign in / create account ---------- */

function selectTab(name) {
  const login = name === "login";
  $("#login-form").hidden = !login;
  $("#register-form").hidden = login;
  $("#tab-login").setAttribute("aria-selected", login);
  $("#tab-register").setAttribute("aria-selected", !login);
  $("#auth-error").textContent = "";
}

$("#tab-login").onclick = () => selectTab("login");
$("#tab-register").onclick = () => selectTab("register");

$("#login-form").onsubmit = async (e) => {
  e.preventDefault();
  try {
    enter(await api("/api/auth/login", { body: { username: $("#l-user").value, password: $("#l-pass").value } }));
  } catch (err) { $("#auth-error").textContent = err.message; }
};

$("#register-form").onsubmit = async (e) => {
  e.preventDefault();
  try {
    enter(await api("/api/auth/register", {
      body: { displayName: $("#r-name").value, username: $("#r-user").value, password: $("#r-pass").value },
    }));
  } catch (err) { $("#auth-error").textContent = err.message; }
};

function enter(profile) {
  state.me = profile;
  paintAvatar($("#me-avatar"), profile.displayName, profile.key);
  $("#me-name").textContent = profile.displayName;
  $("#my-key").textContent = formatKey(profile.key);
  $("#auth").hidden = true;
  $("#app").hidden = false;
  connect();
  loadChats();
}

/* ---------- Sidebar ---------- */

async function copyKey() {
  await navigator.clipboard.writeText(state.me.key);
  flash("Key copied");
}
$("#copy-key").onclick = copyKey;
$("#menu-copy").onclick = () => { $("#menu").hidden = true; copyKey(); };
$("#menu-btn").onclick = (e) => { e.stopPropagation(); $("#menu").hidden = !$("#menu").hidden; };
document.addEventListener("click", (e) => {
  if (!e.target.closest("#menu")) $("#menu").hidden = true;
  if (!e.target.closest("#attach-menu") && !e.target.closest("#attach-btn")) $("#attach-menu").hidden = true;
});

async function signOut() {
  $("#menu").hidden = true;
  endCall(null, true);
  dismissIncoming();
  closeChat();
  const ws = state.ws;
  state.me = null; state.ws = null;
  if (ws) ws.close();
  clearLock();
  unlock();
  $("#settings").hidden = true;
  await api("/api/auth/logout", { body: {} }).catch(() => {});
  selectTab("login");
  $("#app").hidden = true;
  $("#auth").hidden = false;
}
$("#menu-logout").onclick = signOut;

$("#find-key").oninput = (e) => {
  const v = e.target.value;
  // Looks like a key (only digits and spaces): group the digits. Otherwise it's a username: leave it alone.
  if (/^[\d\s]+$/.test(v)) e.target.value = formatKey(v.replace(/\D/g, "").slice(0, 9));
};

$("#find-form").onsubmit = async (e) => {
  e.preventDefault();
  const out = $("#find-result");
  try {
    const user = await api(`/api/users/search?q=${encodeURIComponent($("#find-key").value.trim())}`);
    const btn = el("button", { type: "button", textContent: "Message" });
    btn.onclick = () => { out.textContent = ""; $("#find-key").value = ""; openChat(user.key, user.displayName); };
    out.replaceChildren("Found ", el("strong", { textContent: user.displayName }), ` (@${user.username})`, btn);
  } catch (err) { out.textContent = err.message; }
};

async function loadChats() {
  try { state.chats = await api("/api/chats"); renderChats(); } catch { /* refreshes on the next message */ }
}

function renderChats() {
  $("#chat-list").replaceChildren(...state.chats.map((c) => {
    const av = el("div", { className: "avatar" });
    paintAvatar(av, c.displayName, c.key);
    const btn = el("button", { className: "chat-item" + (state.chat && state.chat.key === c.key ? " active" : "") },
      av,
      el("div", { className: "body" },
        el("div", { className: "r1" },
          el("span", { className: "name ellipsis", textContent: c.displayName }),
          el("span", { className: "when", textContent: timeOf(c.sentAt) })),
        el("div", { className: "preview ellipsis", textContent: c.lastMessage })));
    btn.onclick = () => openChat(c.key, c.displayName);
    return el("li", {}, btn);
  }));
  $("#chat-empty").hidden = state.chats.length > 0;
}

/* ---------- Conversation ---------- */

async function openChat(key, displayName) {
  cancelRecording();
  state.chat = { key, displayName };
  state.lastDay = null;
  state.seen = new Set();
  paintAvatar($("#chat-avatar"), displayName, key);
  $("#chat-title").textContent = displayName;
  $("#chat-key").textContent = formatKey(key);
  $("#emoji-panel").hidden = true;
  $("#attach-menu").hidden = true;
  $("#messages").replaceChildren();
  $("#empty").hidden = true;
  $("#chat").hidden = false;
  $("#app").classList.add("in-chat");
  renderChats();
  try {
    (await api(`/api/messages/${key}`)).forEach(appendMessage);
  } catch (err) { flash(err.message); }
  $("#send-input").focus();
}

function closeChat() {
  cancelRecording();
  stopAllAudio();
  state.chat = null;
  $("#chat").hidden = true;
  $("#empty").hidden = false;
  $("#app").classList.remove("in-chat");
  renderChats();
}
$("#back").onclick = closeChat;

function showIfOpen(m) {
  const other = m.from === state.me.key ? m.to : m.from;
  if (state.chat && state.chat.key === other) appendMessage(m);
  loadChats();
}

function appendMessage(m) {
  if (state.seen.has(m.id)) return;           // the same message can arrive twice (upload reply + live push)
  state.seen.add(m.id);

  const list = $("#messages");
  const date = new Date(m.sentAt);
  if (date.toDateString() !== state.lastDay) {
    state.lastDay = date.toDateString();
    list.append(el("li", { className: "day", textContent: dayLabel(date) }));
  }
  const mine = m.from === state.me.key;
  const media = m.type === "file" && ["image", "video"].includes(fileKind(m.fileMime));
  // textContent, never innerHTML: a message can't inject code.
  const content = m.type === "audio" ? voicePlayer(m)
    : m.type === "file" ? fileBubble(m)
    : el("span", { textContent: m.body });
  list.append(el("li", { className: `msg ${mine ? "mine" : "theirs"}${media ? " has-media" : ""}` },
    content, el("span", { className: "time", textContent: timeOf(m.sentAt) })));
  list.scrollTop = list.scrollHeight;
}

/* Send text, or record a voice note when the box is empty (like WhatsApp) */

const input = $("#send-input");
input.oninput = () => {
  const typing = input.value.trim().length > 0;
  setIcon($("#action-btn"), typing ? "i-send" : "i-mic");
  $("#action-btn").setAttribute("aria-label", typing ? "Send message" : "Record a voice note");
};

$("#send-form").onsubmit = (e) => {
  e.preventDefault();
  const body = input.value.trim();
  if (!body) { startRecording(); return; }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) { flash("Reconnecting. Send again in a moment."); return; }
  state.ws.send(JSON.stringify({ to: state.chat.key, body }));
  input.value = "";
  input.oninput();
  $("#emoji-panel").hidden = true;
};

/* ---------- Emojis ---------- */

const EMOJIS = {
  Faces: "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😎 🤩 🥳 😏 😌 😴 🤔 🤗 😮 😢 😭 😡 🥺 😱 🙄 😬",
  Hands: "👍 👎 👏 🙌 🙏 💪 👋 🤝 ✌️ 🤞 👌 🫶 ☝️ 👀",
  Hearts: "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💖 💯 🔥 ✨ 🎉 🎁",
  Things: "📞 🎤 🎧 📷 💬 ✅ ❌ ⭐ 🌍 🌞 🌙 ☕ 🍕 🎂 ⚽ 🚀",
};

(function buildEmojiPanel() {
  const panel = $("#emoji-panel");
  for (const [group, list] of Object.entries(EMOJIS)) {
    const grid = el("div", { className: "emoji-grid" });
    for (const emoji of list.split(" ")) {
      const b = el("button", { type: "button", textContent: emoji });
      b.setAttribute("aria-label", "Insert " + emoji);
      b.onclick = () => insertAtCursor(input, emoji);
      grid.append(b);
    }
    panel.append(el("p", { className: "label", textContent: group }), grid);
  }
})();

function insertAtCursor(box, text) {
  const start = box.selectionStart ?? box.value.length;
  const end = box.selectionEnd ?? start;
  box.value = box.value.slice(0, start) + text + box.value.slice(end);
  box.setSelectionRange(start + text.length, start + text.length);
  box.focus();
  box.oninput && box.oninput();
}

$("#emoji-btn").onclick = () => { $("#attach-menu").hidden = true; $("#emoji-panel").hidden = !$("#emoji-panel").hidden; };

/* ---------- Voice notes ---------- */

const rec = { recorder: null, stream: null, chunks: [], startedAt: 0, timer: null, send: false };
const MAX_VOICE_SECONDS = 300;
const AUDIO_TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus"];

async function startRecording() {
  if (rec.recorder) return;
  if (!navigator.mediaDevices || !window.MediaRecorder) { flash(mediaError()); return; }
  try {
    rec.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) { flash(mediaError(e)); return; }

  const mimeType = AUDIO_TYPES.find((t) => MediaRecorder.isTypeSupported(t));
  rec.recorder = new MediaRecorder(rec.stream, mimeType ? { mimeType } : undefined);
  rec.chunks = [];
  rec.send = false;
  rec.recorder.ondataavailable = (e) => e.data.size && rec.chunks.push(e.data);
  rec.recorder.onstop = finishRecording;
  rec.recorder.start();
  rec.startedAt = Date.now();

  $("#send-form").hidden = true;
  $("#emoji-panel").hidden = true;
  $("#recorder").hidden = false;
  $("#rec-time").textContent = "0:00";
  rec.timer = setInterval(() => {
    const secs = (Date.now() - rec.startedAt) / 1000;
    $("#rec-time").textContent = mmss(secs);
    if (secs >= MAX_VOICE_SECONDS) stopRecording(true);
  }, 250);
}

function stopRecording(send) {
  if (!rec.recorder || rec.recorder.state === "inactive") return;
  rec.send = send;
  rec.recorder.stop();
}
const cancelRecording = () => stopRecording(false);

async function finishRecording() {
  clearInterval(rec.timer);
  rec.stream.getTracks().forEach((t) => t.stop());
  $("#recorder").hidden = true;
  $("#send-form").hidden = false;

  const seconds = Math.round((Date.now() - rec.startedAt) / 1000);
  const type = (rec.recorder.mimeType || "audio/webm").split(";")[0];
  const chunks = rec.chunks;
  const send = rec.send;
  rec.recorder = null;
  if (!send || !state.chat) return;
  if (seconds < 1) { flash("Hold on a little longer to record."); return; }

  const form = new FormData();
  form.append("file", new File(chunks, "voice", { type }));
  form.append("to", state.chat.key);
  form.append("duration", seconds);
  try {
    const res = await fetch("/api/voice", { method: "POST", body: form, credentials: "same-origin" });
    if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || "Could not send the voice note.");
    showIfOpen(await res.json());
  } catch (err) { flash(err.message); }
}

$("#rec-cancel").onclick = cancelRecording;
$("#rec-send").onclick = () => stopRecording(true);

let playing = null;
function stopAllAudio() { if (playing) { playing.pause(); playing = null; } }

function voicePlayer(m) {
  const audio = new Audio(`/api/voice/${m.id}`);
  audio.preload = "none";
  const play = el("button", { type: "button", className: "play" }, icon("i-play"));
  play.setAttribute("aria-label", "Play voice note");
  const fill = el("div", { className: "fill" });
  const dur = el("span", { className: "dur", textContent: mmss(m.duration || 0) });
  const swap = (id) => play.replaceChildren(icon(id));

  play.onclick = () => {
    if (audio.paused) { stopAllAudio(); playing = audio; audio.play().catch(() => flash("Could not play this voice note.")); }
    else audio.pause();
  };
  audio.onplay = () => swap("i-pause");
  audio.onpause = () => swap("i-play");
  audio.onended = () => { swap("i-play"); fill.style.width = "0"; dur.textContent = mmss(m.duration || 0); };
  audio.ontimeupdate = () => {
    const total = m.duration || audio.duration || 1;
    fill.style.width = Math.min(100, (audio.currentTime / total) * 100) + "%";
    dur.textContent = mmss(audio.currentTime);
  };
  return el("div", { className: "voice" }, play, el("div", { className: "bar2" }, fill), dur);
}

/* ---------- Sending files (images, videos, audio, documents, up to 10 GB) ---------- */

const PICKERS = { media: "#pick-media", doc: "#pick-doc", audio: "#pick-audio" };

$("#attach-btn").onclick = () => { $("#emoji-panel").hidden = true; $("#attach-menu").hidden = !$("#attach-menu").hidden; };
document.querySelectorAll("#attach-menu button").forEach((b) => {
  b.onclick = () => { $("#attach-menu").hidden = true; $(PICKERS[b.dataset.pick]).click(); };
});
Object.values(PICKERS).forEach((sel) => {
  $(sel).onchange = (e) => {
    const file = e.target.files[0];
    e.target.value = "";                     // lets the same file be chosen again later
    if (file) openPreview(file);
  };
});

function docCard(name, size, mime) {
  const kind = fileKind(mime);
  return el("div", { className: "doc" },
    el("div", { className: "ficon" }, icon(kind === "audio" ? "i-audio" : "i-file")),
    el("div", { className: "meta" },
      el("span", { className: "fname ellipsis", textContent: name }),
      el("span", { className: "fsize", textContent: formatSize(size) })));
}

let previewFile = null, previewUrl = null;

function openPreview(file) {
  if (file.size === 0) { flash("That file is empty."); return; }
  if (file.size > MAX_FILE) { flash("Files can be up to 10 GB."); return; }
  previewFile = file;
  const body = $("#preview-body");
  const kind = fileKind(file.type);
  const fallback = () => body.replaceChildren(docCard(file.name, file.size, file.type));
  if (kind === "image" || kind === "video") {
    previewUrl = URL.createObjectURL(file);
    const node = kind === "image" ? el("img", { src: previewUrl, alt: file.name }) : el("video", { src: previewUrl, controls: true });
    node.onerror = fallback;                 // e.g. a format this browser can't show
    body.replaceChildren(node);
  } else fallback();
  $("#preview-name").textContent = file.name;
  $("#preview-size").textContent = formatSize(file.size);
  $("#preview-caption").value = "";
  $("#preview").hidden = false;
  $("#preview-caption").focus();
}

function closePreview() {
  $("#preview").hidden = true;
  $("#preview-body").replaceChildren();
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = null; previewFile = null;
}
$("#preview-close").onclick = closePreview;
$("#preview-form").onsubmit = (e) => {
  e.preventDefault();
  const file = previewFile, caption = $("#preview-caption").value.trim();
  closePreview();
  if (file) sendFile(file, caption);
};

/* A progress bubble in the chat while the file goes up. */
function pendingBubble(file, retry) {
  const bar = el("div");
  const status = el("span", { textContent: "Starting…" });
  const cancel = el("button", { type: "button", textContent: "Cancel" });
  const ui = { cancelled: false, xhr: null, uploadId: null };
  const li = el("li", { className: "msg mine pending" },
    el("div", {},
      docCard(file.name, file.size, file.type),
      el("div", { className: "progress" }, bar),
      el("div", { className: "state" }, status, cancel)));
  cancel.onclick = () => {
    ui.cancelled = true;
    if (ui.xhr) ui.xhr.abort();
    if (ui.uploadId) api(`/api/files/${ui.uploadId}`, { method: "DELETE" }).catch(() => {});
    li.remove();
  };
  ui.progress = (sent) => {
    const pct = Math.min(100, (sent / file.size) * 100);
    bar.style.width = pct + "%";
    status.textContent = `${formatSize(Math.min(sent, file.size))} of ${formatSize(file.size)}`;
  };
  ui.fail = (message) => {
    const again = el("button", { type: "button", textContent: "Try again" });
    again.onclick = () => { li.remove(); retry(); };
    status.textContent = message;
    cancel.replaceWith(again);
  };
  ui.remove = () => li.remove();
  if (state.chat) { $("#messages").append(li); $("#messages").scrollTop = $("#messages").scrollHeight; }
  return ui;
}

async function sendFile(file, caption) {
  if (!state.chat) return;
  const to = state.chat.key;
  const ui = pendingBubble(file, () => sendFile(file, caption));
  try {
    const message = await uploadFile(file, caption, to, ui);
    ui.remove();
    showIfOpen(message);
  } catch (err) {
    if (ui.cancelled) ui.remove(); else ui.fail(err.message);
  }
}

/* The file goes up in 8 MB pieces. If the connection drops, only the current piece is repeated,
   starting from wherever the server says it got to. */
async function uploadFile(file, caption, to, ui) {
  const init = await api("/api/files/init", { body: { to, name: file.name, size: file.size, mime: file.type, caption } });
  ui.uploadId = init.uploadId;
  let received = 0, failures = 0;

  while (received < file.size) {
    if (ui.cancelled) throw new Error("Cancelled");
    const end = Math.min(received + init.chunkSize, file.size);
    try {
      const base = received;
      const reply = await putPiece(init.uploadId, base, file.slice(base, end), (loaded) => ui.progress(base + loaded), ui);
      received = reply.received;
      failures = 0;
    } catch (err) {
      if (ui.cancelled) throw err;
      if (++failures > 8) throw new Error("Upload failed. Check your connection.");
      await sleep(Math.min(1000 * 2 ** failures, 15000));
      try { received = (await api(`/api/files/${init.uploadId}/status`)).received; } catch { /* try again next loop */ }
    }
  }
  return api(`/api/files/${init.uploadId}/complete`, { body: {} });
}

function putPiece(uploadId, offset, blob, onProgress, ui) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    ui.xhr = xhr;
    xhr.open("PUT", `/api/files/${uploadId}?offset=${offset}`);
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.upload.onprogress = (e) => onProgress(e.loaded);
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 300
      ? resolve(JSON.parse(xhr.responseText)) : reject(new Error("HTTP " + xhr.status)));
    xhr.onerror = () => reject(new Error("Network error"));
    xhr.onabort = () => reject(new Error("Cancelled"));
    xhr.send(blob);
  });
}

/* How a received file looks in the chat */
function fileBubble(m) {
  const url = `/api/files/${m.id}`;
  const download = `${url}?download=true`;
  const kind = fileKind(m.fileMime);
  const wrap = el("div", { className: "attach" });

  if (kind === "image") {
    const img = el("img", { src: url, alt: m.fileName, loading: "lazy" });
    img.onclick = () => openViewer(url, download, m.fileName);
    wrap.append(img);
  } else if (kind === "video") {
    wrap.append(el("video", { src: url, controls: true, preload: "metadata" }));
  } else {
    const card = docCard(m.fileName, m.fileSize, m.fileMime);
    const link = el("a", { href: download, className: "ibtn", download: m.fileName }, icon("i-download"));
    link.setAttribute("aria-label", "Download " + m.fileName);
    card.append(link);
    wrap.append(card);
    if (kind === "audio") wrap.append(el("audio", { src: url, controls: true, preload: "none" }));
  }
  if (m.body) wrap.append(el("span", { className: "caption", textContent: m.body }));
  return wrap;
}

function openViewer(url, download, name) {
  $("#viewer-img").src = url;
  $("#viewer-name").textContent = name;
  $("#viewer-download").href = download;
  $("#viewer-download").setAttribute("download", name);
  $("#viewer").hidden = false;
}
function closeViewer() { $("#viewer").hidden = true; $("#viewer-img").removeAttribute("src"); }
$("#viewer-close").onclick = closeViewer;

document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  closeViewer(); closePreview();
  $("#emoji-panel").hidden = true; $("#attach-menu").hidden = true; $("#menu").hidden = true;
});

/* ---------- Live connection ---------- */

function connect() {
  if (state.ws) return;
  const scheme = location.protocol === "https:" ? "wss://" : "ws://";
  const ws = new WebSocket(scheme + location.host + "/ws/chat");
  state.ws = ws;
  ws.onopen = () => { state.retries = 0; };
  ws.onmessage = (e) => onSocket(JSON.parse(e.data));
  ws.onclose = () => {
    if (state.ws === ws) state.ws = null;
    if (state.me) setTimeout(connect, Math.min(1000 * 2 ** state.retries++, 15000));
  };
}

function onSocket(msg) {
  if (msg.error) { flash(msg.error); return; }
  if (msg.type === "signal") { onSignal(msg); return; }
  showIfOpen(msg);
}

function sendSignal(to, payload) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify({ type: "signal", to, payload }));
  }
}

/* ---------- Calls (WebRTC) ---------- */
/* The Java server only passes small "signals" between the two browsers.
   Sound and video go directly from one browser to the other. */

let call = null;       // the call in progress (or being set up)
let incoming = null;   // a call that is ringing

const newCall = (props) => ({ pc: null, stream: null, pendingIce: [], timer: null, ringTimeout: null, ...props });
const stopStream = (s) => s && s.getTracks().forEach((t) => t.stop());

async function getMedia(type) {
  if (!navigator.mediaDevices) throw new Error("insecure");
  return navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true },
    video: type === "video" ? { facingMode: "user" } : false,
  });
}

async function createPeer(c) {
  let config;
  try { config = await api("/api/ice"); }
  catch { config = { iceServers: [{ urls: "stun:stun.l.google.com:19302" }] }; }

  const pc = new RTCPeerConnection(config);
  c.pc = pc;
  c.stream.getTracks().forEach((t) => pc.addTrack(t, c.stream));
  pc.onicecandidate = (e) => { if (e.candidate) sendSignal(c.peer, { kind: "ice", candidate: e.candidate }); };
  pc.ontrack = (e) => { $("#remote-video").srcObject = e.streams[0]; };
  pc.onconnectionstatechange = () => {
    if (call !== c) return;
    if (pc.connectionState === "connected") onConnected(c);
    if (pc.connectionState === "failed") endCall("Connection lost", true);
  };
  return pc;
}

function showCallScreen(c, status) {
  $("#call").classList.toggle("voice", c.type === "voice");
  paintAvatar($("#call-avatar"), c.name, c.peer);
  $("#call-name").textContent = c.name;
  $("#call-status").textContent = status;
  $("#toggle-cam").hidden = c.type !== "video";
  for (const [id, on, off, label] of [["#toggle-mic", "i-mic", "i-mic-off", "microphone"], ["#toggle-cam", "i-cam", "i-cam-off", "camera"]]) {
    $(id).classList.remove("off");
    setIcon($(id), on);
    $(id).setAttribute("aria-label", (label === "microphone" ? "Mute " : "Turn off ") + label);
  }
  $("#call").hidden = false;
}

const setStatus = (text) => ($("#call-status").textContent = text);

function onConnected(c) {
  if (c.connectedAt) return;
  c.connectedAt = Date.now();
  clearTimeout(c.ringTimeout);
  setStatus("0:00");
  c.timer = setInterval(() => setStatus(mmss((Date.now() - c.connectedAt) / 1000)), 1000);
}

async function flushIce(c) {
  for (const cand of c.pendingIce.splice(0)) await c.pc.addIceCandidate(cand).catch(() => {});
}

function addIce(c, candidate) {
  if (c.pc && c.pc.remoteDescription) c.pc.addIceCandidate(candidate).catch(() => {});
  else c.pendingIce.push(candidate);     // arrived before we were ready; added later
}

async function startCall(type) {
  if (call || incoming || !state.chat) return;
  const { key: peer, displayName: name } = state.chat;
  if (peer === state.me.key) { flash("You can't call yourself."); return; }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) { flash("Reconnecting. Try again in a moment."); return; }

  const c = newCall({ peer, name, type, role: "caller" });
  call = c;
  showCallScreen(c, "Starting…");
  try {
    c.stream = await getMedia(type);
  } catch (e) {
    if (call === c) { call = null; $("#call").hidden = true; }
    flash(mediaError(e));
    return;
  }
  if (call !== c) { stopStream(c.stream); return; }
  $("#local-video").srcObject = c.stream;

  const pc = await createPeer(c);
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  if (call !== c) { pc.close(); return; }
  sendSignal(peer, { kind: "offer", callType: type, sdp: pc.localDescription });
  setStatus("Calling…");
  c.ringTimeout = setTimeout(() => endCall("No answer", true), 45000);
}

async function onOffer(from, p) {
  if (call || incoming) { sendSignal(from, { kind: "reject", reason: "busy" }); return; }
  const type = p.callType === "video" ? "video" : "voice";
  const inc = { peer: from, name: formatKey(from), type, offer: p.sdp, ice: [] };
  incoming = inc;                                  // set first so a second call sees "busy"
  try { inc.name = (await api(`/api/users/by-key/${from}`)).displayName; } catch { /* keep the key */ }
  if (incoming !== inc) return;                    // caller hung up while we looked up the name
  paintAvatar($("#incoming-avatar"), inc.name, from);
  $("#incoming-name").textContent = inc.name;
  $("#incoming-type").textContent = type === "video" ? "Zavelo video call" : "Zavelo voice call";
  $("#incoming").hidden = false;
  startRinging();
}

async function answerCall() {
  const inc = incoming;
  if (!inc || call) return;
  dismissIncoming();

  const c = newCall({ peer: inc.peer, name: inc.name, type: inc.type, role: "callee" });
  c.pendingIce = inc.ice;
  call = c;
  showCallScreen(c, "Connecting…");
  try {
    c.stream = await getMedia(inc.type);
  } catch (e) {
    if (call === c) { call = null; $("#call").hidden = true; sendSignal(inc.peer, { kind: "reject", reason: "media" }); }
    flash(mediaError(e));
    return;
  }
  if (call !== c) { stopStream(c.stream); return; }
  $("#local-video").srcObject = c.stream;

  const pc = await createPeer(c);
  await pc.setRemoteDescription(inc.offer);
  await flushIce(c);
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  sendSignal(inc.peer, { kind: "answer", sdp: pc.localDescription });
}

async function onAnswer(p) {
  const c = call;
  clearTimeout(c.ringTimeout);
  await c.pc.setRemoteDescription(p.sdp);
  await flushIce(c);
  setStatus("Connecting…");
}

function onSignal(msg) {
  const p = msg.payload || {};

  // One of my other tabs answered or declined this call: stop ringing here.
  if (msg.self) {
    if (incoming && incoming.peer === msg.peer) dismissIncoming();
    return;
  }

  const from = msg.from;
  const inCallWith = call && call.peer === from;
  switch (p.kind) {
    case "offer": onOffer(from, p); break;
    case "answer": if (inCallWith && call.role === "caller") onAnswer(p); break;
    case "ice":
      if (inCallWith) addIce(call, p.candidate);
      else if (incoming && incoming.peer === from) incoming.ice.push(p.candidate);
      break;
    case "reject": if (inCallWith) endCall(p.reason === "busy" ? "They are on another call" : "Call declined", false); break;
    case "unavailable": if (inCallWith) endCall("They are offline", false); break;
    case "hangup":
      if (inCallWith) endCall("Call ended", false);
      else if (incoming && incoming.peer === from) { const name = incoming.name; dismissIncoming(); flash(`Missed call from ${name}`); }
      break;
  }
}

function endCall(reason, notifyPeer) {
  const c = call;
  if (!c) return;
  call = null;
  clearTimeout(c.ringTimeout);
  clearInterval(c.timer);
  if (notifyPeer) sendSignal(c.peer, { kind: "hangup" });
  stopStream(c.stream);
  if (c.pc) c.pc.close();
  $("#remote-video").srcObject = null;
  $("#local-video").srcObject = null;
  $("#call").hidden = true;
  if (reason) flash(reason);
}

function dismissIncoming() {
  incoming = null;
  stopRinging();
  $("#incoming").hidden = true;
}

$("#voice-call").onclick = () => startCall("voice");
$("#video-call").onclick = () => startCall("video");
$("#answer").onclick = answerCall;
$("#decline").onclick = () => {
  if (!incoming) return;
  sendSignal(incoming.peer, { kind: "reject" });
  dismissIncoming();
};
$("#end-call").onclick = () => endCall("Call ended", true);

/* Mute and camera buttons: the icon shows the state, with a slash when it is off. */
function toggleTrack(btn, getTracks, onIcon, offIcon, onLabel, offLabel) {
  if (!call || !call.stream) return;
  const tracks = getTracks(call.stream);
  if (!tracks.length) return;
  const on = !tracks[0].enabled;
  tracks.forEach((t) => (t.enabled = on));
  btn.classList.toggle("off", !on);
  setIcon(btn, on ? onIcon : offIcon);
  btn.setAttribute("aria-label", on ? onLabel : offLabel);
}
$("#toggle-mic").onclick = (e) => toggleTrack(e.currentTarget, (s) => s.getAudioTracks(), "i-mic", "i-mic-off", "Mute microphone", "Unmute microphone");
$("#toggle-cam").onclick = (e) => toggleTrack(e.currentTarget, (s) => s.getVideoTracks(), "i-cam", "i-cam-off", "Turn camera off", "Turn camera on");

window.addEventListener("pagehide", () => { if (call) sendSignal(call.peer, { kind: "hangup" }); });

/* Ringtone: soft beeps made in the browser, no audio files needed. */
let ringCtx = null, ringTimer = null;
function startRinging() {
  try { ringCtx = ringCtx || new AudioContext(); ringCtx.resume(); } catch { return; }
  const beep = () => {
    const osc = ringCtx.createOscillator();
    const gain = ringCtx.createGain();
    osc.frequency.value = 480;
    gain.gain.value = 0.08;
    osc.connect(gain);
    gain.connect(ringCtx.destination);
    osc.start();
    osc.stop(ringCtx.currentTime + 0.35);
  };
  beep();
  ringTimer = setInterval(beep, 1500);
}
function stopRinging() { clearInterval(ringTimer); ringTimer = null; }

/* ---------- Opening animation (4 seconds, tap to skip) ---------- */

function runSplash() {
  const splash = $("#splash");
  const calm = matchMedia("(prefers-reduced-motion: reduce)").matches;   // no bouncing for people who turn motion off
  splash.classList.toggle("calm", calm);
  return new Promise((resolve) => {
    const done = () => { if (splash.isConnected) { splash.remove(); resolve(); } };
    setTimeout(done, calm ? 1200 : 4000);
    splash.addEventListener("click", done);
  });
}
const splashDone = runSplash();

/* ---------- App lock (kept on this device only) ---------- */
/* This is a privacy screen, like the lock in WhatsApp: it hides your chats from someone
   holding your unlocked phone. It does not replace your phone's own lock. */

const LOCK_KEY = "zavelo.lock", FAIL_KEY = "zavelo.lock.fails";
const PBKDF2_ROUNDS = 210000;
const enc = new TextEncoder();
const b64 = (bytes) => btoa(String.fromCharCode(...bytes));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const readJson = (k) => { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } };
const lockConfig = () => readJson(LOCK_KEY);
const saveLock = (cfg) => localStorage.setItem(LOCK_KEY, JSON.stringify(cfg));
function clearLock() { localStorage.removeItem(LOCK_KEY); localStorage.removeItem(FAIL_KEY); }

async function hashSecret(secret, salt) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: PBKDF2_ROUNDS }, key, 256);
  return b64(new Uint8Array(bits));
}
async function checkSecret(secret, cfg) { return (await hashSecret(secret, unb64(cfg.salt))) === cfg.hash; }

/* After 5 wrong tries the lock makes you wait: 30 s, then 60 s, 2 min, and so on up to 15 min. */
function cooldownLeft() {
  const f = readJson(FAIL_KEY);
  return f && f.until > Date.now() ? Math.ceil((f.until - Date.now()) / 1000) : 0;
}
function recordFail() {
  const f = readJson(FAIL_KEY) || { count: 0, until: 0 };
  f.count++;
  if (f.count % 5 === 0) f.until = Date.now() + Math.min(30 * 2 ** (f.count / 5 - 1), 900) * 1000;
  localStorage.setItem(FAIL_KEY, JSON.stringify(f));
}
const clearFails = () => localStorage.removeItem(FAIL_KEY);

/* Fingerprint / face: uses the phone's own screen-lock sensor through the browser (WebAuthn). */
async function biometricAvailable() {
  try {
    return !!(window.PublicKeyCredential && await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable());
  } catch { return false; }
}
async function registerBiometric() {
  const cred = await navigator.credentials.create({
    publicKey: {
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      rp: { name: "Zavelo" },
      user: { id: crypto.getRandomValues(new Uint8Array(16)), name: state.me.key, displayName: state.me.displayName },
      pubKeyCredParams: [{ type: "public-key", alg: -7 }, { type: "public-key", alg: -257 }],
      authenticatorSelection: { authenticatorAttachment: "platform", userVerification: "required", residentKey: "discouraged" },
      timeout: 60000,
    },
  });
  return b64(new Uint8Array(cred.rawId));
}
async function verifyBiometric(cfg) {
  await navigator.credentials.get({
    publicKey: {
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      allowCredentials: [{ type: "public-key", id: unb64(cfg.bio), transports: ["internal"] }],
      userVerification: "required",
      timeout: 60000,
    },
  });
}

/* The lock screen */
const lockState = { locked: false, hiddenAt: 0 };
let coolTimer = null;

function lockApp() {
  const cfg = lockConfig();
  if (!cfg || lockState.locked) return;
  lockState.locked = true;
  const pin = cfg.type === "pin";
  const input = $("#lock-input");
  input.value = "";
  input.setAttribute("inputmode", pin ? "numeric" : "text");
  input.setAttribute("maxlength", pin ? String(cfg.len) : "64");
  input.setAttribute("aria-label", pin ? "PIN" : "Password");
  input.placeholder = pin ? "Enter PIN" : "Enter password";
  $("#lock-msg").textContent = "";
  $("#lock-bio-btn").hidden = !cfg.bio;
  $("#lock").hidden = false;
  updateCooldown();
  if (cfg.bio) splashDone.then(() => { if (lockState.locked) tryBiometric(); });
  else splashDone.then(() => { if (lockState.locked && !input.disabled) input.focus(); });
}

function unlock() {
  lockState.locked = false;
  clearInterval(coolTimer);
  $("#lock").hidden = true;
  $("#lock-input").value = "";
  $("#lock-input").blur();
}

function updateCooldown() {
  clearInterval(coolTimer);
  const tick = () => {
    const secs = cooldownLeft();
    $("#lock-input").disabled = secs > 0;
    $("#lock-submit").disabled = secs > 0;
    if (secs > 0) $("#lock-msg").textContent = `Too many tries. Wait ${secs} s.`;
    else {
      clearInterval(coolTimer);
      if ($("#lock-msg").textContent.startsWith("Too many")) $("#lock-msg").textContent = "";
    }
  };
  tick();
  if (cooldownLeft() > 0) coolTimer = setInterval(tick, 1000);
}

async function tryUnlock() {
  const cfg = lockConfig();
  const value = $("#lock-input").value;
  if (!cfg || !value || cooldownLeft() > 0) return;
  if (await checkSecret(value, cfg)) { clearFails(); unlock(); return; }
  recordFail();
  $("#lock-input").value = "";
  $("#lock-msg").textContent = cfg.type === "pin" ? "Wrong PIN." : "Wrong password.";
  updateCooldown();
}

async function tryBiometric() {
  const cfg = lockConfig();
  if (!cfg || !cfg.bio) return;
  try { await verifyBiometric(cfg); clearFails(); unlock(); }
  catch { if (lockState.locked && cooldownLeft() === 0) $("#lock-msg").textContent = "Fingerprint did not work. Use your " + (cfg.type === "pin" ? "PIN." : "password."); }
}

$("#lock-form").onsubmit = (e) => { e.preventDefault(); tryUnlock(); };
$("#lock-input").oninput = (e) => {
  const cfg = lockConfig();
  if (!cfg || cfg.type !== "pin") return;
  e.target.value = e.target.value.replace(/\D/g, "").slice(0, cfg.len);
  if (e.target.value.length === cfg.len) tryUnlock();      // a PIN unlocks as soon as the last digit is typed
};
$("#lock-bio-btn").onclick = tryBiometric;
$("#lock-forgot").onclick = () => { if (confirm("Signing out removes the lock from this device. You will need to sign in again.")) signOut(); };

/* Lock again when you come back after being away */
document.addEventListener("visibilitychange", () => {
  const cfg = lockConfig();
  if (!cfg || !state.me) return;
  if (document.hidden) lockState.hiddenAt = Date.now();
  else if (lockState.hiddenAt && (Date.now() - lockState.hiddenAt) / 1000 >= cfg.timeout) lockApp();
});

/* ---------- Settings ---------- */

let bioAvailable = false, afterVerify = null;
const showLockPane = (name) => {
  $("#lock-off").hidden = name !== "off";
  $("#lock-setup").hidden = name !== "setup";
  $("#lock-verify").hidden = name !== "verify";
  $("#lock-on").hidden = name !== "on";
};

function renderLockSettings() {
  const cfg = lockConfig();
  if (!cfg) { showLockPane("off"); return; }
  $("#lock-summary").textContent = "Locked with " + (cfg.type === "pin" ? "a PIN" : "a password") +
    (cfg.bio ? " and fingerprint or face unlock." : ".");
  $("#lock-timeout").value = String(cfg.timeout);
  showLockPane("on");
}

async function openSettings() {
  $("#menu").hidden = true;
  $("#settings").hidden = false;
  renderLockSettings();
  refreshInstallRow();
  bioAvailable = await biometricAvailable();
  $("#bio-choice").hidden = !bioAvailable;
}
$("#menu-settings").onclick = openSettings;
$("#settings-close").onclick = () => { $("#settings").hidden = true; };

function openSetup() {
  document.querySelector('input[name="lock-type"][value="pin"]').checked = true;
  applyLockType();
  $("#lock-bio").checked = bioAvailable;
  $("#lock-error").textContent = "";
  showLockPane("setup");
  $("#lock-secret").focus();
}

function applyLockType() {
  const pin = document.querySelector('input[name="lock-type"]:checked').value === "pin";
  for (const id of ["#lock-secret", "#lock-confirm"]) {
    $(id).value = "";
    $(id).setAttribute("inputmode", pin ? "numeric" : "text");
    $(id).setAttribute("maxlength", pin ? "6" : "64");
  }
  $("#lock-secret-label").textContent = pin ? "New PIN" : "New password";
}
document.querySelectorAll('input[name="lock-type"]').forEach((r) => (r.onchange = applyLockType));

$("#lock-enable").onclick = openSetup;
$("#lock-cancel").onclick = renderLockSettings;

$("#lock-setup").onsubmit = async (e) => {
  e.preventDefault();
  const err = $("#lock-error");
  err.textContent = "";
  const type = document.querySelector('input[name="lock-type"]:checked').value;
  const secret = $("#lock-secret").value;
  if (type === "pin" && !/^\d{4,6}$/.test(secret)) { err.textContent = "A PIN must be 4 to 6 digits."; return; }
  if (type === "password" && secret.length < 6) { err.textContent = "A password must be at least 6 characters."; return; }
  if (secret !== $("#lock-confirm").value) { err.textContent = "The two entries do not match."; return; }
  if (!window.crypto || !crypto.subtle) { err.textContent = "The lock needs a secure connection (HTTPS)."; return; }

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const old = lockConfig();
  const cfg = {
    owner: state.me.key, type, len: secret.length, salt: b64(salt),
    hash: await hashSecret(secret, salt), bio: null, timeout: old ? old.timeout : 60,
  };
  let note = "App lock is on";
  if (bioAvailable && $("#lock-bio").checked) {
    try { cfg.bio = await registerBiometric(); }
    catch { note = "App lock is on. Fingerprint was not set up."; }
  }
  saveLock(cfg);
  clearFails();
  renderLockSettings();
  flash(note);
};

/* Changing or removing the lock asks for the current PIN or password first */
function verifyThen(action) {
  const cfg = lockConfig();
  if (!cfg) return;
  afterVerify = action;
  $("#verify-label").textContent = cfg.type === "pin" ? "Enter your current PIN" : "Enter your current password";
  $("#verify-secret").setAttribute("inputmode", cfg.type === "pin" ? "numeric" : "text");
  $("#verify-secret").value = "";
  $("#verify-error").textContent = "";
  showLockPane("verify");
  $("#verify-secret").focus();
}
$("#lock-verify").onsubmit = async (e) => {
  e.preventDefault();
  const cfg = lockConfig();
  const wait = cooldownLeft();
  if (wait > 0) { $("#verify-error").textContent = `Too many tries. Wait ${wait} s.`; return; }
  if (await checkSecret($("#verify-secret").value, cfg)) { clearFails(); const go = afterVerify; afterVerify = null; go(); }
  else { recordFail(); $("#verify-secret").value = ""; $("#verify-error").textContent = "That is not right."; }
};
$("#verify-cancel").onclick = renderLockSettings;

$("#lock-change").onclick = () => verifyThen(openSetup);
$("#lock-disable").onclick = () => verifyThen(() => { clearLock(); renderLockSettings(); flash("App lock is off"); });
$("#lock-now").onclick = () => { $("#settings").hidden = true; lockApp(); };
$("#lock-timeout").onchange = (e) => {
  const cfg = lockConfig();
  if (cfg) { cfg.timeout = Number(e.target.value); saveLock(cfg); }
};

/* ---------- Install as an app ---------- */

let installEvent = null;
const isStandalone = () => matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;

function refreshInstallRow() {
  const text = $("#install-text"), btn = $("#install-btn");
  btn.hidden = true;
  if (isStandalone()) text.textContent = "Zavelo is installed on this device.";
  else if (installEvent) { text.textContent = "Add Zavelo to your home screen or desktop so it opens like an app."; btn.hidden = false; }
  else if (/iphone|ipad|ipod/i.test(navigator.userAgent)) text.textContent = "On iPhone or iPad: tap the Share button, then Add to Home Screen.";
  else text.textContent = "To install, open your browser menu and choose Install app or Add to Home screen.";
}
window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); installEvent = e; refreshInstallRow(); });
window.addEventListener("appinstalled", () => { installEvent = null; refreshInstallRow(); flash("Zavelo installed"); });
$("#install-btn").onclick = async () => {
  if (!installEvent) return;
  installEvent.prompt();
  await installEvent.userChoice;
  installEvent = null;
  refreshInstallRow();
};

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));
}

/* ---------- Start ---------- */

function boot() {
  const cfg = lockConfig();
  if (cfg) lockApp();                      // a fresh open starts locked, before anything is shown
  api("/api/me").then((profile) => {
    if (cfg && cfg.owner !== profile.key) { clearLock(); unlock(); }   // the lock belonged to someone else
    enter(profile);
  }).catch((err) => {
    if (err instanceof TypeError) { setTimeout(boot, 4000); flash("Cannot reach Zavelo. Trying again..."); return; }
    clearLock(); unlock();                 // not signed in, so there is nothing to protect
    $("#auth").hidden = false;
  });
}
boot();
