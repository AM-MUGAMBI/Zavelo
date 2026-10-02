package com.example.zavelo.auth;

import com.example.zavelo.user.AppUser;
import com.example.zavelo.user.UserRepository;
import com.example.zavelo.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    public record RegisterRequest(String username, String displayName, String password) {}
    public record LoginRequest(String username, String password) {}

    private final UserService userService;
    private final UserRepository users;
    private final AuthenticationManager authManager;

    public AuthController(UserService userService, UserRepository users, AuthenticationManager authManager) {
        this.userService = userService;
        this.users = users;
        this.authManager = authManager;
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody RegisterRequest req,
                                        HttpServletRequest request, HttpServletResponse response) {
        AppUser user = userService.register(req.username(), req.displayName(), req.password());
        startSession(user.getUsername(), req.password(), request, response);
        return profile(user);
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest req,
                                     HttpServletRequest request, HttpServletResponse response) {
        String username = req.username() == null ? "" : req.username().trim().toLowerCase();
        try {
            startSession(username, req.password() == null ? "" : req.password(), request, response);
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password.");
        }
        return profile(users.findByUsername(username).orElseThrow());
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request) throws Exception {
        request.logout();
        SecurityContextHolder.clearContext();
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        return profile(users.findByUsername(auth.getName()).orElseThrow());
    }

    /** Look someone up by their key. Returns only their name and key. */
    @GetMapping("/users/by-key/{key}")
    public Map<String, Object> byKey(@PathVariable String key) {
        return profile(userService.findByKey(key));
    }

    /** Search by key or username. Returns only the name, key and username of an exact match. */
    @GetMapping("/users/search")
    public Map<String, Object> search(@RequestParam String q) {
        AppUser u = userService.findByKeyOrUsername(q);
        return Map.of("displayName", u.getDisplayName(), "key", u.getConnectKey(), "username", u.getUsername());
    }

    private void startSession(String username, String password,
                              HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        new HttpSessionSecurityContextRepository().saveContext(ctx, request, response);
    }

    private Map<String, Object> profile(AppUser u) {
        return Map.of("displayName", u.getDisplayName(), "key", u.getConnectKey());
    }
}
