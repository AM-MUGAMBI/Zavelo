package com.example.zavelo.push;

import com.example.zavelo.user.UserRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/push")
public class PushController {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keys(String p256dh, String auth) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubscribeRequest(String endpoint, Keys keys) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UnsubscribeRequest(String endpoint) {}

    private final PushService push;
    private final UserRepository users;

    public PushController(PushService push, UserRepository users) {
        this.push = push;
        this.users = users;
    }

    @GetMapping("/key")
    public Map<String, String> key() {
        return Map.of("key", push.publicKey());
    }

    @PostMapping("/subscribe")
    public Map<String, Object> subscribe(@RequestBody SubscribeRequest req, Authentication auth) {
        if (req == null || req.keys() == null || req.endpoint() == null
                || req.keys().p256dh() == null || req.keys().auth() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification details.");
        }
        push.subscribe(users.findByUsername(auth.getName()).orElseThrow(),
                req.endpoint(), req.keys().p256dh(), req.keys().auth());
        return Map.of("ok", true);
    }

    @PostMapping("/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestBody UnsubscribeRequest req, Authentication auth) {
        push.unsubscribe(users.findByUsername(auth.getName()).orElseThrow(), req == null ? null : req.endpoint());
        return Map.of("ok", true);
    }

    @PostMapping("/test")
    public Map<String, Object> test(Authentication auth) {
        var me = users.findByUsername(auth.getName()).orElseThrow();
        if (!push.hasDevice(me)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Turn notifications on first.");
        push.notifyTest(me);
        return Map.of("ok", true);
    }
}
