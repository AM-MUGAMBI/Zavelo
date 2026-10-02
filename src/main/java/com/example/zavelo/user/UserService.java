package com.example.zavelo.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class UserService {

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,30}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public AppUser register(String username, String displayName, String password) {
        username = username == null ? "" : username.trim().toLowerCase();
        displayName = displayName == null ? "" : displayName.trim();

        if (!USERNAME.matcher(username).matches())
            throw bad("Username must be 3-30 letters, numbers or underscores.");
        if (displayName.isEmpty() || displayName.length() > 40)
            throw bad("Enter a name up to 40 characters.");
        if (password == null || password.length() < 8)
            throw bad("Password must be at least 8 characters.");
        if (users.existsByUsername(username))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is taken.");

        AppUser user = new AppUser(generateKey(), username, displayName, encoder.encode(password));
        return users.save(user);
    }

    public AppUser findByKey(String key) {
        String digits = key == null ? "" : key.replaceAll("\\D", "");
        return users.findByConnectKey(digits)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No one has that key."));
    }

    /**
     * Finds a person by their 9-digit key or by their username (with or without a leading @).
     * Exact matches only, so the list of users can't be browsed or guessed from partial names.
     */
    public AppUser findByKeyOrUsername(String query) {
        String q = query == null ? "" : query.trim();
        if (q.startsWith("@")) q = q.substring(1);

        String digits = q.replaceAll("\\s", "");
        if (digits.matches("\\d{9}")) {
            Optional<AppUser> byKey = users.findByConnectKey(digits);
            if (byKey.isPresent()) return byKey.get();
        }
        return users.findByUsername(q.toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No one found with that key or username."));
    }

    /** 9 random digits, never starting with 0, guaranteed unused. */
    private String generateKey() {
        for (int i = 0; i < 20; i++) {
            String key = String.valueOf(100_000_000 + RANDOM.nextInt(900_000_000));
            if (!users.existsByConnectKey(key)) return key;
        }
        throw new IllegalStateException("Could not generate a unique key");
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
