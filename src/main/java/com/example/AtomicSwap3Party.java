package com.example;

/**
 * AtomicSwap3Party — Generic 3-party atomic swap engine using HTLC contracts.
 * Implements a ring swap where three parties each offer one currency and
 * receive a different one, with atomicity guaranteed by a shared hash-lock:
 *   legA.party  offers legA.currency  ──▶  legB.party
 *   legB.party  offers legB.currency  ──▶  legC.party
 *   legC.party  offers legC.currency  ──▶  legA.party
 * Three HTLC contracts are deployed, all sharing the same hashLock H(S).
 * Either every contract is redeemed (all parties get what they want) or
 * every contract times out and is refunded (nobody loses anything).
 * Timelocks must satisfy T1 > T2 > T3 so that each downstream party
 * always has a window to react after the secret is revealed on-chain.
 * Usage example:
 *   AtomicSwap3Party swap = new AtomicSwap3Party(
 *       new SwapLeg("Alice", "ALT", 100.0),
 *       new SwapLeg("Bob",   "BTC",   0.05),
 *       new SwapLeg("Carol", "CAD",   1.0),
 *       30, 20, 10
 *   );
 *   SwapResult result = swap.executeHappyPath();
 */
public class AtomicSwap3Party {
    public static final String FOR = " for ";
    public static final String RECLAIMS = " reclaims ";
    public static final String LOCKS = " locks ";
    public static final String WAITING = "Waiting ";
    public static final String PRINT_STR = "  %-8s: gave %8.4f %-4s  →  received %8.4f %s  ✓%n";

    // ── Swap configuration (immutable, set at construction time) ─────────────

    /** Initiator: generates the secret, offers legA.currency to legB.party. */
    private final SwapLeg legA;

    /** Middle party: offers legB.currency to legC.party. */
    private final SwapLeg legB;

    /** Final party: offers legC.currency back to legA.party. */
    private final SwapLeg legC;

    /** Timelock for contract A→B (longest). Seconds. */
    private final long t1;

    /** Timelock for contract B→C (middle). Seconds. */
    private final long t2;

    /** Timelock for contract C→A (shortest). Seconds. */
    private final long t3;

    // ── Runtime state (populated during execution) ────────────────────────────

