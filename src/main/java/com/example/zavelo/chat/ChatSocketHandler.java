package com.example.zavelo.chat;

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

    public ChatSocketHandler(UserRepository users, MessageRepository messages, ObjectMapper json) {
        this.users = users;
        this.messages = messages;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (session.getPrincipal() == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        online.computeIfAbsent(session.getPrincipal().getName(), k -> ConcurrentHashMap.newKeySet()).add(session);
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

        // Nobody online to ring.
        if (!delivered && kind.equals("offer")) {
            sendTo(session, Map.of("type", "signal", "from", recipient.getConnectKey(),
                    "payload", Map.of("kind", "unavailable")));
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

    private void sendTo(WebSocketSession session, Object payload) {
        if (!session.isOpen()) return;
        try {
            String msg = json.writeValueAsString(payload);
            synchronized (session) {   // a session can only send one message at a time
                session.sendMessage(new TextMessage(msg));
            }
        } catch (IOException ignored) {
            // The connection dropped; afterConnectionClosed cleans up.
        }
    }
}
