package com.example.zavelo.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The cryptography Web Push needs, using only what ships with Java (no extra libraries):
 *  - RFC 8291 message encryption (aes128gcm), so only the user's phone can read a notification;
 *  - RFC 8292 VAPID signing, so the push service knows the notification comes from this server.
 * Everything here is pure (no Spring, no database) so it can be tested on its own.
 */
public final class WebPushCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final int RECORD_SIZE = 4096;
    /** Largest message we will encrypt; push services accept 4096 bytes in total. */
    public static final int MAX_PLAINTEXT = RECORD_SIZE - 86 - 17;

    private WebPushCrypto() {}

    public static String b64(byte[] bytes) { return B64.encodeToString(bytes); }

    public static byte[] unb64(String text) {
        // accept both url-safe and standard alphabets, with or without padding
        String t = text.trim().replace('+', '-').replace('/', '_');
        int end = t.length();
        while (end > 0 && t.charAt(end - 1) == '=') end--;
        return B64D.decode(t.substring(0, end));
    }

    // ---------------------------------------------------------------- keys

    private static ECParameterSpec curve() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Public key as the 65 bytes browsers use: 0x04 | X | Y. */
    public static byte[] encodePublic(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(fixed32(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed32(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    public static byte[] encodePrivate(ECPrivateKey key) { return fixed32(key.getS()); }

    public static ECPublicKey decodePublic(byte[] raw) {
        if (raw == null || raw.length != 65 || raw[0] != 4) throw new IllegalArgumentException("Bad public key");
        try {
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
            // the point is checked to be on the curve when the key is used for agreement
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curve()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad public key", e);
        }
    }

    public static ECPrivateKey decodePrivate(byte[] raw) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), curve()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad private key", e);
        }
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fixed32(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    // ---------------------------------------------------------------- RFC 8291 encryption

    /** Encrypts a payload for one phone. Returns the full request body (header + one encrypted record). */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generateKeyPair(), salt);
    }

    /** Same, with the server's one-off key and salt supplied (used by the standard test vector). */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) throw new IllegalArgumentException("Message too large");
        if (authSecret == null || authSecret.length != 16) throw new IllegalArgumentException("Bad auth secret");
        try {
            byte[] asPublic = encodePublic((ECPublicKey) asKeys.getPublic());

            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(asKeys.getPrivate());
            ka.doPhase(decodePublic(uaPublic), true);
            byte[] ecdh = ka.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdh);
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[]{1}));

            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);

            byte[] padded = concat(plaintext, new byte[]{2});   // 0x02 marks the last (only) record
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] cipher = c.doFinal(padded);

            ByteBuffer out = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + cipher.length);
            out.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(cipher);
            return out.array();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt notification", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, i, p.length); i += p.length; }
        return out;
    }

    // ---------------------------------------------------------------- RFC 8292 VAPID

    /** Signed token proving the notification comes from this server. */
    public static String vapidJwt(ECPrivateKey key, String audience, String subject, long expiresAtEpochSeconds) {
        try {
            String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
            String claims = b64(("{\"aud\":\"" + jsonEscape(audience) + "\",\"exp\":" + expiresAtEpochSeconds
                    + ",\"sub\":\"" + jsonEscape(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + claims;
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign((PrivateKey) key);
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign notification", e);
        }
    }

    /** Check a token (used by tests). */
    public static boolean verifyJwt(String jwt, PublicKey key) {
        try {
            int dot = jwt.lastIndexOf('.');
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(key);
            s.update(jwt.substring(0, dot).getBytes(StandardCharsets.US_ASCII));
            return s.verify(B64D.decode(jwt.substring(dot + 1)));
        } catch (Exception e) {
            return false;
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(' ');
            else b.append(ch);
        }
        return b.toString();
    }
}
