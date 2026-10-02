package com.example.zavelo.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The 9-digit number other people use to reach this user. */
    @Column(nullable = false, unique = true, length = 9)
    private String connectKey;

    @Column(nullable = false, unique = true, length = 30)
    private String username;

    @Column(nullable = false, length = 40)
    private String displayName;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {}

    public AppUser(String connectKey, String username, String displayName, String passwordHash) {
        this.connectKey = connectKey;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getConnectKey() { return connectKey; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
}
