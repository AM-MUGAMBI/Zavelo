package com.example.zavelo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Render calls this to know the app is up. */
@RestController
public class HealthController {
    @GetMapping("/healthz")
    public String healthz() {
        return "ok";
    }
}
