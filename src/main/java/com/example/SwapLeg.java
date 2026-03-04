package com.example;

/**
 * SwapLeg — Configuration for one participant's side of a 3-party atomic swap.

 * In a ring swap A→B→C→A:
 *   legA: party A offers `amount` of `currency` to party B
 *   legB: party B offers `amount` of `currency` to party C
 *   legC: party C offers `amount` of `currency` to party A
 *
 * @param partyName  Display name of this participant
 * @param currency   The currency/token this party is offering
 * @param amount     How much of that currency they are offering
 */
public record SwapLeg(String partyName,
                      String currency,
                      double amount) {

    public SwapLeg {
        if (partyName == null || partyName.isBlank())
            throw new IllegalArgumentException("Party name must not be blank");
        if (currency == null || currency.isBlank())
            throw new IllegalArgumentException("Currency must not be blank");
        if (amount <= 0)
            throw new IllegalArgumentException("Amount must be positive, got: " + amount);
    }
}
