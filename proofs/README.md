# Lean 4 Formalization

A Lean 4 formalization of the propositions of the SONAR paper.

## Build and check

Requires [`elan`](https://github.com/leanprover/elan) (`brew install elan-init`); Lean and Mathlib
are pinned to `v4.31.0`.

```bash
cd proofs
./check.sh   # fetches the Mathlib cache, builds, fails on sorry/admit/axiom, prints each theorem's axioms
```

CI runs `check.sh` on every push (`.github/workflows/proofs.yml`). `Audit.lean` lists the theorems
whose axioms are checked.

## Propositions

| Paper | Lean | Module |
|---|---|---|
| Prop. 1: an optimal search policy exists | `Sonar.SearchModel.exists_optimal_policy` | `Sonar/Existence.lean` |
| Prop. 2: NP-hardness (correctness of the reduction from min-sum set cover) | `Sonar.MinSumSetCover.Instance.extract_optimal`, `searchCost_optimal_eq` | `Sonar/Reduction.lean` |
| Prop. 3: a step of SONAR takes `O(n)` operations | `Sonar.Step.sonarStep_linear`, `sonarStep_val` | `Sonar/Complexity.lean` |

## Scope

- **Prop. 1.** The search model of Section 3 is abstract (`SearchModel`): states hold the belief
  and the configuration, decisions are `(x_t, r_t)`, verification has finitely many outcomes, and
  only valid repairs are applied. Assumes finitely many regions and outcomes. The proof follows
  the paper: truncation at `⌈λ g_Φ(c_1)⌉`, Tychonoff, lower semicontinuity of `J = sup_T J_T`.
- **Prop. 2.** Proves the reduction correct: orderings and policies map to each other with
  matching costs, so an optimal policy yields an optimal ordering. Uses `λ ≥ n` instead of
  `λ > |U|` (both polynomial). Cited, not formalized: NP-hardness of min-sum set cover
  [Feige, Lovász, Tetali 2004] and the polynomial running time of the reduction.
- **Prop. 3.** The step is written in a cost monad (`Counted`, one operation per comparison or
  arithmetic operation) and proved to compute rules (5)–(6) and the Bayesian update exactly, in at
  most `10 n + 14` operations. The agent and the verifier are outside the count, as in the paper.