    private Party partyA;
    private Party partyB;
    private Party partyC;
    private HTLC  contractAB;
    private HTLC contractBC;
    private HTLC contractCA;
    private String secret;
    private String hashLock;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Create a new 3-party atomic swap.
     *
     * @param legA  Initiator leg (generates secret, offers currency to legB party)
     * @param legB  Middle leg
     * @param legC  Final leg (offers currency back to legA party)
     * @param t1    Timelock seconds for contract A→B (must be longest)
     * @param t2    Timelock seconds for contract B→C (must satisfy T1 > T2)
     * @param t3    Timelock seconds for contract C→A (must satisfy T2 > T3)
     */
    public AtomicSwap3Party(SwapLeg legA, SwapLeg legB, SwapLeg legC,
                             long t1, long t2, long t3) {
        if (t1 <= t2 || t2 <= t3)
            throw new IllegalArgumentException(
                "Timelocks must satisfy T1 > T2 > T3, got T1=" + t1 + " T2=" + t2 + " T3=" + t3);
        this.legA = legA; this.legB = legB; this.legC = legC;
        this.t1 = t1;     this.t2 = t2;    this.t3 = t3;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INITIALISATION
    // ═════════════════════════════════════════════════════════════════════════

    private void initParties() {
        Logger.header("INITIALISATION — Creating parties & wallets");
        partyA = new Party(legA.partyName());
        partyB = new Party(legB.partyName());
        partyC = new Party(legC.partyName());
        partyA.addFunds(legA.currency(), legA.amount());
        partyB.addFunds(legB.currency(), legB.amount());
        partyC.addFunds(legC.currency(), legC.amount());
        printAllWallets("Initial balances");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 1 — SETUP: ALL PARTIES LOCK THEIR FUNDS
    // ═════════════════════════════════════════════════════════════════════════

    private void phase1Setup() {

        // Step 1 — Initiator (A) generates secret S and computes H(S)
        Logger.header("STEP 1 — " + legA.partyName() + " generates secret S");
        secret   = CryptoUtils.generateSecret();
        hashLock = CryptoUtils.sha256Hex(secret);
        partyA.learnSecret(secret);
        Logger.info("H(S) = " + hashLock.substring(0, 16) + "...  (embedded in all 3 contracts)");

        // Step 2 — A locks funds for B  (T1 = longest timelock)
        Logger.header("STEP 2 — " + legA.partyName() + LOCKS
                + legA.amount() + " " + legA.currency()
                + FOR + legB.partyName() + "  (T1=" + t1 + "s)");
        partyA.lockFunds(legA.currency(), legA.amount());
        contractAB = new HTLC(
                contractName(legA, legB),
                legA.partyName(), legB.partyName(),
                legA.amount(), legA.currency(),
                hashLock, t1);
        Logger.info(contractAB.toString());

        // Step 3 — B verifies contract AB, then locks funds for C  (T2)
        Logger.header("STEP 3 — " + legB.partyName() + LOCKS
                + legB.amount() + " " + legB.currency()
                + FOR + legC.partyName() + "  (T2=" + t2 + "s)");
        Logger.info(legB.partyName() + " verified hashLock from " + contractAB.getName());
        partyB.lockFunds(legB.currency(), legB.amount());
        contractBC = new HTLC(
                contractName(legB, legC),
                legB.partyName(), legC.partyName(),
                legB.amount(), legB.currency(),
                hashLock, t2);
        Logger.info(contractBC.toString());

        // Step 4 — C verifies contracts AB & BC, then locks funds for A  (T3 = shortest)
        Logger.header("STEP 4 — " + legC.partyName() + LOCKS
                + legC.amount() + " " + legC.currency()
                + FOR + legA.partyName() + "  (T3=" + t3 + "s)");
        Logger.info(legC.partyName() + " verified hashLock from " + contractBC.getName());
        partyC.lockFunds(legC.currency(), legC.amount());
        contractCA = new HTLC(
                contractName(legC, legA),
                legC.partyName(), legA.partyName(),
                legC.amount(), legC.currency(),
                hashLock, t3);
        Logger.info(contractCA.toString());
        Logger.info("All 3 contracts LOCKED — Phase 1 complete.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 2 (HAPPY PATH) — REDEMPTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Runs the happy-path redemption chain.
     * Step 5: A reveals S to redeem C→A contract → gets legC.currency
     * Step 6: C reads S on-chain, redeems B→C contract → gets legB.currency
     * Step 7: B reads S on-chain, redeems A→B contract → gets legA.currency
     *
     * @return true if all 3 redemptions succeeded
     */
    private boolean phase2HappyRedemption() {

        // Step 5 — A redeems C→A (reveals secret on-chain)
        Logger.header("STEP 5 — " + legA.partyName()
                + " redeems " + contractCA.getName() + " with secret S");
        boolean s5 = contractCA.redeem(partyA.getKnownSecret(), legA.partyName());
        if (s5) {
            partyA.receiveFunds(legC.currency(), legC.amount());
            Logger.info("S is now visible on-chain — " + legC.partyName() + " can read it");
        }

        // Step 6 — C reads S, redeems B→C
        Logger.header("STEP 6 — " + legC.partyName()
                + " reads S on-chain, redeems " + contractBC.getName());
        partyC.learnSecret(contractCA.getRevealedSecret());
        boolean s6 = contractBC.redeem(partyC.getKnownSecret(), legC.partyName());
        if (s6) partyC.receiveFunds(legB.currency(), legB.amount());

        // Step 7 — B reads S, redeems A→B
        Logger.header("STEP 7 — " + legB.partyName()
                + " reads S on-chain, redeems " + contractAB.getName());
        partyB.learnSecret(contractBC.getRevealedSecret());
        boolean s7 = contractAB.redeem(partyB.getKnownSecret(), legB.partyName());
        if (s7) partyB.receiveFunds(legA.currency(), legA.amount());

        return s5 && s6 && s7;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 2 (TIMEOUT PATH) — ALICE GOES SILENT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Simulates the initiator (A) never revealing S.
     * Timelocks expire in reverse order (T3 → T2 → T1) so each party
     * reclaims their own funds without loss.
     */
    private void phase2Timeout() throws InterruptedException {
        Logger.header("TIMEOUT — " + legA.partyName() + " goes silent, timelocks expire");
        Logger.warn(legA.partyName() + " is NOT revealing S...");

        // Wait for T3 (C→A contract) to expire first
        Logger.warn(WAITING + t3 + "s for " + contractCA.getName() + " (T3) to expire...");
        Thread.sleep(t3 * 1000L + 500L);
        Logger.header("T3 EXPIRED — " + legC.partyName() + RECLAIMS + legC.currency());
        if (contractCA.refund(legC.partyName()))
            partyC.receiveFunds(legC.currency(), legC.amount());

        // Wait for T2 (B→C contract)
        long waitT2 = (t2 - t3) * 1000L + 500L;
        Logger.warn(WAITING + (t2 - t3) + "s more for " + contractBC.getName() + " (T2) to expire...");
        Thread.sleep(waitT2);
        Logger.header("T2 EXPIRED — " + legB.partyName() + RECLAIMS + legB.currency());
        if (contractBC.refund(legB.partyName()))
            partyB.receiveFunds(legB.currency(), legB.amount());

        // Wait for T1 (A→B contract)
        long waitT1 = (t1 - t2) * 1000L + 500L;
        Logger.warn(WAITING + (t1 - t2) + "s more for " + contractAB.getName() + " (T1) to expire...");
        Thread.sleep(waitT1);
        Logger.header("T1 EXPIRED — " + legA.partyName() + RECLAIMS + legA.currency());
        if (contractAB.refund(legA.partyName()))
            partyA.receiveFunds(legA.currency(), legA.amount());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Execute the happy-path swap: all parties cooperate and redeem in order.
     *
     * @return SwapResult indicating success with the secret and hashLock used
     */
    public SwapResult executeHappyPath() {
        printBanner("HAPPY PATH");
        initParties();
        phase1Setup();
        boolean success = phase2HappyRedemption();

        Logger.header("SWAP COMPLETE — Final balances");
        printAllWallets("Final balances");

        if (success) {
            Logger.success("All 3 contracts redeemed. Atomic swap successful!");
            printSwapSummary();
            return SwapResult.ok(secret, hashLock);
        } else {
            Logger.error("One or more redemptions failed — check logs above");
            return SwapResult.failed("Redemption failure", hashLock);
        }
    }

    /**
     * Execute the timeout path: initiator (A) never reveals S.
     * All three contracts expire and every party reclaims their funds.
     * NOTE: This blocks for (T1 + 1.5) seconds due to real sleeps.
     *
     * @return SwapResult indicating refund outcome
     */
    public SwapResult executeTimeoutPath() throws InterruptedException {
        printBanner("TIMEOUT PATH");
        initParties();
        phase1Setup();
        phase2Timeout();

        Logger.header("TIMEOUT COMPLETE — Final balances");
        printAllWallets("Final balances");
        Logger.warn("Swap did NOT complete — all funds refunded to original owners");
        Logger.warn("Atomicity preserved: nobody gained or lost anything.");

        return SwapResult.failed(
                legA.partyName() + " did not reveal secret — timelocks expired", hashLock);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private static String contractName(SwapLeg sender, SwapLeg recipient) {
        return "Contract_" + sender.partyName() + "→" + recipient.partyName();
    }

    private void printBanner(String scenario) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf ("║  3-PARTY ATOMIC SWAP  —  %-36s║%n", scenario);
        System.out.printf ("║  %s(%s→%s)  %s(%s→%s)  %s(%s→%s)%n",
                legA.partyName(), legA.currency(), legC.currency(),
                legB.partyName(), legB.currency(), legA.currency(),
                legC.partyName(), legC.currency(), legB.currency());
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    private void printAllWallets(String label) {
        System.out.println();
        System.out.println("  ── " + label + " ──────────────────────────────────");
        partyA.printWallet();
        partyB.printWallet();
        partyC.printWallet();
        System.out.println("  ────────────────────────────────────────────────────");
        System.out.println();
    }

    private void printSwapSummary() {
        System.out.println();
        System.out.println("  ── Swap Summary ──────────────────────────────────────────────");
        System.out.printf (PRINT_STR,
                legA.partyName(), legA.amount(), legA.currency(), legC.amount(), legC.currency());
        System.out.printf (PRINT_STR,
                legB.partyName(), legB.amount(), legB.currency(), legA.amount(), legA.currency());
        System.out.printf (PRINT_STR,
                legC.partyName(), legC.amount(), legC.currency(), legB.amount(), legB.currency());
        System.out.println("  ──────────────────────────────────────────────────────────────");
    }
}
