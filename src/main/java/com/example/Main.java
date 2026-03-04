package com.example;

import java.util.Scanner;

/**
 * Main — Entry point for the 3-party Atomic Swap demonstration.
 *   1. HAPPY PATH   — all three parties behave honestly.
 *                     All contracts are redeemed in sequence.
 *                     Each party ends up with the currency they wanted.
 *   2. TIMEOUT PATH — Alice goes silent after Phase 1 and never reveals S.
 *                     Timelocks expire in order (T3 → T2 → T1).
 *                     Every party reclaims their original funds.
 *                     NOTE: This scenario takes ~30s to run due to real sleeps.
 * Usage:
 *   Compile:  javac -d out src/atomicswap/*.java
 *   Run:      java  -cp out atomicswap.Main
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {

        // ── Banner ─────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════╗");
        System.out.println("  ║   ATOMIC SWAP — 3 CURRENCIES (Alice / Bob / Carol)  ║");
        System.out.println("  ║   Hash Time-Locked Contracts (HTLC) Simulation      ║");
        System.out.println("  ║   Based on Tier Nolan (2013) protocol               ║");
        System.out.println("  ╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Check for command-line argument (non-interactive mode) ─────────
        // Usage:  java -cp out atomicswap.Main happy
        //         java -cp out atomicswap.Main timeout
        if (args.length > 0) {
            runScenario(args[0].toLowerCase());
            return;
        }

        // ── Interactive menu ───────────────────────────────────────────────
        Scanner scanner = new Scanner(System.in);
        System.out.println("  Choose a scenario:");
        System.out.println("    [1] Happy Path   — all parties cooperate (fast)");
        System.out.println("    [2] Timeout Path — Alice goes silent   (~30s wait)");
        System.out.println("    [3] Run both scenarios back-to-back");
        System.out.print("\n  Your choice (1/2/3): ");

        String choice = scanner.nextLine().trim();
        System.out.println();

        switch (choice) {
            case "1" -> runScenario("happy");
            case "2" -> runScenario("timeout");
            case "3" -> {
                runScenario("happy");
                System.out.println("\n\n  ══ Starting Timeout Scenario ══\n");
                runScenario("timeout");
            }
            default -> {
                System.out.println("  Unknown choice — running Happy Path by default.");
                runScenario("happy");
            }
        }

        scanner.close();
    }

    // ── Scenario dispatcher ────────────────────────────────────────────────

    /**
     * Instantiate a fresh swap system and run the requested scenario.
     * Each call creates a new AtomicSwap3Party instance so parties, wallets,
     * and contracts are completely reset between runs.
     *
     * @param scenario  "happy" or "timeout"
     */
    private static void runScenario(String scenario) throws InterruptedException {
        AtomicSwap3Party swap = new AtomicSwap3Party();

        SwapResult result = switch (scenario) {
            case "timeout" -> swap.executeTimeoutPath();
            default        -> swap.executeHappyPath();
        };

        // Print the final machine-readable result
        System.out.println();
        System.out.println("  ── Result object ─────────────────────────────────────────");
        System.out.println("  " + result);
        System.out.println();
    }
}
