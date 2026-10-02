package com.example.zavelo.chat;

import com.example.zavelo.user.AppUser;
import com.example.zavelo.user.UserRepository;
import com.example.zavelo.user.UserService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    /** Only these audio types are accepted. Chrome/Firefox record webm or ogg, Safari records mp4. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "audio/webm", "webm", "audio/ogg", "ogg", "audio/mp4", "m4a",
            "audio/mpeg", "mp3", "audio/wav", "wav");

    private final Path dir = Paths.get("data", "voice");
    private final MessageRepository messages;
    private final UserRepository users;
    private final UserService userService;
    private final ChatSocketHandler socket;

    public VoiceController(MessageRepository messages, UserRepository users,
                           UserService userService, ChatSocketHandler socket) {
        this.messages = messages;
        this.users = users;
        this.userService = userService;
        this.socket = socket;
    }

    @PostMapping
    public MessageDto upload(@RequestParam("file") MultipartFile file,
                             @RequestParam("to") String to,
                             @RequestParam(value = "duration", defaultValue = "0") int duration,
                             Authentication auth) throws IOException {
        AppUser sender = users.findByUsername(auth.getName()).orElseThrow();
        AppUser recipient = userService.findByKey(to);

        String contentType = file.getContentType() == null ? "" : file.getContentType();
        String mime = contentType.split(";")[0].trim().toLowerCase();
        String ext = EXTENSIONS.get(mime);
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The recording is empty.");
        if (ext == null) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported audio format.");

        Files.createDirectories(dir);
        String name = UUID.randomUUID() + "." + ext;     // we pick the name; the client never does
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }

        int seconds = Math.max(0, Math.min(duration, 600));
        Message saved = messages.save(
                Message.voice(sender.getConnectKey(), recipient.getConnectKey(), name, mime, seconds));
        socket.deliver(sender, recipient, saved);
        return MessageDto.of(saved);
    }

    /** Only the sender and the recipient can play a voice note. Supports Range requests. */
    @GetMapping("/{id}")
    public ResponseEntity<Resource> play(@PathVariable long id, Authentication auth) {
        String me = users.findByUsername(auth.getName()).orElseThrow().getConnectKey();
        Message m = messages.findById(id)
                .filter(Message::isAudio)
                .filter(x -> x.getSenderKey().equals(me) || x.getRecipientKey().equals(me))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice note not found."));

        Path file = dir.resolve(m.getAudioFile()).normalize();
        if (!file.startsWith(dir.normalize()) || !Files.exists(file))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice note not found.");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(m.getAudioMime()))
                .body(new FileSystemResource(file));
    }
}
