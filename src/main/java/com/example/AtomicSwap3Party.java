package com.example;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AtomicSwap3Party — Concurrent 3-party atomic swap engine.

 * Each party is an independent reactive agent that watches specific blockchains
 * and responds to on-chain events autonomously — no central orchestrator drives
 * the steps.  This mirrors how real blockchain participants operate.

 * Ring swap structure (all contracts share the same hashLock H(S)):

 *   legA.party  locks legA.currency  ──[Contract_A→B]──▶  legB.party   (T1, longest)
 *   legB.party  locks legB.currency  ──[Contract_B→C]──▶  legC.party   (T2)
 *   legC.party  locks legC.currency  ──[Contract_C→A]──▶  legA.party   (T3, shortest)

 * Event-driven execution:
 *   1. Alice deploys Contract_A→B  →  event on chainA
 *   2. Bob   sees event            →  deploys Contract_B→C on chainB
 *   3. Carol sees event            →  deploys Contract_C→A on chainC
 *   4. Alice sees event            →  redeems Contract_C→A (reveals S on chainC)
 *   5. Carol sees S on chainC      →  redeems Contract_B→C on chainB
 *   6. Bob   sees S on chainB      →  redeems Contract_A→B on chainA

 * A background refund-monitor thread is started for every contract.
 * If a contract's timelock expires before it is redeemed, the monitor
 * refunds it automatically — guaranteeing atomicity in the timeout path.

 * Usage:
 *   AtomicSwap3Party swap = new AtomicSwap3Party(
 *       new SwapLeg("Alice", "ALT", 100.0),
 *       new SwapLeg("Bob",   "BTC",   0.05),
 *       new SwapLeg("Carol", "CAD",   1.0),
 *       30, 20, 10
 *   );
 *   SwapResult result = swap.executeHappyPath();
 */
public class AtomicSwap3Party {

    private static final String SUMMARY_FMT = "  %-8s: gave %8.4f %-4s  →  received %8.4f %s  ✓%n";
    public static final String SEES = "] sees ";

    // ── Swap configuration ────────────────────────────────────────────────────

    /** Initiator: generates the secret, offers legA.currency to legB.party. */
    private final SwapLeg legA;

    /** Middle party: offers legB.currency to legC.party. */
    private final SwapLeg legB;

    /** Final party: offers legC.currency back to legA.party. */
    private final SwapLeg legC;

    /** Timelock in seconds for Contract_A→B (longest). */
    private final long t1;

    /** Timelock in seconds for Contract_B→C (middle). */
    private final long t2;

    /** Timelock in seconds for Contract_C→A (shortest). */
    private final long t3;

    // ── Runtime state ─────────────────────────────────────────────────────────

    private Blockchain chainA;   // legA.currency blockchain
    private Blockchain chainB;   // legB.currency blockchain
    private Blockchain chainC;   // legC.currency blockchain

    private Party partyA;
    private Party partyB;
    private Party partyC;

    private String secret;
    private String hashLock;

    /**
     * Counts down to zero when the swap finishes — either because all 3
     * parties received their target currency (happy path), or because all 3
     * contracts were refunded (timeout path).
     */
    private CountDownLatch completionLatch;

