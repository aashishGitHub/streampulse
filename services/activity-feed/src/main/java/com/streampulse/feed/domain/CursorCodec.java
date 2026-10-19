/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An opaque, signed token. The signature is integrity, not secrecy: the contents are readable
 * but cannot be altered, and are bound to the user they were issued to.
 *
 * <pre>token = base64url( version(1) | asOf(8) | scoreBits(8) | entryId(UTF) | HMAC-SHA256(userId | those bytes) )</pre>
 */
public final class CursorCodec {

    private static final byte VERSION = 1;
    private static final int MAC_BYTES = 32;
    private static final int MAX_TOKEN_CHARS = 512;
    private static final String HMAC = "HmacSHA256";

    private final byte[] secret;

    public CursorCodec(byte[] secret) {
        if (secret.length < 16) {
            throw new IllegalArgumentException("the cursor signing secret must be at least 16 bytes");
        }
        this.secret = secret.clone();
    }

    public String encode(UserId user, Cursor cursor) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeLong(cursor.asOfMillis());
            out.writeLong(Double.doubleToLongBits(cursor.score()));
            out.writeUTF(cursor.entryId());
            byte[] payload = bytes.toByteArray();
            bytes.write(sign(user, payload));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Cursor decode(UserId user, String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            throw invalid();
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (raw.length <= MAC_BYTES) {
            throw invalid();
        }
        byte[] payload = Arrays.copyOfRange(raw, 0, raw.length - MAC_BYTES);
        byte[] mac = Arrays.copyOfRange(raw, raw.length - MAC_BYTES, raw.length);
        // Constant time: a byte-by-byte comparison tells an attacker how many leading bytes matched.
        if (!MessageDigest.isEqual(mac, sign(user, payload))) {
            throw invalid();
        }
        try {
            var in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readByte() != VERSION) {
                throw invalid();
            }
            long asOf = in.readLong();
            double score = Double.longBitsToDouble(in.readLong());
            String entryId = in.readUTF();
            if (in.available() != 0 || !Double.isFinite(score)) {
                throw invalid();
            }
            return new Cursor(asOf, score, entryId);
        } catch (IOException e) {
            throw invalid();
        }
    }

    private byte[] sign(UserId user, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            mac.update(user.value().getBytes(StandardCharsets.UTF_8));
            // A separator no id can contain, so ("ab","c...") and ("a","bc...") cannot sign alike.
            mac.update((byte) 0);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static InvalidCursorException invalid() {
        return new InvalidCursorException("the cursor is not valid for this request");
    }
}
