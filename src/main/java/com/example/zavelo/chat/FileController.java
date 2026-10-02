package com.example.zavelo.chat;

import com.example.zavelo.user.AppUser;
import com.example.zavelo.user.UserRepository;
import com.example.zavelo.user.UserService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sending files up to 10 GB.
 *
 * A file is sent in 8 MB pieces so that a dropped connection only costs one piece:
 *   1. POST   /api/files/init            -> uploadId
 *   2. PUT    /api/files/{id}?offset=N   -> one piece (repeat; safe to retry)
 *   3. POST   /api/files/{id}/complete   -> the message is created and delivered
 * Anyone in the chat can then open or download it with GET /api/files/{messageId}.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    static final long MAX_BYTES = 10L * 1024 * 1024 * 1024;   // 10 GB
    static final int CHUNK_BYTES = 8 * 1024 * 1024;           // 8 MB
    private static final int MAX_PENDING_PER_USER = 5;

    /** Types the browser may show inside the page. Everything else is only ever downloaded. */
    private static final Set<String> INLINE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/avif",
            "video/mp4", "video/webm", "video/ogg", "video/quicktime",
            "audio/mpeg", "audio/ogg", "audio/wav", "audio/webm", "audio/mp4", "audio/aac", "audio/flac",
            "application/pdf");

    private static final Path FINAL_DIR = Paths.get("data", "files");
    private static final Path TMP_DIR = Paths.get("data", "uploads-tmp");

    public record InitRequest(String to, String name, long size, String mime, String caption) {}

    private static class Pending {
        final String id = UUID.randomUUID().toString();
        final Instant created = Instant.now();
        String username, toKey, name, mime, caption;
        long size, received;
        Path tmp;
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final MessageRepository messages;
    private final UserRepository users;
    private final UserService userService;
    private final ChatSocketHandler socket;

    public FileController(MessageRepository messages, UserRepository users,
                          UserService userService, ChatSocketHandler socket) {
        this.messages = messages;
        this.users = users;
        this.userService = userService;
        this.socket = socket;
    }

    /** Unfinished uploads are forgotten when the server restarts, so clear their leftovers. */
    @PostConstruct
    void cleanTemp() throws IOException {
        Files.createDirectories(TMP_DIR);
        Files.createDirectories(FINAL_DIR);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(TMP_DIR, "*.part")) {
            for (Path p : stream) Files.deleteIfExists(p);
        }
    }

    @PostMapping("/init")
    public Map<String, Object> init(@RequestBody InitRequest req, Authentication auth) throws IOException {
        AppUser sender = users.findByUsername(auth.getName()).orElseThrow();
        AppUser recipient = userService.findByKey(req.to());

        if (req.size() <= 0) throw fail(HttpStatus.BAD_REQUEST, "That file is empty.");
        if (req.size() > MAX_BYTES) throw fail(HttpStatus.PAYLOAD_TOO_LARGE, "Files can be up to 10 GB.");
        if (Files.getFileStore(TMP_DIR).getUsableSpace() < req.size())
            throw fail(HttpStatus.INSUFFICIENT_STORAGE, "The server does not have enough free space.");

        purgeStale();
        long mine = pending.values().stream().filter(p -> p.username.equals(sender.getUsername())).count();
        if (mine >= MAX_PENDING_PER_USER) throw fail(HttpStatus.TOO_MANY_REQUESTS, "Too many uploads at once.");

        Pending p = new Pending();
        p.username = sender.getUsername();
        p.toKey = recipient.getConnectKey();
        p.name = safeName(req.name());
        p.mime = safeMime(req.mime());
        p.caption = req.caption() == null ? "" : req.caption().trim();
        if (p.caption.length() > 1000) p.caption = p.caption.substring(0, 1000);
        p.size = req.size();
        p.tmp = TMP_DIR.resolve(p.id + ".part");
        Files.createFile(p.tmp);
        pending.put(p.id, p);
        return Map.of("uploadId", p.id, "chunkSize", CHUNK_BYTES);
    }

    @PutMapping("/{id}")
    public Map<String, Object> chunk(@PathVariable String id, @RequestParam long offset,
                                     HttpServletRequest request, Authentication auth) throws IOException {
        Pending p = owned(id, auth);
        long length = request.getContentLengthLong();
        if (length <= 0 || length > CHUNK_BYTES) throw fail(HttpStatus.PAYLOAD_TOO_LARGE, "Invalid piece size.");

        // Read the whole piece first. If the connection drops, nothing is written, so a retry is clean.
        byte[] data = request.getInputStream().readNBytes((int) length);
        if (data.length != length) throw fail(HttpStatus.BAD_REQUEST, "The piece was cut short.");

        synchronized (p) {
            if (offset != p.received) throw fail(HttpStatus.CONFLICT, "Out of sync.");
            if (p.received + data.length > p.size) throw fail(HttpStatus.BAD_REQUEST, "Too much data.");
            Files.write(p.tmp, data, StandardOpenOption.APPEND);
            p.received += data.length;
            return Map.of("received", p.received);
        }
    }

    /** Lets the browser ask where to continue after a dropped connection. */
    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable String id, Authentication auth) {
        Pending p = owned(id, auth);
        synchronized (p) {
            return Map.of("received", p.received, "size", p.size);
        }
    }

    @PostMapping("/{id}/complete")
    public MessageDto complete(@PathVariable String id, Authentication auth) throws IOException {
        Pending p = owned(id, auth);
        AppUser sender = users.findByUsername(auth.getName()).orElseThrow();
        AppUser recipient = userService.findByKey(p.toKey);

        String stored;
        synchronized (p) {
            if (p.received != p.size) throw fail(HttpStatus.CONFLICT, "The upload is not finished.");
            stored = UUID.randomUUID() + "." + extensionOf(p.name);
            Files.move(p.tmp, FINAL_DIR.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
            pending.remove(p.id);
        }
        Message saved = messages.save(Message.file(sender.getConnectKey(), recipient.getConnectKey(),
                p.caption, p.name, p.mime, p.size, stored));
        socket.deliver(sender, recipient, saved);
        return MessageDto.of(saved);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> cancel(@PathVariable String id, Authentication auth) throws IOException {
        Pending p = owned(id, auth);
        pending.remove(p.id);
        Files.deleteIfExists(p.tmp);
        return Map.of("ok", true);
    }

    /** Open (images, video, audio, PDF) or download a file. Supports Range, so big videos can be skipped through. */
    @GetMapping("/{messageId}")
    public ResponseEntity<Resource> serve(@PathVariable long messageId,
                                          @RequestParam(defaultValue = "false") boolean download,
                                          Authentication auth) {
        String me = users.findByUsername(auth.getName()).orElseThrow().getConnectKey();
        Message m = messages.findById(messageId)
                .filter(Message::isFile)
                .filter(x -> x.getSenderKey().equals(me) || x.getRecipientKey().equals(me))
                .orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "File not found."));

        Path file = FINAL_DIR.resolve(m.getFileStored()).normalize();
        if (!file.startsWith(FINAL_DIR.normalize()) || !Files.exists(file))
            throw fail(HttpStatus.NOT_FOUND, "File not found.");

        boolean inline = !download && INLINE_TYPES.contains(m.getFileMime());
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(m.getFileName(), StandardCharsets.UTF_8).build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(m.getFileMime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(file));
    }

    // ---- helpers ----

    private Pending owned(String id, Authentication auth) {
        Pending p = pending.get(id);
        if (p == null || !p.username.equals(auth.getName())) throw fail(HttpStatus.NOT_FOUND, "Upload not found.");
        return p;
    }

    private void purgeStale() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        pending.values().removeIf(p -> {
            if (p.created.isBefore(cutoff)) {
                try { Files.deleteIfExists(p.tmp); } catch (IOException ignored) { }
                return true;
            }
            return false;
        });
    }

    /** The browser's idea of the type is not trusted: unknown types are stored as plain downloads. */
    private static String safeMime(String mime) {
        String m = mime == null ? "" : mime.split(";")[0].trim().toLowerCase();
        return INLINE_TYPES.contains(m) ? m : "application/octet-stream";
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "file";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase().replaceAll("[^a-z0-9]", "") : "";
        return ext.isEmpty() || ext.length() > 10 ? "bin" : ext;
    }

    private static ResponseStatusException fail(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
