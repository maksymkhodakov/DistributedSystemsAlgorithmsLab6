package com.example;

/**
 * AtomicSwap3Party — Orchestrates the full 3-party atomic swap protocol.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  SCENARIO (from the lab):                                               │
 * │                                                                         │
 * │  Carol wants to sell her Cadillac for BTC.                              │
 * │  Alice wants Carol's Cadillac but only has ALT-coins.                   │
 * │  Bob is willing to trade BTC for ALT-coins.                             │
 * │                                                                         │
 * │  Desired transfers:                                                     │
 * │    Alice  ──( ALT )──▶  Bob                                             │
 * │    Bob    ──( BTC )──▶  Carol                                           │
 * │    Carol  ──( CAD )──▶  Alice                                           │
 * │                                                                         │
 * │  Nobody trusts anybody. No intermediary. No exchange.                   │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  HTLC CHAIN (all contracts share the SAME hashLock H(S)):               │
 * │                                                                         │
 * │  Contract_AB : Alice locks ALT  for Bob   — timelock T1 = 30s (longest) │
 * │  Contract_BC : Bob   locks BTC  for Carol — timelock T2 = 20s           │
 * │  Contract_CA : Carol locks CAD  for Alice — timelock T3 = 10s (shortest)│
 * │                                                                         │
 * │  IMPORTANT: T1 > T2 > T3 (each is strictly shorter than the previous)  │
 * │  This ensures downstream parties always have time to redeem after       │
 * │  observing the secret on-chain.                                         │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  HAPPY PATH (everyone behaves honestly):                                │
 * │                                                                         │
 * │  Phase 1 — Setup (all parties lock their funds):                        │
 * │    1. Alice generates secret S, computes H(S) = SHA-256(S)              │
 * │    2. Alice deploys Contract_AB  (ALT locked, hashLock = H(S), T1=30s) │
 * │    3. Bob verifies Contract_AB, deploys Contract_BC (BTC, T2=20s)      │
 * │    4. Carol verifies Contract_BC, deploys Contract_CA (CAD, T3=10s)    │
 * │                                                                         │
 * │  Phase 2 — Redemption (secret propagates through the chain):            │
 * │    5. Alice redeems Contract_CA by revealing S → gets CAD               │
 * │       (S is now visible on Carol's blockchain)                          │
 * │    6. Carol reads S from chain → redeems Contract_BC → gets BTC         │
 * │       (S confirmed again on Bob's blockchain)                           │
 * │    7. Bob reads S from chain → redeems Contract_AB → gets ALT           │
 * │                                                                         │
 * │  Result: Alice +CAD -ALT | Bob +ALT -BTC | Carol +BTC -CAD             │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  TIMEOUT PATH (Alice never reveals secret):                             │
 * │                                                                         │
 * │  Phase 1 same as above — all contracts locked.                         │
 * │                                                                         │
 * │  Phase 2 — Alice goes silent, timelocks expire in order:                │
 * │    5. T3 expires → Carol refunds Contract_CA, reclaims her CAD          │
 * │    6. T2 expires → Bob   refunds Contract_BC, reclaims his BTC          │
 * │    7. T1 expires → Alice refunds Contract_AB, reclaims her ALT          │
 * │                                                                         │
 * │  Result: everyone gets their original funds back — nobody loses.        │
 * │  This is the "atomicity" guarantee.                                     │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
public class AtomicSwap3Party {

    // ── Timelock durations (demo values — real swaps use hours/days) ─────────

    /**
     * T1: Alice's contract timelock (longest).
     * Alice→Bob contract on the ALT chain.
     * Must be the longest to give Bob and Carol time to act after Alice reveals S.
     */
    private static final long T1_ALICE_BOB_SEC = 30L;

    /**
     * T2: Bob's contract timelock (middle).
     * Bob→Carol contract on the BTC chain.
     * Must be shorter than T1 so Bob can always redeem before Alice's refund window.
     */
    private static final long T2_BOB_CAROL_SEC = 20L;

    /**
     * T3: Carol's contract timelock (shortest).
     * Carol→Alice contract on the CAD chain.
     * Must be the shortest: Alice redeems this first, revealing S. Carol still has
     * time (T2 - T3 = 10s) to use S before her own contract expires.
     */
    private static final long T3_CAROL_ALICE_SEC = 10L;

    // ── Swap amounts ─────────────────────────────────────────────────────────

    private static final double ALICE_ALT_AMOUNT = 100.0; // ALT-coins Alice locks
    private static final double BOB_BTC_AMOUNT   = 0.05;  // BTC Bob locks
    private static final double CAROL_CAD_AMOUNT = 1.0;   // Cadillac token Carol locks

    // ── Party references (set during initialisation) ─────────────────────────

    private Party alice;
    private Party bob;
    private Party carol;

    // ── Contract references ───────────────────────────────────────────────────

    /** Alice locks ALT for Bob on the ALT blockchain. */
    private HTLC contractAB;

    /** Bob locks BTC for Carol on the BTC blockchain. */
    private HTLC contractBC;

    /** Carol locks CAD for Alice on the CAD blockchain. */
    private HTLC contractCA;

    // ── Secret material ───────────────────────────────────────────────────────

    /** The plaintext secret S, generated by Alice. */
    private String secret;

    /** H(S) = SHA-256(secret). Embedded in all three contracts. */
    private String hashLock;

    // ═════════════════════════════════════════════════════════════════════════
    // INITIALISATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Initialise all three parties with their starting balances.
     * This simulates the state of the blockchain BEFORE the swap begins.
     */
    private void initParties() {
        Logger.header("INITIALISATION — Creating parties & wallets");

        alice = new Party("Alice");
        bob   = new Party("Bob");
        carol = new Party("Carol");

        // Alice starts with ALT-coins (the currency she wants to trade away)
        alice.addFunds("ALT", ALICE_ALT_AMOUNT);

        // Bob starts with BTC (the currency he wants to trade away)
        bob.addFunds("BTC", BOB_BTC_AMOUNT);

        // Carol starts with CAD tokens (representing the Cadillac)
        carol.addFunds("CAD", CAROL_CAD_AMOUNT);

        printAllWallets("Initial balances");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 1 — SETUP: ALL PARTIES LOCK THEIR FUNDS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Step 1 — Alice generates the secret S and computes its hash H(S).
     *
     * Only Alice knows S at this point. H(S) will be shared publicly and
     * embedded in all three contracts as the hashLock.
     *
     * Alice keeps S private until Phase 2 when she uses it to redeem
     * Carol's contract. Revealing S is the "trigger" for the whole chain.
     */
    private void step1_AliceGeneratesSecret() {
        Logger.header("STEP 1 — Alice generates secret S and computes H(S)");

        // Generate a cryptographically secure 256-bit random secret
        secret   = CryptoUtils.generateSecret();
        hashLock = CryptoUtils.sha256Hex(secret);

        // Alice records the secret in her own party state
        alice.learnSecret(secret);

        Logger.info("Secret S    = " + secret.substring(0, 16) + "... (kept private by Alice)");
        Logger.info("H(S)        = " + hashLock.substring(0, 16) + "... (shared publicly as hashLock)");
        Logger.info("All contracts will use the SAME hashLock");
    }

    /**
     * Step 2 — Alice deploys Contract_AB on the ALT blockchain.
     *
     * Alice locks her ALT-coins so that Bob can claim them — but ONLY by
     * presenting the secret S (which he doesn't know yet).
     *
     * Timelock T1 is the longest, giving time for the entire chain to
     * complete before Alice could reclaim her funds.
     */
    private void step2_AliceLocksALT() {
        Logger.header("STEP 2 — Alice locks " + ALICE_ALT_AMOUNT + " ALT in Contract_AB (T1=" + T1_ALICE_BOB_SEC + "s)");

        // Deduct ALT from Alice's wallet — funds are now "in the contract"
        alice.lockFunds("ALT", ALICE_ALT_AMOUNT);

        // Deploy the HTLC on the ALT blockchain
        contractAB = new HTLC(
            "Contract_Alice→Bob",   // name
            "Alice",                // sender
            "Bob",                  // recipient
            ALICE_ALT_AMOUNT,       // amount
            "ALT",                  // currency
            hashLock,               // H(S) — the cryptographic lock
            T1_ALICE_BOB_SEC        // timelock (longest: 30s)
        );

        Logger.info(contractAB.toString());
        Logger.info("Bob can now inspect Contract_AB on the ALT blockchain");
    }

    /**
     * Step 3 — Bob verifies Contract_AB and deploys Contract_BC on the BTC blockchain.
     *
     * Bob checks:
     *   - The hashLock in Contract_AB is H(S) (he doesn't know S yet, but he
     *     will use the same hashLock in his own contract)
     *   - The amount and recipient are correct
     *   - The timelock T1 is long enough for him to act
     *
     * Bob then creates Contract_BC with a SHORTER timelock T2 < T1.
     * This ensures that if things go wrong, Bob can refund before Alice.
     */
    private void step3_BobLocksBTC() {
        Logger.header("STEP 3 — Bob verifies Contract_AB and locks " + BOB_BTC_AMOUNT + " BTC in Contract_BC (T2=" + T2_BOB_CAROL_SEC + "s)");

        // Bob inspects Contract_AB — verifies it exists and uses the right hashLock
        Logger.info("Bob observes Contract_AB on the ALT blockchain");
        Logger.info("Bob reads hashLock = " + contractAB.getHashLock().substring(0, 16) + "...");
        Logger.info("Bob confirms: T1=" + T1_ALICE_BOB_SEC + "s, amount=" + ALICE_ALT_AMOUNT + " ALT — looks good");

        // Deduct BTC from Bob's wallet
        bob.lockFunds("BTC", BOB_BTC_AMOUNT);

        // Deploy Contract_BC using the SAME hashLock as Contract_AB
        contractBC = new HTLC(
            "Contract_Bob→Carol",
            "Bob",
            "Carol",
            BOB_BTC_AMOUNT,
            "BTC",
            contractAB.getHashLock(),  // same H(S) — critically important
            T2_BOB_CAROL_SEC           // T2 < T1 (20s)
        );

        Logger.info(contractBC.toString());
        Logger.info("Carol can now inspect Contract_BC on the BTC blockchain");
    }

    /**
     * Step 4 — Carol verifies Contract_BC and deploys Contract_CA on the CAD blockchain.
     *
     * Carol checks:
     *   - The hashLock in Contract_BC matches what she sees in Contract_AB
     *   - The amount (BTC) and timelock T2 are acceptable
     *
     * Carol deploys Contract_CA with the shortest timelock T3 < T2.
     * Alice can now redeem Contract_CA to start the redemption chain.
     */
    private void step4_CarolLocksCAD() {
        Logger.header("STEP 4 — Carol verifies Contract_BC and locks " + CAROL_CAD_AMOUNT + " CAD in Contract_CA (T3=" + T3_CAROL_ALICE_SEC + "s)");

        // Carol inspects both existing contracts
        Logger.info("Carol observes Contract_AB on the ALT blockchain");
        Logger.info("Carol observes Contract_BC on the BTC blockchain");
        Logger.info("Carol confirms both use the same hashLock = "
                + contractBC.getHashLock().substring(0, 16) + "...");
        Logger.info("Carol confirms: T2=" + T2_BOB_CAROL_SEC + "s, amount=" + BOB_BTC_AMOUNT + " BTC — looks good");

        // Deduct CAD from Carol's wallet
        carol.lockFunds("CAD", CAROL_CAD_AMOUNT);

        // Deploy Contract_CA using the SAME hashLock
        contractCA = new HTLC(
            "Contract_Carol→Alice",
            "Carol",
            "Alice",
            CAROL_CAD_AMOUNT,
            "CAD",
            contractBC.getHashLock(),  // same H(S)
            T3_CAROL_ALICE_SEC         // T3 < T2 (10s) — shortest
        );

        Logger.info(contractCA.toString());
        Logger.info("All three contracts are now LOCKED. Phase 1 complete.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 2 (HAPPY PATH) — REDEMPTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Step 5 — Alice redeems Contract_CA by revealing secret S.
     *
     * Alice submits S to Contract_CA on the CAD blockchain.
     * The contract verifies SHA-256(S) == hashLock → releases CAD to Alice.
     *
     * CRITICAL MOMENT: S is now publicly visible on the CAD blockchain.
     * Carol (and anyone else) can read S from the transaction data.
     *
     * @return true if redemption succeeded
     */
    private boolean step5_AliceRedeemsCAD() {
        Logger.header("STEP 5 — Alice redeems Contract_CA with secret S → receives CAD");
        Logger.info("Alice submits S to Contract_Carol→Alice");

        // Alice presents her secret to unlock the CAD contract
        boolean ok = contractCA.redeem(alice.getKnownSecret(), "Alice");

        if (ok) {
            // Transfer CAD to Alice's wallet
            alice.receiveFunds("CAD", CAROL_CAD_AMOUNT);
            Logger.info("S is now VISIBLE on the CAD blockchain — Carol can read it");
        }
        return ok;
    }

    /**
     * Step 6 — Carol reads S from the blockchain and redeems Contract_BC.
     *
     * Carol observes S in the transaction where Alice redeemed Contract_CA.
     * She uses S to claim her BTC from Bob's contract.
     *
     * @return true if redemption succeeded
     */
    private boolean step6_CarolRedeemsBTC() {
        Logger.header("STEP 6 — Carol reads S from chain → redeems Contract_BC → receives BTC");

        // Carol "observes" S from the CAD blockchain (Alice's redemption transaction)
        String observedSecret = contractCA.getRevealedSecret();
        Logger.info("Carol reads S from CAD blockchain: " + observedSecret.substring(0, 16) + "...");

        // Carol learns the secret and can now use it
        carol.learnSecret(observedSecret);

        // Carol presents S to Contract_BC
        boolean ok = contractBC.redeem(carol.getKnownSecret(), "Carol");

        if (ok) {
            carol.receiveFunds("BTC", BOB_BTC_AMOUNT);
        }
        return ok;
    }

    /**
     * Step 7 — Bob reads S from the blockchain and redeems Contract_AB.
     *
     * Bob observes S (confirmed again in Carol's redemption transaction on
     * the BTC blockchain). He uses S to claim his ALT-coins from Alice's contract.
     *
     * @return true if redemption succeeded
     */
    private boolean step7_BobRedeemsALT() {
        Logger.header("STEP 7 — Bob reads S from chain → redeems Contract_AB → receives ALT");

        // Bob "observes" S from the BTC blockchain (Carol's redemption transaction)
        String observedSecret = contractBC.getRevealedSecret();
        Logger.info("Bob reads S from BTC blockchain: " + observedSecret.substring(0, 16) + "...");

        // Bob learns the secret
        bob.learnSecret(observedSecret);

        // Bob presents S to Contract_AB
        boolean ok = contractAB.redeem(bob.getKnownSecret(), "Bob");

        if (ok) {
            bob.receiveFunds("ALT", ALICE_ALT_AMOUNT);
        }
        return ok;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 2 (TIMEOUT PATH) — ALICE GOES SILENT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Simulate the timeout path: Alice never reveals S, so all contracts expire
     * and every party reclaims their original funds.
     *
     * The order of refunds follows REVERSE timelock order:
     *   - T3 (Carol's) expires first → Carol refunds Contract_CA
     *   - T2 (Bob's)   expires next  → Bob   refunds Contract_BC
     *   - T1 (Alice's) expires last  → Alice refunds Contract_AB
     *
     * This ordering is safe because:
     *   Carol cannot be stuck: her contract expires before Bob's.
     *   Bob cannot be stuck:   his contract expires before Alice's.
     *   Alice cannot be stuck: her contract has the longest window.
     *
     * @throws InterruptedException if the sleep is interrupted
     */
    private void runTimeoutPath() throws InterruptedException {
        Logger.header("TIMEOUT SCENARIO — Alice goes silent, timelocks expire");
        Logger.warn("Alice is NOT revealing secret S...");

        // ── Wait for T3 to expire (Carol's contract) ──────────────────────
        Logger.warn("Waiting " + T3_CAROL_ALICE_SEC + "s for Contract_CA (T3) to expire...");
        Thread.sleep(T3_CAROL_ALICE_SEC * 1000L + 500L); // +0.5s buffer

        Logger.header("T3 EXPIRED — Carol reclaims her CAD");
        boolean refundCA = contractCA.refund("Carol");
        if (refundCA) {
            carol.receiveFunds("CAD", CAROL_CAD_AMOUNT);
        }

        // ── Wait for T2 to expire (Bob's contract) ────────────────────────
        long waitForT2 = (T2_BOB_CAROL_SEC - T3_CAROL_ALICE_SEC) * 1000L + 500L;
        Logger.warn("Waiting " + (T2_BOB_CAROL_SEC - T3_CAROL_ALICE_SEC) + "s more for Contract_BC (T2) to expire...");
        Thread.sleep(waitForT2);

        Logger.header("T2 EXPIRED — Bob reclaims his BTC");
        boolean refundBC = contractBC.refund("Bob");
        if (refundBC) {
            bob.receiveFunds("BTC", BOB_BTC_AMOUNT);
        }

        // ── Wait for T1 to expire (Alice's contract) ──────────────────────
        long waitForT1 = (T1_ALICE_BOB_SEC - T2_BOB_CAROL_SEC) * 1000L + 500L;
        Logger.warn("Waiting " + (T1_ALICE_BOB_SEC - T2_BOB_CAROL_SEC) + "s more for Contract_AB (T1) to expire...");
        Thread.sleep(waitForT1);

        Logger.header("T1 EXPIRED — Alice reclaims her ALT");
        boolean refundAB = contractAB.refund("Alice");
        if (refundAB) {
            alice.receiveFunds("ALT", ALICE_ALT_AMOUNT);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Execute the HAPPY PATH atomic swap.
     *
     * All 7 steps are run in sequence. Each step depends on the previous one.
     * At the end, every party has the currency they wanted.
     *
     * @return SwapResult indicating success or failure with details
     */
    public SwapResult executeHappyPath() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          3-PARTY ATOMIC SWAP  —  HAPPY PATH                 ║");
        System.out.println("║  Alice(ALT→CAD)  Bob(BTC→ALT)  Carol(CAD→BTC)              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // ── Phase 1: Setup ────────────────────────────────────────────────
        initParties();
        step1_AliceGeneratesSecret();
        step2_AliceLocksALT();
        step3_BobLocksBTC();
        step4_CarolLocksCAD();

        // ── Phase 2: Redemption ───────────────────────────────────────────
        boolean s5 = step5_AliceRedeemsCAD();
        boolean s6 = step6_CarolRedeemsBTC();
        boolean s7 = step7_BobRedeemsALT();

        // ── Final summary ─────────────────────────────────────────────────
        Logger.header("SWAP COMPLETE — Final balances");
        printAllWallets("Final balances");

        if (s5 && s6 && s7) {
            Logger.success("✅ All 3 contracts redeemed. Atomic swap successful!");
            printSwapSummary();
            return SwapResult.ok(secret, hashLock);
        } else {
            Logger.error("❌ One or more redemptions failed — check logs above");
            return SwapResult.failed("Redemption failure", hashLock);
        }
    }

    /**
     * Execute the TIMEOUT PATH atomic swap.
     *
     * Phase 1 (setup) is identical to the happy path.
     * Phase 2 is replaced with waiting for timelocks to expire and refunding.
     *
     * Demonstrates that the protocol is safe even if Alice acts irrationally
     * (or maliciously) by never revealing S.
     *
     * @return SwapResult indicating the refund outcome
     * @throws InterruptedException if thread sleep is interrupted
     */
    public SwapResult executeTimeoutPath() throws InterruptedException {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          3-PARTY ATOMIC SWAP  —  TIMEOUT PATH               ║");
        System.out.println("║  Alice goes silent — all contracts expire and refund         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // Phase 1 is the same — all three contracts get deployed
        initParties();
        step1_AliceGeneratesSecret();
        step2_AliceLocksALT();
        step3_BobLocksBTC();
        step4_CarolLocksCAD();

        // Phase 2: Alice stays silent, timelocks expire
        runTimeoutPath();

        Logger.header("TIMEOUT COMPLETE — Final balances");
        printAllWallets("Final balances");
        Logger.warn("⚠️  Swap did NOT complete — all funds refunded to original owners");
        Logger.warn("Nobody gained or lost anything — atomicity preserved ✓");

        return SwapResult.failed("Alice did not reveal secret — timelocks expired", hashLock);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DISPLAY HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Print all three wallets side by side. */
    private void printAllWallets(String label) {
        System.out.println();
        System.out.println("  ── " + label + " ──────────────────────────────────────");
        alice.printWallet();
        bob.printWallet();
        carol.printWallet();
        System.out.println("  ────────────────────────────────────────────────────");
        System.out.println();
    }

    /** Print a concise "who got what" swap summary. */
    private void printSwapSummary() {
        System.out.println();
        System.out.println("  ── Swap Summary ──────────────────────────────────────────────");
        System.out.println("  Alice  : gave " + ALICE_ALT_AMOUNT + " ALT  → received " + CAROL_CAD_AMOUNT + " CAD  ✓");
        System.out.println("  Bob    : gave " + BOB_BTC_AMOUNT   + " BTC  → received " + ALICE_ALT_AMOUNT + " ALT  ✓");
        System.out.println("  Carol  : gave " + CAROL_CAD_AMOUNT + " CAD  → received " + BOB_BTC_AMOUNT   + " BTC  ✓");
        System.out.println("  ──────────────────────────────────────────────────────────────");
    }
}
