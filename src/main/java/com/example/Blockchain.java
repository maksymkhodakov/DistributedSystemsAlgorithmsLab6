package com.example;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Blockchain — In-memory simulation of a single-currency blockchain node.

 * Supports the three on-chain operations that HTLC-based atomic swaps require:
 *   deploy()  — lock funds into a new contract (Phase 1)
 *   redeem()  — claim funds by revealing the secret S (Phase 2, happy path)
 *   refund()  — reclaim funds after timelock expiry (Phase 2, timeout path)

 * Each operation is atomic (synchronized) and, on success, broadcasts a
 * BlockchainEvent to all subscribers.  Delivery is asynchronous — each
 * subscriber is called on a dedicated dispatcher thread — so parties react
 * independently and concurrently, just as real network participants would.

 * One Blockchain instance represents one currency's chain.
 * The 3-party swap uses three separate Blockchain instances.
 */
public class Blockchain {

    private final String currency;

    /** All contracts ever deployed on this chain, keyed by contract name. */
    private final Map<String, HTLC> contracts = new ConcurrentHashMap<>();

    /** Registered event listeners (one per subscribing party). */
    private final List<Consumer<BlockchainEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Thread pool used to deliver events to subscribers.
     * Each event dispatch runs on a separate thread so subscribers don't
     * block each other — simulating independent network participants.
     */
    private final ExecutorService dispatcher;

    public Blockchain(String currency) {
        this.currency = currency;
        this.dispatcher = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, currency + "-node");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Deploy a new HTLC contract on this chain.
     * Funds are considered locked once this call returns.
     * All subscribers are notified asynchronously.
     *
     * @param contract  The HTLC to deploy
     */
    public synchronized void deploy(HTLC contract) {
        contracts.put(contract.getName(), contract);
        Logger.info("[" + currency + " chain] Deployed: " + contract.getName());
        emit(new BlockchainEvent(BlockchainEvent.Type.CONTRACT_DEPLOYED, contract, null));
    }

    /**
     * Attempt to redeem a contract by presenting the secret preimage S.

     * If the contract exists, is still PENDING, has not expired, and the
     * hash of {@code secret} matches the contract's hashLock, the contract
     * transitions to REDEEMED and all subscribers receive the revealed secret.
     *
     * @param contractName  Name of the contract to redeem
     * @param secret        The plaintext secret S
     * @param caller        Name of the party attempting redemption (for logging)
     * @return true if redemption succeeded
     */
    public synchronized boolean redeem(String contractName, String secret, String caller) {
        HTLC contract = contracts.get(contractName);
        if (contract == null) {
            Logger.warn("[" + currency + " chain] Redeem failed — contract not found: " + contractName);
            return false;
        }
        if (contract.redeem(secret, caller)) {
            emit(new BlockchainEvent(BlockchainEvent.Type.CONTRACT_REDEEMED, contract, secret));
            return true;
        }
        return false;
    }

    /**
     * Attempt to refund a contract after its timelock has expired.

     * Only succeeds if the contract is still PENDING and its timelock has
     * passed.  If the contract was already redeemed, this silently returns false.
     *
     * @param contractName  Name of the contract to refund
     * @param caller        Name of the party requesting the refund (should be sender)
     * @return true if refund succeeded
     */
    public synchronized boolean refund(String contractName, String caller) {
        HTLC contract = contracts.get(contractName);
        if (contract == null) return false;
        if (contract.refund(caller)) {
            emit(new BlockchainEvent(BlockchainEvent.Type.CONTRACT_REFUNDED, contract, null));
            return true;
        }
        return false;
    }

    /**
     * Subscribe to all events on this blockchain.
     * The listener will be invoked asynchronously on a dispatcher thread
     * whenever a contract is deployed, redeemed, or refunded.
     *
     * @param listener  Event handler to register
     */
    public void subscribe(Consumer<BlockchainEvent> listener) {
        listeners.add(listener);
    }

    /** Shut down the dispatcher thread pool. Call after the swap completes. */
    public void shutdown() {
        dispatcher.shutdown();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void emit(BlockchainEvent event) {
        for (Consumer<BlockchainEvent> listener : listeners) {
            dispatcher.submit(() -> {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    Logger.error("[" + currency + " chain] Listener error: " + e.getMessage());
                }
            });
        }
    }
}
