package com.example;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * CryptoUtils — Cryptographic utility methods for the atomic swap system.
 * Key operations:
 *   generateSecret()  — creates a cryptographically random 32-byte secret S
 *   sha256Hex()       — computes SHA-256(input) and returns the result as hex
 */
public final class CryptoUtils {

    /** Prevent instantiation — this is a static utility class. */
    private CryptoUtils() {}

    // ── Secure random source ─────────────────────────────────────────────────

    /**
     * SecureRandom is used instead of Random because the secret S is a
     * cryptographic value. If S were predictable, an attacker could compute
     * H(S) themselves and pre-emptively drain contracts.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Generate a cryptographically random secret S.
     * The secret is 32 bytes (256 bits) of random data, encoded as a 64-char
     * hex string. 256 bits of entropy makes brute-force guessing infeasible.
     * In the 3-party protocol, ONLY Alice generates the secret. She keeps it
     * private until she is ready to redeem Carol's contract. At that point she
     * reveals it on-chain and the other parties can observe and use it.
     *
     * @return 64-character lowercase hex string representing 32 random bytes
     */
    public static String generateSecret() {
        byte[] bytes = new byte[32];       // 32 bytes = 256 bits of randomness
        SECURE_RANDOM.nextBytes(bytes);    // fill with secure random data
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Compute SHA-256 hash of a UTF-8 string and return the result as hex.
     * This is used to:
     *   (a) Compute H(S) from secret S when creating contracts (hashLock)
     *   (b) Verify a candidate secret during redemption attempt
     * SHA-256 guarantees:
     *   - Deterministic: same input always produces same output
     *   - Pre-image resistant: can't find S given H(S)
     *   - Collision resistant: can't find two different inputs with same hash
     *
     * @param input  The string to hash (e.g. the secret S)
     * @return       64-character lowercase hex digest
     * @throws RuntimeException if SHA-256 algorithm is unexpectedly unavailable
     */
    public static String sha256Hex(String input) {
        try {
            // Get a SHA-256 MessageDigest instance from Java's built-in provider
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Hash the UTF-8 bytes of the input string
            byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Convert raw bytes to lowercase hexadecimal string
            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the Java spec — this should never happen
            throw new IllegalArgumentException("SHA-256 not available — JVM is non-compliant", e);
        }
    }
}
