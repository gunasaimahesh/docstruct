package com.docstruct.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 digests, rendered as lowercase hex. Used to identify an uploaded
 * document by its bytes so an identical re-upload can reuse a previous
 * extraction instead of paying for another LLM call.
 */
public final class ContentHash {

    private ContentHash() {
    }

    public static String sha256(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    public static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; its absence is not recoverable.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
