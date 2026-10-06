package com.example.zavelo.push;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Optional;

/**
 * The server's notification identity. Made once, on first start, and kept in the database so it
 * survives restarts and redeploys. (If the database is ever wiped, new keys are made and phones
 * quietly sign up again the next time they open Zavelo.)
 */
@Component
public class VapidKeys {

    private static final String PRIVATE = "vapid.private";
    private static final String PUBLIC = "vapid.public";

    private final AppSettingRepository settings;
    private final String subject;
    private ECPrivateKey privateKey;
    private String publicKeyB64;

    public VapidKeys(AppSettingRepository settings,
                     @Value("${zavelo.push.subject:mailto:admin@zavelo.app}") String subject) {
        this.settings = settings;
        this.subject = subject;
    }

    @PostConstruct
    void load() {
        Optional<AppSetting> priv = settings.findById(PRIVATE);
        Optional<AppSetting> pub = settings.findById(PUBLIC);
        if (priv.isPresent() && pub.isPresent()) {
            try {
                privateKey = WebPushCrypto.decodePrivate(WebPushCrypto.unb64(priv.get().getValue()));
                publicKeyB64 = pub.get().getValue();
                return;
            } catch (RuntimeException broken) {
                // unreadable keys: fall through and make fresh ones
            }
        }
        KeyPair pair = WebPushCrypto.generateKeyPair();
        privateKey = (ECPrivateKey) pair.getPrivate();
        publicKeyB64 = WebPushCrypto.b64(WebPushCrypto.encodePublic((ECPublicKey) pair.getPublic()));
        settings.save(new AppSetting(PRIVATE, WebPushCrypto.b64(WebPushCrypto.encodePrivate(privateKey))));
        settings.save(new AppSetting(PUBLIC, publicKeyB64));
    }

    /** What the phone needs to subscribe (the "applicationServerKey"). */
    public String publicKey() { return publicKeyB64; }

    /** Value for the Authorization header of a push request to the given service. */
    public String authorization(String audience) {
        String jwt = WebPushCrypto.vapidJwt(privateKey, audience, subject, Instant.now().getEpochSecond() + 12 * 3600);
        return "vapid t=" + jwt + ", k=" + publicKeyB64;
    }
}