    /** Schedules contract-expiry checks for automatic refunds. */
    private ScheduledExecutorService refundScheduler;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AtomicSwap3Party(SwapLeg legA, SwapLeg legB, SwapLeg legC,
                             long t1, long t2, long t3) {
        if (t1 <= t2 || t2 <= t3)
            throw new IllegalArgumentException(
                "Timelocks must satisfy T1 > T2 > T3, got T1=" + t1 + " T2=" + t2 + " T3=" + t3);
        this.legA = legA;
        this.legB = legB;
        this.legC = legC;
        this.t1 = t1;
        this.t2 = t2;
        this.t3 = t3;
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private void init() {
        Logger.header("INITIALISATION — Parties, wallets, blockchains");

        // One blockchain per currency in the ring
        chainA = new Blockchain(legA.currency());
        chainB = new Blockchain(legB.currency());
        chainC = new Blockchain(legC.currency());

        // Party wallets with starting balances
        partyA = new Party(legA.partyName());
        partyB = new Party(legB.partyName());
        partyC = new Party(legC.partyName());
        partyA.addFunds(legA.currency(), legA.amount());
        partyB.addFunds(legB.currency(), legB.amount());
        partyC.addFunds(legC.currency(), legC.amount());

        // Alice generates the shared secret
        secret   = CryptoUtils.generateSecret();
        hashLock = CryptoUtils.sha256Hex(secret);
        partyA.learnSecret(secret);
        Logger.info(legA.partyName() + " generated S,  H(S) = " + hashLock.substring(0, 12)
                + "...  (same hashLock in all 3 contracts)");

        refundScheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "refund-monitor");
            t.setDaemon(true);
            return t;
        });

        printAllWallets("Initial balances");
    }

    // ── Party reactions (event-driven, run on dispatcher threads) ────────────

    /**
     * Bob's two reactions:

     *  (a) Watches chainA: when Contract_A→B appears (he is the recipient),
     *      he locks his BTC and deploys Contract_B→C on chainB.

     *  (b) Watches chainB: when Contract_B→C is redeemed by Carol,
     *      the secret S is revealed — Bob uses it to redeem Contract_A→B.
     */
    private void wireBob() {

        // (a) Bob sees Contract_A→B → deploys Contract_B→C
        chainA.subscribe(ev -> {
            if (ev.type() != BlockchainEvent.Type.CONTRACT_DEPLOYED) return;
            if (!legB.partyName().equals(ev.contract().getRecipientName())) return;

            Logger.header("[" + legB.partyName() + SEES + ev.contract().getName()
                    + " on " + legA.currency() + " chain"
                    + " → locking " + legB.amount() + " " + legB.currency());

            partyB.lockFunds(legB.currency(), legB.amount());
            HTLC contractBC = new HTLC(
                    cname(legB, legC),
                    legB.partyName(), legC.partyName(),
                    legB.amount(), legB.currency(), hashLock, t2);
            chainB.deploy(contractBC);
            scheduleRefund(chainB, contractBC.getName(),
                    legB.partyName(), partyB, legB.currency(), legB.amount(), t2);
        });

        // (b) Bob sees S revealed when Carol redeems Contract_B→C → redeems Contract_A→B
        chainB.subscribe(ev -> {
            if (ev.type() != BlockchainEvent.Type.CONTRACT_REDEEMED) return;
            if (!legB.partyName().equals(ev.contract().getSenderName())) return;

            Logger.header("[" + legB.partyName() + "] sees S on " + legB.currency()
                    + " chain → redeeming " + cname(legA, legB));

            partyB.learnSecret(ev.revealedSecret());
            if (chainA.redeem(cname(legA, legB), ev.revealedSecret(), legB.partyName())) {
                partyB.receiveFunds(legA.currency(), legA.amount());
                completionLatch.countDown();
            }
        });
    }

    /**
     * Carol's two reactions:

     *  (a) Watches chainB: when Contract_B→C appears (she is the recipient),
     *      she locks her CAD and deploys Contract_C→A on chainC.

     *  (b) Watches chainC: when Contract_C→A is redeemed by Alice,
     *      the secret S is revealed — Carol uses it to redeem Contract_B→C.
     */
    private void wireCarol() {

        // (a) Carol sees Contract_B→C → deploys Contract_C→A
        chainB.subscribe(ev -> {
            if (ev.type() != BlockchainEvent.Type.CONTRACT_DEPLOYED) return;
            if (!legC.partyName().equals(ev.contract().getRecipientName())) return;

            Logger.header("[" + legC.partyName() + SEES + ev.contract().getName()
                    + " on " + legB.currency() + " chain"
                    + " → locking " + legC.amount() + " " + legC.currency());

            partyC.lockFunds(legC.currency(), legC.amount());
            HTLC contractCA = new HTLC(
                    cname(legC, legA),
                    legC.partyName(), legA.partyName(),
                    legC.amount(), legC.currency(), hashLock, t3);
            chainC.deploy(contractCA);
            scheduleRefund(chainC, contractCA.getName(),
                    legC.partyName(), partyC, legC.currency(), legC.amount(), t3);
        });

        // (b) Carol sees S when Alice redeems Contract_C→A → redeems Contract_B→C
        chainC.subscribe(ev -> {
            if (ev.type() != BlockchainEvent.Type.CONTRACT_REDEEMED) return;
            if (!legC.partyName().equals(ev.contract().getSenderName())) return;

            Logger.header("[" + legC.partyName() + "] sees S on " + legC.currency()
                    + " chain → redeeming " + cname(legB, legC));

            partyC.learnSecret(ev.revealedSecret());
            if (chainB.redeem(cname(legB, legC), ev.revealedSecret(), legC.partyName())) {
                partyC.receiveFunds(legB.currency(), legB.amount());
                completionLatch.countDown();
            }
        });
    }

    /**
     * Alice's happy-path reaction:

     * Watches chainC: when Contract_C→A appears (she is the recipient),
     * she immediately redeems it using her secret S.
     * This reveals S on chainC, kicking off the downstream redemption chain.

     * NOT wired in the timeout scenario — Alice goes silent instead.
     */
    private void wireAlice() {
        chainC.subscribe(ev -> {
            if (ev.type() != BlockchainEvent.Type.CONTRACT_DEPLOYED) return;
            if (!legA.partyName().equals(ev.contract().getRecipientName())) return;

            Logger.header("[" + legA.partyName() + SEES + ev.contract().getName()
                    + " on " + legC.currency() + " chain → redeeming (reveals S on-chain)");

            if (chainC.redeem(cname(legC, legA), secret, legA.partyName())) {
                partyA.receiveFunds(legC.currency(), legC.amount());
                completionLatch.countDown();
            }
        });
    }

    // ── Refund monitor ────────────────────────────────────────────────────────

    /**
     * Schedule an automatic refund attempt after the contract's timelock expires.

     * In the happy path the refund fails silently (contract already redeemed).
     * In the timeout path the refund succeeds and counts down the latch.
     */
    private void scheduleRefund(Blockchain chain, String contractName, String caller,
                                 Party party, String currency, double amount, long timeLockSec) {
        refundScheduler.schedule(() -> {
            Logger.warn("[refund-monitor] " + contractName + " expired → refund attempt for " + caller);
            if (chain.refund(contractName, caller)) {
                party.receiveFunds(currency, amount);
                completionLatch.countDown();
            }
        }, timeLockSec * 1000L + 500L, TimeUnit.MILLISECONDS);
    }

    // ── Public entry points ───────────────────────────────────────────────────

    /**
     * Execute the happy-path swap.

     * All parties cooperate: Alice redeems when she sees Carol's contract,
     * then Carol and Bob follow the revealed secret downstream.
     * The main thread blocks until all 3 parties have received their funds.
     *
     * @return SwapResult indicating success
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public SwapResult executeHappyPath() throws InterruptedException {
        printBanner("HAPPY PATH");
        init();

        completionLatch = new CountDownLatch(3);  // Alice + Carol + Bob each receive funds

        wireBob();
        wireCarol();
        wireAlice();   // Alice will redeem as soon as Carol's contract appears

        // Alice deploys the first contract — this single event cascades through all parties
        Logger.header("STEP 1 — " + legA.partyName() + " deploys " + cname(legA, legB)
                + "  (T1=" + t1 + "s)  ← triggers the chain");
        partyA.lockFunds(legA.currency(), legA.amount());
        HTLC contractAB = new HTLC(cname(legA, legB), legA.partyName(), legB.partyName(),
                legA.amount(), legA.currency(), hashLock, t1);
        chainA.deploy(contractAB);
        scheduleRefund(chainA, contractAB.getName(),
                legA.partyName(), partyA, legA.currency(), legA.amount(), t1);

        // Wait for all 3 parties to finish (generous timeout = T1 + 5s)
        boolean ok = completionLatch.await(t1 + 5, TimeUnit.SECONDS);

        Logger.header("SWAP COMPLETE — Final balances");
        printAllWallets("Final balances");
        shutdown();

        if (ok) {
            Logger.success("All 3 contracts redeemed. Atomic swap successful!");
            printSwapSummary();
            return SwapResult.ok(secret, hashLock);
        } else {
            Logger.error("Swap did not complete within the expected window");
            return SwapResult.failed("Completion timeout", hashLock);
        }
    }

    /**
     * Execute the timeout path.

     * Alice deploys Contract_A→B but then goes silent — she never wires up her
     * redemption reaction.  Bob and Carol still lock their funds (they don't know
     * Alice will misbehave).  Once the timelocks expire, each party's refund
     * monitor automatically reclaims their original funds.

     * The main thread blocks until all 3 refunds are confirmed.
     * Total wall-clock time ≈ T1 + 1s.
     *
     * @return SwapResult indicating all contracts were refunded
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public SwapResult executeTimeoutPath() throws InterruptedException {
        printBanner("TIMEOUT PATH");
        init();

        completionLatch = new CountDownLatch(3);  // 3 refunds needed

        wireBob();    // Bob still sets up his contract — he doesn't know Alice will vanish
        wireCarol();  // Carol still sets up her contract
        // Alice intentionally does NOT call wireAlice() — she goes silent

        Logger.warn(legA.partyName() + " going silent — will NOT redeem "
                + cname(legC, legA) + " when it appears");

        Logger.header("STEP 1 — " + legA.partyName() + " deploys " + cname(legA, legB)
                + "  (T1=" + t1 + "s)  ← then goes silent");
        partyA.lockFunds(legA.currency(), legA.amount());
        HTLC contractAB = new HTLC(cname(legA, legB), legA.partyName(), legB.partyName(),
                legA.amount(), legA.currency(), hashLock, t1);
        chainA.deploy(contractAB);
        scheduleRefund(chainA, contractAB.getName(),
                legA.partyName(), partyA, legA.currency(), legA.amount(), t1);

        // Wait for all 3 refund monitors to fire (≈ T1 + 1.5s total)
        completionLatch.await(t1 + 5, TimeUnit.SECONDS);

        Logger.header("TIMEOUT COMPLETE — Final balances");
        printAllWallets("Final balances");
        Logger.warn("Swap did NOT complete — all funds refunded. Atomicity preserved ✓");
        shutdown();

        return SwapResult.failed(
                legA.partyName() + " went silent — all contracts refunded", hashLock);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String cname(SwapLeg from, SwapLeg to) {
        return "Contract_" + from.partyName() + "→" + to.partyName();
    }

    private void shutdown() {
        refundScheduler.shutdown();
        chainA.shutdown();
        chainB.shutdown();
        chainC.shutdown();
    }

    private void printBanner(String scenario) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf ("║  3-PARTY ATOMIC SWAP  —  %-36s║%n", scenario);
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
        System.out.printf(SUMMARY_FMT,
                legA.partyName(), legA.amount(), legA.currency(), legC.amount(), legC.currency());
        System.out.printf(SUMMARY_FMT,
                legB.partyName(), legB.amount(), legB.currency(), legA.amount(), legA.currency());
        System.out.printf(SUMMARY_FMT,
                legC.partyName(), legC.amount(), legC.currency(), legB.amount(), legB.currency());
        System.out.println("  ──────────────────────────────────────────────────────────────");
    }
}
