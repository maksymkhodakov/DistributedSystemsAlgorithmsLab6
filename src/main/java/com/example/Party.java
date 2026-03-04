package com.example;

import java.util.HashMap;
import java.util.Map;

/**
 * Party — Represents a participant in the atomic swap.
 *
 * Each party in the system has:
 *   - A name (Alice, Bob, Carol)
 *   - A wallet: a map of currency → balance
 *   - An optional known secret (learned either by generating it or observing the chain)
 *
 * In the 3-party scenario from the lab:
 *
 *   Alice  — starts with ALT-coins. Wants Carol's CAD token.
 *              She is the INITIATOR: she generates the secret S and H(S).
 *
 *   Bob    — starts with BTC. Wants Alice's ALT-coins.
 *              He is the MIDDLE party: he locks BTC after verifying Alice's contract.
 *
 *   Carol  — starts with CAD tokens. Wants Bob's BTC.
 *              She is the FINAL party: she locks CAD after verifying Bob's contract.
 *
 * Swap flow (all contracts use the SAME hashLock H(S)):
 *
 *   Step 1: Alice creates Contract_AB  (ALT locked for Bob,   timelock T1 = longest)
 *   Step 2: Bob   creates Contract_BC  (BTC locked for Carol, timelock T2 < T1)
 *   Step 3: Carol creates Contract_CA  (CAD locked for Alice, timelock T3 < T2)
 *
 *   Step 4: Alice redeems Contract_CA  → reveals S on-chain, receives CAD
 *   Step 5: Carol reads S from chain   → redeems Contract_BC, receives BTC
 *   Step 6: Bob   reads S from chain   → redeems Contract_AB, receives ALT
 *
 * Why descending timelocks?
 *   Alice MUST redeem before T3 expires.
 *   After Alice reveals S, Carol has time (T2 - T3) to use S before T2 expires.
 *   After Carol reveals S on Bob's chain, Bob has time (T1 - T2) to react.
 *   This ensures no rational party can be left empty-handed.
 */
public class Party {

    // ── Identity ─────────────────────────────────────────────────────────────

    /** Human-readable name: "Alice", "Bob", or "Carol". */
    private final String name;

    // ── Wallet ────────────────────────────────────────────────────────────────

    /**
     * Simulated wallet balances.
     * Key   = currency name (e.g. "ALT", "BTC", "CAD")
     * Value = current balance
     *
     * In a real system this would be derived from unspent transaction outputs
     * (UTXOs) on the actual blockchain.
     */
    private final Map<String, Double> wallet;

    // ── Secret knowledge ──────────────────────────────────────────────────────

    /**
     * The secret preimage S known to this party.
     *
     * - Alice sets this when she GENERATES the secret.
     * - Bob and Carol set this when they OBSERVE it being revealed on-chain
     *   (by reading the revealed secret from a redeemed contract).
     * - null means this party does not yet know the secret.
     */
    private String knownSecret;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Create a new party with an empty wallet and no secret knowledge.
     *
     * @param name  Display name of this party
     */
    public Party(String name) {
        this.name        = name;
        this.wallet      = new HashMap<>();
        this.knownSecret = null;
    }

    // ── Wallet operations ─────────────────────────────────────────────────────

    /**
     * Add funds to this party's wallet.
     * Used during system initialisation to give each party their starting balance.
     *
     * @param currency  Token name (e.g. "BTC")
     * @param amount    Amount to add (must be positive)
     */
    public void addFunds(String currency, double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        wallet.merge(currency, amount, Double::sum);
        Logger.info(name + " wallet: +" + amount + " " + currency
                + "  (total: " + getBalance(currency) + " " + currency + ")");
    }

    /**
     * Deduct funds from this party's wallet to "lock" them into an HTLC.
     * Throws if the party has insufficient funds.
     *
     * @param currency  Token name
     * @param amount    Amount to deduct
     * @throws IllegalStateException if balance is insufficient
     */
    public void lockFunds(String currency, double amount) {
        double current = getBalance(currency);
        if (current < amount) {
            throw new IllegalStateException(
                name + " cannot lock " + amount + " " + currency
                + " — only has " + current);
        }
        wallet.put(currency, current - amount);
        Logger.info(name + " locked " + amount + " " + currency
                + " into contract  (remaining: " + getBalance(currency) + " " + currency + ")");
    }

    /**
     * Credit this party's wallet after successfully redeeming a contract.
     *
     * @param currency  Token name
     * @param amount    Amount received from the contract
     */
    public void receiveFunds(String currency, double amount) {
        wallet.merge(currency, amount, Double::sum);
        Logger.success(name + " received " + amount + " " + currency
                + "  (new balance: " + getBalance(currency) + " " + currency + ")");
    }

    /**
     * Returns the current balance for a given currency.
     * Returns 0.0 if the currency has never been held.
     *
     * @param currency  Token name
     * @return current balance, or 0.0 if not present
     */
    public double getBalance(String currency) {
        return wallet.getOrDefault(currency, 0.0);
    }

    // ── Secret management ─────────────────────────────────────────────────────

    /**
     * Record that this party now knows the secret S.
     *
     * Called when:
     *   - Alice generates S herself (she sets it as her own secret)
     *   - Bob/Carol observe S being revealed on-chain after Alice redeems
     *
     * @param secret  The plaintext secret S
     */
    public void learnSecret(String secret) {
        this.knownSecret = secret;
        Logger.info(name + " now knows secret S = " + secret.substring(0, 8) + "...");
    }

    /**
     * Returns whether this party currently knows the secret.
     */
    public boolean knowsSecret() {
        return knownSecret != null;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getName()        { return name; }
    public String getKnownSecret() { return knownSecret; }
    public Map<String, Double> getWallet() { return Map.copyOf(wallet); }

    // ── Display ───────────────────────────────────────────────────────────────

    /**
     * Print a formatted summary of this party's current balances.
     */
    public void printWallet() {
        System.out.println("  " + name + " wallet:");
        if (wallet.isEmpty()) {
            System.out.println("    (empty)");
        } else {
            wallet.forEach((currency, balance) ->
                System.out.printf("    %-6s : %.4f%n", currency, balance));
        }
    }
}
