package com.example;

/**
 * SwapResult — Immutable summary of a completed (or failed) atomic swap attempt.
 * Returned by AtomicSwap3Party.executeHappyPath() and
 * AtomicSwap3Party.executeTimeoutPath() to allow the caller to inspect
 * the outcome without parsing log output.
 * Fields:
 *   success       — true if all three contracts were redeemed (happy path)
 *   description   — human-readable explanation of what happened
 *   secretUsed    — the secret S that was (or was not) revealed
 *   hashLock      — H(S) that was embedded in all three contracts
 * In the HAPPY PATH:
 *   success = true, all parties received their desired currency.
 * In the TIMEOUT PATH:
 *   success = false, all contracts expired and were refunded to their senders.
 *   No party loses funds — this is the "atomicity" guarantee.
 */
public record SwapResult(
        boolean success,
        String  description,
        String  secretUsed,
        String  hashLock
) {
    /**
     * Factory method for a successful swap outcome.
     */
    public static SwapResult ok(String secretUsed, String hashLock) {
        return new SwapResult(true,
                "All three contracts redeemed — swap complete",
                secretUsed, hashLock);
    }

    /**
     * Factory method for a failed / timed-out swap outcome.
     *
     * @param reason  Explanation of why the swap failed
     */
    public static SwapResult failed(String reason, String hashLock) {
        return new SwapResult(false, reason, null, hashLock);
    }

    @Override
    public String toString() {
        return "SwapResult{success=" + success
                + ", description='" + description + "'"
                + (secretUsed != null ? ", secret=" + secretUsed.substring(0, 8) + "..." : "")
                + "}";
    }
}
