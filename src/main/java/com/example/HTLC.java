package com.example;

import java.time.Instant;

/**
 * HTLC — Hash Time-Locked Contract
 *
 * This is the core building block of an atomic swap. It models a smart contract
 * that holds funds and releases them under exactly ONE of two conditions:
 *
 *   (1) REDEEM  — the recipient presents the secret S such that SHA-256(S) == hashLock
 *                 within the time deadline (timelock). The funds go to the recipient.
 *
 *   (2) REFUND  — the deadline passes without redemption. The funds return to the sender.
 *
 * In a 3-party swap we create THREE such contracts, all sharing the SAME hashLock.
 * This guarantees atomicity: revealing the secret claims all contracts or none.
 *
 * Field naming convention:
 *   sender      — party who deposits funds into this contract
 *   recipient   — party who can unlock funds by providing secret S
 *   amount      — how much of `currency` is locked
 *   currency    — which blockchain / token this contract lives on
 *   hashLock    — H(S) = SHA-256(secret).  Stored as hex string.
 *   timeLockSec — seconds from creation after which a refund is possible
 */
public class HTLC {

    // ── Possible states a contract can be in ─────────────────────────────────
    public enum State {
        /** Contract created, funds locked, waiting for action. */
        PENDING,
        /** Recipient successfully provided secret S — funds transferred. */
        REDEEMED,
        /** Timelock expired before secret was provided — funds returned to sender. */
        REFUNDED
    }

    // ── Contract identity & parties ──────────────────────────────────────────

    /** Human-readable name, e.g. "Contract_Alice→Bob". Used only for logging. */
    private final String name;

    /** The party who created this contract and deposited the funds. */
    private final String senderName;

    /** The party who can redeem this contract by revealing the preimage S. */
    private final String recipientName;

    // ── Financial terms ──────────────────────────────────────────────────────

    /** Amount of currency locked in this contract. */
    private final double amount;

    /** Name of the currency / chain this contract exists on (e.g. "BTC", "ALT"). */
    private final String currency;

    // ── Cryptographic lock ───────────────────────────────────────────────────

    /**
     * The hash of the secret: H(S) = SHA-256(secret).
     * This is public — anyone on the blockchain can see it.
     * But only the secret holder can produce the preimage S that satisfies it.
     */
    private final String hashLock;

    // ── Time lock ────────────────────────────────────────────────────────────

    /** Unix timestamp (seconds) at which this contract was created. */
    private final long createdAt;

    /**
     * How many seconds after creation the sender may request a refund.
     * In the 3-party protocol:
     *   T1 (Alice→Bob)   = longest  (e.g. 30s in demo, hours/days in production)
     *   T2 (Bob→Carol)   = shorter  (e.g. 20s)
     *   T3 (Carol→Alice) = shortest (e.g. 10s)
     *
     * Why descending? Alice must claim Carol's contract BEFORE Carol's T3 expires.
     * Once Alice reveals S on Carol's chain, Carol uses S on Bob's chain, etc.
     * Each downstream party always has enough time to react.
     */
    private final long timeLockSec;

    // ── Mutable state ────────────────────────────────────────────────────────

    /** Current state of this contract. Starts as PENDING. */
    private State state;

    /**
     * The secret preimage S, revealed only after redemption.
     * null while PENDING, populated on successful redeem().
     * Once set, anyone watching the blockchain can read it and use it
     * to redeem their own contract downstream.
     */
    private String revealedSecret;

    // ── Constructor ──────────────────────────────────────────────────────────

    /**
     * Creates a new HTLC contract and "locks" the funds.
     *
     * @param name         Display name for logging
     * @param senderName   Name of the party depositing funds
     * @param recipientName Name of the party who can redeem
     * @param amount       Amount to lock
     * @param currency     Token / chain name
     * @param hashLock     SHA-256 hash of the secret (hex string)
     * @param timeLockSec  Seconds until sender can reclaim funds
     */
    public HTLC(String name, String senderName, String recipientName,
                double amount, String currency,
                String hashLock, long timeLockSec) {
        this.name          = name;
        this.senderName    = senderName;
        this.recipientName = recipientName;
        this.amount        = amount;
        this.currency      = currency;
        this.hashLock      = hashLock;
        this.timeLockSec   = timeLockSec;
        this.createdAt     = Instant.now().getEpochSecond();
        this.state         = State.PENDING;
        this.revealedSecret = null;
    }

