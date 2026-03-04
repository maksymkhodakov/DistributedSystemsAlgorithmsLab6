package com.example;

/**
 * BlockchainEvent — An event broadcast by a Blockchain when a contract changes state.
 * Parties subscribe to blockchains and react to these events asynchronously,
 * each on their own thread — just as real blockchain nodes would watch for
 * on-chain transactions.
 *
 * @param type            What happened to the contract
 * @param contract        The HTLC that triggered this event
 * @param revealedSecret  The plaintext secret S, present only on REDEEMED events
 */
public record BlockchainEvent(Type type, HTLC contract, String revealedSecret) {

    public enum Type {
        /** A new HTLC was deployed and funds are locked. */
        CONTRACT_DEPLOYED,
        /** A contract was redeemed — secret S is now publicly visible on-chain. */
        CONTRACT_REDEEMED,
        /** Timelock expired and funds were returned to the sender. */
        CONTRACT_REFUNDED
    }
}
