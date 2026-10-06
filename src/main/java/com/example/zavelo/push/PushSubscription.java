package com.example.zavelo.push;

import jakarta.persistence.*;
import java.time.Instant;

/** One phone/browser that asked to receive notifications for one user. */
@Entity
@Table(name = "push_subscriptions", indexes = @Index(name = "idx_push_user", columnList = "userId"))
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 700)
    private String endpoint;

    @Column(nullable = false, length = 200)
    private String p256dh;

    @Column(nullable = false, length = 100)
    private String auth;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected PushSubscription() {}

    public PushSubscription(Long userId, String endpoint, String p256dh, String auth) {
        this.userId = userId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getEndpoint() { return endpoint; }
    public String getP256dh() { return p256dh; }
    public String getAuth() { return auth; }

    public void setUserId(Long userId) { this.userId = userId; }
    public void setP256dh(String p256dh) { this.p256dh = p256dh; }
    public void setAuth(String auth) { this.auth = auth; }
}
