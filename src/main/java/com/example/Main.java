package com.example;

import java.util.Scanner;

/**
 * Main — Entry point for the 3-party Atomic Swap demonstration.
 * Configures a concrete swap scenario and runs it through AtomicSwap3Party.
 * To swap different currencies or amounts, change the SwapLeg parameters below.

 * Scenario used here:
 *   Alice  has 100 ALT-coins, wants Carol's CAD token
 *   Bob    has 0.05 BTC,      wants Alice's ALT-coins
 *   Carol  has 1 CAD token,   wants Bob's BTC

 * Ring:  Alice(ALT→CAD)  Bob(BTC→ALT)  Carol(CAD→BTC)

 * Two scenarios:
 *   HAPPY PATH   — all parties cooperate, all contracts redeemed
 *   TIMEOUT PATH — initiator (Alice) goes silent, all contracts refunded (~30s)
 */
public class Main {
    private static final SwapLeg LEG_A = new SwapLeg("Alice", "ALT", 100.0);
    private static final SwapLeg LEG_B = new SwapLeg("Bob",   "BTC",   0.05);
    private static final SwapLeg LEG_C = new SwapLeg("Carol", "CAD",   1.0);

    // Timelocks in seconds (must satisfy T1 > T2 > T3).
    // In production these would be hours or days, not seconds.
    private static final long T1 = 30L;
    private static final long T2 = 20L;
    private static final long T3 = 10L;
    public static final String HAPPY = "happy";
    public static final String TIMEOUT = "timeout";

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws InterruptedException {

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════╗");
        System.out.println("  ║   ATOMIC SWAP — 3 CURRENCIES (HTLC Protocol)        ║");
        System.out.printf ("  ║   %s(%s→%s)  %s(%s→%s)  %s(%s→%s)%n",
                LEG_A.partyName(), LEG_A.currency(), LEG_C.currency(),
                LEG_B.partyName(), LEG_B.currency(), LEG_A.currency(),
                LEG_C.partyName(), LEG_C.currency(), LEG_B.currency());
        System.out.println("  ╚══════════════════════════════════════════════════════╝");
        System.out.println();

        if (args.length > 0) {
            runScenario(args[0].toLowerCase());
            return;
        }

        Scanner scanner = new Scanner(System.in);
        System.out.println("  Choose a scenario:");
        System.out.println("    [1] Happy Path   — all parties cooperate (fast)");
        System.out.println("    [2] Timeout Path — initiator goes silent  (~" + T1 + "s wait)");
        System.out.println("    [3] Run both back-to-back");
        System.out.print("\n  Your choice (1/2/3): ");

        String choice = scanner.nextLine().trim();
        System.out.println();

        switch (choice) {
            case "1" -> runScenario(HAPPY);
            case "2" -> runScenario(TIMEOUT);
            case "3" -> {
                runScenario(HAPPY);
                System.out.println("\n\n  ══ Starting Timeout Scenario ══\n");
                runScenario(TIMEOUT);
            }
            default -> {
                System.out.println("  Unknown choice — running Happy Path by default.");
                runScenario(HAPPY);
            }
        }

        scanner.close();
    }

    private static void runScenario(String scenario) throws InterruptedException {
        AtomicSwap3Party swap = new AtomicSwap3Party(LEG_A, LEG_B, LEG_C, T1, T2, T3);

        SwapResult result = switch (scenario) {
            case TIMEOUT -> swap.executeTimeoutPath();
            case HAPPY -> swap.executeHappyPath();
            default -> swap.executeHappyPath();
        };

        System.out.println();
        System.out.println("  ── Result ────────────────────────────────────────────────");
        System.out.println("  " + result);
        System.out.println();
    }
}
