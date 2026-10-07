package com.example.zavelo.push;

import com.example.zavelo.user.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends notifications to people's phones through the browser's push service (Google, Mozilla, Apple, Microsoft).
 * Sending never slows down chat: it happens on small background threads.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final int MAX_DEVICES_PER_USER = 20;

    private final PushSubscriptionRepository subscriptions;
    private final VapidKeys vapid;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500), r -> {
                Thread t = new Thread(r, "zavelo-push");
                t.setDaemon(true);
                return t;
            });

    public PushService(PushSubscriptionRepository subscriptions, VapidKeys vapid, ObjectMapper json) {
        this.subscriptions = subscriptions;
        this.vapid = vapid;
        this.json = json;
    }

    @PreDestroy
    void stop() { pool.shutdownNow(); }

    // ------------------------------------------------------------------ device sign-up

    public String publicKey() {
        if (!vapid.ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Notifications are not available right now.");
        return vapid.publicKey();
    }

    /**
     * Only the real push services of the big browsers are allowed. Without this check, someone could "subscribe"
     * with an address of their own choosing and make this server send requests wherever they like.
     */
    static boolean allowedEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 700) return false;
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (Exception e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("fcm.googleapis.com") || host.equals("android.googleapis.com")
                || host.endsWith(".push.services.mozilla.com")
                || host.endsWith(".push.apple.com")
                || host.endsWith(".notify.windows.com");
    }

    public void subscribe(AppUser user, String endpoint, String p256dh, String auth) {
        if (!allowedEndpoint(endpoint)) throw bad("This browser's notification service isn't supported.");
        try {
            byte[] key = WebPushCrypto.unb64(p256dh);
            byte[] secret = WebPushCrypto.unb64(auth);
            WebPushCrypto.decodePublic(key);
            if (secret.length != 16) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw bad("Invalid notification details.");
        }
        PushSubscription existing = subscriptions.findByEndpoint(endpoint).orElse(null);
        if (existing != null) {
            // Same phone: it now belongs to whoever is signed in on it.
            existing.setUserId(user.getId());
            existing.setP256dh(p256dh);
            existing.setAuth(auth);
            subscriptions.save(existing);
            return;
        }
        if (subscriptions.countByUserId(user.getId()) >= MAX_DEVICES_PER_USER) {
            throw bad("Too many devices have notifications turned on.");
        }
        subscriptions.save(new PushSubscription(user.getId(), endpoint, p256dh, auth));
    }

    public void unsubscribe(AppUser user, String endpoint) {
        if (endpoint != null) subscriptions.deleteByEndpointAndUserId(endpoint, user.getId());
    }

    public boolean hasDevice(AppUser user) { return subscriptions.existsByUserId(user.getId()); }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    // ------------------------------------------------------------------ what we send

    public void notifyMessage(AppUser to, AppUser from, String preview) {
        String text = preview == null ? "" : preview.strip();
        if (text.length() > 140) text = text.substring(0, 139) + "…";
        send(to, payload("message", from.getDisplayName(), text, from, null, false), 86_400, "normal");
    }

    public void notifyCall(AppUser to, AppUser from, String callType) {
        boolean video = "video".equals(callType);
        send(to, payload("call", from.getDisplayName(), video ? "Incoming video call" : "Incoming voice call",
                from, video ? "video" : "audio", false), 45, "high");
    }


    /** The caller hung up before it was answered. */
    public void notifyCallEnded(AppUser to, AppUser from) {
        send(to, payload("call-end", from.getDisplayName(), "Missed call", from, null, false), 86_400, "normal");
    }

    public void notifyTest(AppUser user) {
        send(user, payload("message", "Zavelo", "Notifications are working on this device.", user, null, true), 300, "high");
    }

    private Map<String, Object> payload(String type, String title, String body, AppUser from, String callType, boolean test) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("title", title);
        p.put("body", body);
        p.put("from", from.getConnectKey());
        if (callType != null) p.put("callType", callType);
        if (test) p.put("test", true);
        return p;
    }

    private void send(AppUser to, Map<String, Object> payload, int ttlSeconds, String urgency) {
        if (!vapid.ready()) return;
        Long userId = to.getId();
        try {
            pool.execute(() -> {
                try {
                    byte[] data = json.writeValueAsBytes(payload);
                    List<PushSubscription> devices = subscriptions.findByUserId(userId);
                    for (PushSubscription device : devices) sendOne(device, data, ttlSeconds, urgency);
                } catch (Exception e) {
                    log.warn("Notification failed: {}", e.toString());
                }
            });
        } catch (RejectedExecutionException busy) {
            log.warn("Notification queue is full; skipped one.");
        }
    }

    private void sendOne(PushSubscription device, byte[] data, int ttlSeconds, String urgency) {
        try {
            if (!allowedEndpoint(device.getEndpoint())) {
                subscriptions.deleteByEndpoint(device.getEndpoint());
                return;
            }
            URI uri = URI.create(device.getEndpoint());
            String audience = uri.getScheme() + "://" + uri.getHost();
            byte[] body = WebPushCrypto.encrypt(data, WebPushCrypto.unb64(device.getP256dh()), WebPushCrypto.unb64(device.getAuth()));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", vapid.authorization(audience))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", String.valueOf(ttlSeconds))
                    .header("Urgency", urgency)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 404 || status == 410) {
                subscriptions.deleteByEndpoint(device.getEndpoint());   // the phone removed the app or turned it off
            } else if (status >= 300) {
                log.warn("Push service answered {} for one device", status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not notify one device: {}", e.toString());
        }
    }
}
