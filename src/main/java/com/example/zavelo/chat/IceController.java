package com.example.zavelo.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tells the browser which STUN/TURN servers to use for calls. Configured in application.properties. */
@RestController
public class IceController {

    @Value("${zavelo.ice.stun}")
    private String stun;
    @Value("${zavelo.ice.turn.url:}")
    private String turnUrl;
    @Value("${zavelo.ice.turn.username:}")
    private String turnUser;
    @Value("${zavelo.ice.turn.credential:}")
    private String turnCredential;

    @GetMapping("/api/ice")
    public Map<String, Object> ice() {
        List<Map<String, Object>> servers = new ArrayList<>();
        servers.add(Map.of("urls", stun));
        if (!turnUrl.isBlank()) {
            servers.add(Map.of("urls", turnUrl, "username", turnUser, "credential", turnCredential));
        }
        return Map.of("iceServers", servers);
    }
}
