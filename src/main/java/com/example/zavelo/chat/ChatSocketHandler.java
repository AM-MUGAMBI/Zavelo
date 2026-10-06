package com.example.zavelo.chat;

import com.example.zavelo.push.PushService;
import com.example.zavelo.user.AppUser;
import com.example.zavelo.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One WebSocket per open browser tab. The user is identified by the login session,
 * so the client can never pretend to be someone else.
 *
 * Two kinds of traffic go through here:
 *  - chat messages: {"to":"123456789","body":"hi"}   -> saved, then pushed to both people
 *  - call signals:  {"type":"signal","to":"...","payload":{kind:"offer"|"answer"|"ice"|"reject"|"hangup"}}
 *                   -> only relayed to the other person, never saved
 */
@Component
public class ChatSocketHandler extends TextWebSocketHandler {

    public record Incoming(String type, String to, String body, JsonNode payload) {}

    private static final int MAX_LENGTH = 2000;
    private static final int MAX_SIGNAL_SIZE = 60_000;
    private static final Set<String> SIGNAL_KINDS = Set.of("offer", "answer", "ice", "reject", "hangup");

    private final Map<String, Set<WebSocketSession>> online = new ConcurrentHashMap<>();
    private final UserRepository users;
    private final MessageRepository messages;
    private final ObjectMapper json;
    private final PushService push;

    /**
     * A call that is ringing for someone whose app may be closed. If they open Zavelo from the notification
     * within 45 seconds, the call is handed to them so they can still answer. Key: callee username + "|" + caller key.
     */
    private static final long RING_MILLIS = 45_000;
    private static final int MAX_HELD_FRAMES = 60;
    private record PendingCall(long expiresAt, List<String> frames) {}
    private final Map<String, PendingCall> pending = new ConcurrentHashMap<>();

    public ChatSocketHandler(UserRepository users, MessageRepository messages, ObjectMapper json, PushService push) {
        this.users = users;
        this.messages = messages;
        this.json = json;
        this.push = push;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (session.getPrincipal() == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String username = session.getPrincipal().getName();
        online.computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet()).add(session);
        replayHeldCalls(username, session);
    }

