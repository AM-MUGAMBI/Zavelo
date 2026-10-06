package com.example.zavelo.chat;

import com.example.zavelo.user.AppUser;
import com.example.zavelo.user.UserRepository;
import com.example.zavelo.user.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final MessageRepository messages;
    private final UserRepository users;
    private final UserService userService;

    public ChatController(MessageRepository messages, UserRepository users, UserService userService) {
        this.messages = messages;
        this.users = users;
        this.userService = userService;
    }

    /** Full history with one person, oldest first. */
    @GetMapping("/messages/{key}")
    public List<MessageDto> history(@PathVariable String key, Authentication auth) {
        String me = users.findByUsername(auth.getName()).orElseThrow().getConnectKey();
        String other = userService.findByKey(key).getConnectKey();
        return messages.conversation(me, other).stream().map(MessageDto::of).toList();
    }

    /** One row per person you have talked to, most recent first. */
    @GetMapping("/chats")
    public List<Map<String, Object>> chats(Authentication auth) {
        String me = users.findByUsername(auth.getName()).orElseThrow().getConnectKey();
        Map<String, Message> latest = new LinkedHashMap<>();
        for (Message m : messages.involving(me)) {
            String other = m.getSenderKey().equals(me) ? m.getRecipientKey() : m.getSenderKey();
            latest.putIfAbsent(other, m);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        latest.forEach((key, m) -> {
            String name = users.findByConnectKey(key).map(AppUser::getDisplayName).orElse("Unknown");
            String preview = preview(m);
            out.add(Map.of("key", key, "displayName", name, "lastMessage", preview, "sentAt", m.getSentAt()));
        });
        return out;
    }

    static String preview(Message m) {
        if (m.isAudio()) return "Voice note";
        if (!m.isFile()) return m.getBody();
        String mime = m.getFileMime() == null ? "" : m.getFileMime();
        String label = mime.startsWith("image/") ? "Photo"
                : mime.startsWith("video/") ? "Video"
                : mime.startsWith("audio/") ? "Audio"
                : m.getFileName();
        return m.getBody().isBlank() ? label : label + ": " + m.getBody();
    }
}