    // ── Core operations ──────────────────────────────────────────────────────

    /**
     * Attempt to redeem the contract by presenting secret S.
     *
     * Steps performed:
     *   1. Verify the contract is still PENDING.
     *   2. Verify the timelock has NOT yet expired.
     *   3. Hash the provided secret and compare to hashLock.
     *   4. If all checks pass → mark REDEEMED, store revealed secret.
     *
     * The revealed secret is now "on the blockchain" — all other parties
     * can read it via getRevealedSecret() to redeem their own contracts.
     *
     * @param secret  The preimage S (plaintext secret)
     * @param caller  Name of the party attempting redemption (for logging)
     * @return true if redemption succeeded
     */
    public boolean redeem(String secret, String caller) {
        // ── Guard: wrong state ───────────────────────────────────────────
        if (state != State.PENDING) {
            Logger.warn(name + ": redeem() called but contract is already " + state);
            return false;
        }

        // ── Guard: timelock expired ──────────────────────────────────────
        if (isExpired()) {
            Logger.warn(name + ": timelock expired — cannot redeem");
            return false;
        }

        // ── Guard: hash mismatch ─────────────────────────────────────────
        String computedHash = CryptoUtils.sha256Hex(secret);
        if (!computedHash.equals(hashLock)) {
            Logger.warn(name + ": " + caller + " provided WRONG secret (hash mismatch)");
            return false;
        }

        // ── All checks passed: transfer funds ────────────────────────────
        state          = State.REDEEMED;
        revealedSecret = secret;   // now visible to all blockchain observers

        Logger.success(name + ": REDEEMED by " + caller
                + "  |  " + amount + " " + currency + " → " + recipientName
                + "  |  secret revealed on-chain ✓");
        return true;
    }

    /**
     * Attempt to refund the contract back to the sender.
     *
     * Can only succeed when:
     *   - The contract is still PENDING (not already redeemed or refunded)
     *   - The timelock HAS expired
     *
     * @param caller  Name of the party requesting refund (should be sender)
     * @return true if refund succeeded
     */
    public boolean refund(String caller) {
        // ── Guard: wrong state ───────────────────────────────────────────
        if (state != State.PENDING) {
            Logger.warn(name + ": refund() called but contract is already " + state);
            return false;
        }

        // ── Guard: timelock not yet expired ──────────────────────────────
        if (!isExpired()) {
            long remaining = getRemainingSeconds();
            Logger.warn(name + ": " + caller
                    + " tried to refund early — " + remaining + "s remaining");
            return false;
        }

        // ── Timelock expired: return funds to sender ──────────────────────
        state = State.REFUNDED;
        Logger.warn(name + ": REFUNDED to " + senderName
                + "  |  " + amount + " " + currency + " returned");
        return true;
    }

    // ── Time helpers ─────────────────────────────────────────────────────────

    /**
     * Returns true if the current time has passed the timelock deadline.
     * deadline = createdAt + timeLockSec
     */
    public boolean isExpired() {
        return Instant.now().getEpochSecond() >= (createdAt + timeLockSec);
    }

    /** Returns how many seconds remain until the timelock expires (0 if already expired). */
    public long getRemainingSeconds() {
        long remaining = (createdAt + timeLockSec) - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String  getName()            { return name; }
    public String  getSenderName()      { return senderName; }
    public String  getRecipientName()   { return recipientName; }
    public double  getAmount()          { return amount; }
    public String  getCurrency()        { return currency; }
    public String  getHashLock()        { return hashLock; }
    public long    getTimeLockSec()     { return timeLockSec; }
    public State   getState()           { return state; }

    /**
     * Returns the secret preimage S if this contract has been redeemed,
     * or null if still PENDING / REFUNDED.
     *
     * In a real blockchain this is simply reading the transaction data
     * from the chain — anyone can observe it.
     */
    public String getRevealedSecret()   { return revealedSecret; }

    // ── Display ──────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return String.format(
            "HTLC[%s | %s→%s | %.4f %s | lock=%s... | T=%ds | %s]",
            name, senderName, recipientName,
            amount, currency,
            hashLock.substring(0, 8),
            timeLockSec,
            state
        );
    }
}