    /** Hand a still-ringing call to a device that has only just opened Zavelo. */
    private void replayHeldCalls(String username, WebSocketSession session) {
        long now = System.currentTimeMillis();
        String prefix = username + "|";
        pending.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        pending.forEach((key, call) -> {
            if (!key.startsWith(prefix)) return;
            List<String> frames;
            synchronized (call.frames()) { frames = new ArrayList<>(call.frames()); }
            for (String frame : frames) sendRaw(session, frame);
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getPrincipal() == null) return;
        Set<WebSocketSession> sessions = online.get(session.getPrincipal().getName());
        if (sessions != null) sessions.remove(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage text) throws IOException {
        if (session.getPrincipal() == null) return;

        Incoming in;
        try {
            in = json.readValue(text.getPayload(), Incoming.class);
        } catch (Exception e) {
            sendTo(session, Map.of("error", "Invalid message."));
            return;
        }

        AppUser sender = users.findByUsername(session.getPrincipal().getName()).orElse(null);
        String toKey = in.to() == null ? "" : in.to().replaceAll("\\D", "");
        Optional<AppUser> recipient = users.findByConnectKey(toKey);
        if (sender == null || recipient.isEmpty()) {
            sendTo(session, Map.of("error", "No one has that key."));
            return;
        }

        if ("signal".equals(in.type())) {
            relaySignal(session, sender, recipient.get(), in.payload());
            return;
        }

        String body = in.body() == null ? "" : in.body().trim();
        if (body.isEmpty() || body.length() > MAX_LENGTH) {
            sendTo(session, Map.of("error", "Messages must be 1-" + MAX_LENGTH + " characters."));
            return;
        }

        Message saved = messages.save(new Message(sender.getConnectKey(), recipient.get().getConnectKey(), body));
        deliver(sender, recipient.get(), saved);
    }

    /** Push a saved message to every open tab of both people. Also used by voice-note uploads. */
    public void deliver(AppUser sender, AppUser recipient, Message saved) {
        MessageDto dto = MessageDto.of(saved);
        // A set, so messaging yourself delivers once.
        Set<String> usernames = new LinkedHashSet<>(List.of(sender.getUsername(), recipient.getUsername()));
        for (String username : usernames) {
            for (WebSocketSession s : online.getOrDefault(username, Set.of())) {
                sendTo(s, dto);
            }
        }
        // Tell the other person's phone, even if Zavelo is closed (the phone skips it if Zavelo is on screen).
        if (!sender.getId().equals(recipient.getId())) {
            push.notifyMessage(recipient, sender, ChatController.preview(saved));
        }
    }

    private void relaySignal(WebSocketSession session, AppUser sender, AppUser recipient, JsonNode payload) {
        if (payload == null || !payload.hasNonNull("kind") || payload.toString().length() > MAX_SIGNAL_SIZE) return;
        String kind = payload.get("kind").asText();
        if (!SIGNAL_KINDS.contains(kind)) return;

        if (sender.getId().equals(recipient.getId())) {
            if (kind.equals("offer")) sendTo(session, Map.of("error", "You can't call yourself."));
            return;
        }

        // The "from" is set here from the login session, never taken from the browser.
        Map<String, Object> out = Map.of("type", "signal", "from", sender.getConnectKey(), "payload", payload);
        boolean delivered = false;
        for (WebSocketSession s : online.getOrDefault(recipient.getUsername(), Set.of())) {
            if (s.isOpen()) {
                sendTo(s, out);
                delivered = true;
            }
        }

        // The ringing call is kept for 45 seconds so a phone woken by a notification can still pick it up.
        String callKey = recipient.getUsername() + "|" + sender.getConnectKey();
        switch (kind) {
            case "offer" -> {
                boolean reachable = push.hasDevice(recipient);
                if (!delivered && !reachable) {
                    // Nobody online to ring and no phone to notify.
                    sendTo(session, Map.of("type", "signal", "from", recipient.getConnectKey(),
                            "payload", Map.of("kind", "unavailable")));
                } else if (reachable) {
                    long now = System.currentTimeMillis();
                    pending.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
                    List<String> frames = new ArrayList<>();
                    frames.add(toJson(out));
                    pending.put(callKey, new PendingCall(now + RING_MILLIS, frames));
                    push.notifyCall(recipient, sender, payload.path("callType").asText("audio"));
                }
            }
            case "ice" -> {
                PendingCall call = pending.get(callKey);
                if (call != null) {
                    synchronized (call.frames()) {
                        if (call.frames().size() < MAX_HELD_FRAMES) call.frames().add(toJson(out));
                    }
                }
            }
            case "hangup" -> {
                // The caller gave up while it was still ringing: leave a "Missed call" notification.
                if (pending.remove(callKey) != null) push.notifyCallEnded(recipient, sender);
                pending.remove(sender.getUsername() + "|" + recipient.getConnectKey());
            }
            case "answer", "reject" ->
                // I (the callee) answered or declined: the call is no longer waiting for me.
                pending.remove(sender.getUsername() + "|" + recipient.getConnectKey());
            default -> { }
        }

        // If the call was answered or declined in this tab, stop the ringing in my other tabs.
        boolean busy = payload.path("reason").asText("").equals("busy");
        if ((kind.equals("answer") || (kind.equals("reject") && !busy))) {
            for (WebSocketSession s : online.getOrDefault(sender.getUsername(), Set.of())) {
                if (s != session) {
                    sendTo(s, Map.of("type", "signal", "self", true,
                            "peer", recipient.getConnectKey(), "payload", Map.of("kind", kind)));
                }
            }
        }
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (IOException e) {
            return "{}";
        }
    }

    private void sendTo(WebSocketSession session, Object payload) {
        sendRaw(session, toJson(payload));
    }

    private void sendRaw(WebSocketSession session, String msg) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {   // a session can only send one message at a time
                session.sendMessage(new TextMessage(msg));
            }
        } catch (IOException ignored) {
            // The connection dropped; afterConnectionClosed cleans up.
        }
    }
}
