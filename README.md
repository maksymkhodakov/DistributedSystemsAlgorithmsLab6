# Розподілені системи обробки інформації
## Лабораторна робота №6
## Ходаков Максим ШІ - 1

# 3-Party Atomic Swap — Java Implementation

Hash Time-Locked Contract (HTLC) based atomic swap for **3 currencies**,
based on the Tier Nolan (2013) protocol.

## Scenario

| Party | Has    | Wants  |
|-------|--------|--------|
| Alice | ALT    | CAD (Cadillac token) |
| Bob   | BTC    | ALT    |
| Carol | CAD    | BTC    |

## How to Build & Run

```
Phase 1 — Setup (all parties lock funds):
  Step 1: Alice generates secret S, computes H(S) = SHA-256(S)
  Step 2: Alice locks ALT  in Contract_AB  [hashLock=H(S), T1=30s]
  Step 3: Bob   locks BTC  in Contract_BC  [hashLock=H(S), T2=20s]
  Step 4: Carol locks CAD  in Contract_CA  [hashLock=H(S), T3=10s]

Phase 2 — Redemption (secret propagates):
  Step 5: Alice redeems Contract_CA with S  → gets CAD, S revealed on-chain
  Step 6: Carol reads S from chain          → redeems Contract_BC, gets BTC
  Step 7: Bob   reads S from chain          → redeems Contract_AB, gets ALT
```

## Timelock Ordering (T1 > T2 > T3)

This is critical for safety:
- Alice must redeem before T3 expires
- After Alice reveals S, Carol has (T2 − T3) seconds to act
- After Carol acts, Bob has (T1 − T2) seconds to act

If Alice never reveals S, all three contracts expire and refund automatically.
**No rational party can lose funds.**
