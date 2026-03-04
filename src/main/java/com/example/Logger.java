package com.example;

/**
 * Logger — Simple console logger with ANSI color support.
 *   info    — neutral informational message  (white/default)
 *   success — positive outcome               (green)
 *   warn    — warning or notable event       (yellow)
 *   error   — error or failure               (red)
 *   header  — section separator / step title (cyan, bold)
 */
public final class Logger {

    private Logger() {}

    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String CYAN   = "\u001B[36m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";


    /** Neutral informational message. */
    public static void info(String message) {
        System.out.println("  [INFO]    " + message);
    }

    /** Positive outcome — funds received, contract redeemed, etc. */
    public static void success(String message) {
        System.out.println(GREEN + "  [SUCCESS] " + message + RESET);
    }

    /** Warning or notable event — timeout, early refund attempt, etc. */
    public static void warn(String message) {
        System.out.println(YELLOW + "  [WARN]    " + message + RESET);
    }

    /** Error or failure — redemption refused, wrong secret, etc. */
    public static void error(String message) {
        System.out.println(RED + "  [ERROR]   " + message + RESET);
    }

    /**
     * Section header printed before each numbered step.
     * Adds a blank line above and below for readability.
     */
    public static void header(String title) {
        System.out.println();
        System.out.println(BOLD + CYAN + "  ══ " + title + " ══" + RESET);
    }
}
