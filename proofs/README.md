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
| Prop. 1: a step of SONAR takes `O(n)` operations | `Sonar.Step.sonarStep_linear`, `sonarInitStep_linear`, `sonarStep_val`, `sonarInitStep_val`, `rule_spec` | `Sonar/Complexity.lean` |
| Prop. 2: soundness and completeness of SONAR | `Sonar.RepairProcess.g_step_le`, `terminates`, `terminates_within` | `Sonar/Completeness.lean` |
| Prop. 3: an optimal search policy exists | `Sonar.SearchModel.exists_optimal_policy` | `Sonar/Existence.lean` |
| Prop. 4: NP-hardness (correctness of the reduction from min-sum set cover) | `Sonar.MinSumSetCover.Instance.extract_optimal`, `searchCost_optimal_eq` | `Sonar/Reduction.lean` |

## Scope

- **Prop. 1.** The step is written in a cost monad (`Counted`, one operation per comparison or
  arithmetic operation) and proved to compute rules (6a)–(6c) and the belief update or
  initialization exactly, in at most `10 n + 2 r_t + 13` operations (`7 n + 12` when the belief is
  re-initialized). `rule_spec` proves that the closed form of `r_t` is the minimum in (6c). The
  decision model, the verifier and the scores `f_1`, `f_2` are outside the count, as in the paper.
- **Prop. 2.** The repair process is abstract (`RepairProcess`): any selection rule that picks at
  least `minBatch ≥ 1` unverified candidates per step (or all that remain), and adopts only repairs
  verified to reduce `g_Φ`. So the result holds for every belief and decision model. The paper's
  condition `r_t ≥ 1` is `minBatch = 1`, which also covers batches cut short by an exhausted region.
  After an adoption the search space is regenerated. Proves (a), a valid repair within
  `⌈N_t / minBatch⌉` steps (`valid_repair_within`), and (b), with the bound
  `g_Φ(c_1) ⌈N / minBatch⌉` when every search space has at most `N` candidates.
- **Prop. 3.** The search model of Section 3 is abstract (`SearchModel`): states hold the belief and
  the configuration, decisions are `(i_t, r_t)` with `r_t ≤ r_max`, and the search has finitely many
  outcomes. Assumes one finite type of regions for all configurations (the paper's regions
  `{1, ..., n_{c_t}}` embed in it when `n_c` is bounded). The cost is objective (5). The proof
  follows the paper: Tychonoff and lower semicontinuity of `J = sup_T J_T`.
- **Prop. 4.** Proves the reduction correct: orderings and policies map to each other, with search
  costs `1 + κ` times the ordering costs, so an optimal policy yields an optimal ordering. Uses
  `λ ≥ (1 + κ) n`, which the paper's `λ > (1 + κ) |U| n` implies. Cited, not formalized: NP-hardness
  of min-sum set cover [Feige, Lovász, Tetali 2004] and the polynomial running time of the reduction.
