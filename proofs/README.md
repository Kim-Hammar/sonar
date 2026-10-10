# Lean 4 Formalization

A Lean 4 formalization of the theoretical results of the SONAR paper.

## Toolchain

- Lean `v4.31.0`, Mathlib `v4.31.0` (pinned in `lean-toolchain` and `lake-manifest.json`).
- Install [`elan`](https://github.com/leanprover/elan): `brew install elan-init`
  (or `curl -sSf https://elan.lean-lang.org/elan-init.sh | sh`).

## Build and check

```bash
cd proofs
lake exe cache get   # download the prebuilt Mathlib cache (avoids compiling Mathlib)
lake build           # build the Sonar library, which checks every proof
./check.sh           # also fails on sorry/admit/axiom and prints each theorem's axioms
```

`check.sh` runs in CI on every push (`.github/workflows/proofs.yml`).

## Formalizations

| Paper | Lean | Module |
|---|---|---|
| Proposition 1 (existence of an optimal search policy) | `Sonar.SearchModel.exists_optimal_policy` | `Sonar/Existence.lean` |
| Proposition 2 (NP-hardness): correctness of the reduction from min-sum set cover | `Sonar.MinSumSetCover.Instance.extract_optimal`, `searchCost_optimal_eq` | `Sonar/Reduction.lean` |
| Proposition 3 (a step of SONAR is `O(n)`) | `Sonar.Step.sonarStep_linear`, `sonarStep_ops_le`, `sonarStep_val` | `Sonar/Complexity.lean` |

## Coverage

What is formalized and what is assumed, statement by statement, is noted here.

**Proposition 1.** The search model of Section 3 is formalized abstractly (`SearchModel`): a
search state holds the belief `b_t` and the configuration `c_t`, with `g_Φ(c_t)` violations; a
decision is a region and a number of candidates `(x_t, r_t)`; verification has finitely many
outcomes, with given probabilities and successor states; and only valid repairs are applied, so
`g_Φ` never increases. The cost of a policy is the objective (1) with the stopping time (2),
defined as the supremum over `T` of the expected cost of the first `T` steps. Assumed: finitely
many regions (`[Finite X]`, at least one) and finitely many verification outcomes per step
(`[Fintype O]`). Not modeled: how the belief is updated and how candidates are generated and
verified; these are abstracted into the outcome probabilities and successor states. The theorem
holds for every such model, and its proof follows the paper's (truncation at
`⌈λ g_Φ(c_1)⌉`, compactness by Tychonoff, lower semicontinuity of `J = sup_T J_T`).

**Proposition 2.** Formalized: the reduction and its correctness. From a min-sum set cover (MSSC)
instance with `n` sets covering `U`, the module builds the search problem of the proof as a
`SearchModel` (one region per set, one equally likely cause per element, `g_Φ(c_1) = 1`) and
proves (a) every ordering yields a policy whose expected cost is at most the ordering's MSSC cost
divided by `|U|` (`searchCost_policyOf_le`); (b) every policy yields an ordering, read off its
search while the network is not repaired, whose MSSC cost is at most `|U|` times the policy's
expected cost (`mssc_extract_le`); hence the ordering extracted from an optimal policy is an
optimal MSSC ordering (`extract_optimal`), and the optimal expected cost is the optimal MSSC cost
divided by `|U|` (`searchCost_optimal_eq`). The formalization uses `λ ≥ n` (the number of sets)
rather than the paper's `λ > |U|`; both are polynomial in the instance, and `λ ≥ n` makes (b) hold
for every policy. Not formalized (cited): that MSSC is NP-hard [Feige, Lovász, Tetali 2004,
Thm. 1], and that the construction and the extraction (simulating the policy for at most `n`
steps) run in polynomial time.

**Proposition 3.** Formalized: a step of SONAR, apart from invoking the agent and the verifier,
written as programs in a cost monad (`Counted`) that counts one operation per comparison,
arithmetic operation, logarithm, power or rounding (the unit-cost RAM model; indexing and
control flow are free). Each program is proved to compute the paper's formula and its operations
are bounded: one pass finds a region `x_t` of maximal belief and `b̄_t = max_{j ≠ x_t} b_t(j)`
(rule (5), `topTwo_spec`, at most `3n` operations); rule (6) is computed exactly as stated,
including `ln(b/0) = ∞` and the infinite threshold when `g_Φ(c_t) = 0` (`decide_val`, at most 11
operations); and the Bayesian update returns `b_{t+1}(i) = b_t(i) L_t(i) / Z` with `L_t` as in
Section 4.5 (`update_val`, `update_sum`, at most `7n + 3` operations). A whole step takes at most
`10 n + 14` operations (`sonarStep_ops_le`), hence `O(n)` (`sonarStep_linear`). Inputs, as in the
paper's proof: the agent's assessment `p_t`, `p_t(∅)` and the verification feedback `m_t`. Not
covered: the cost of the agent and the verifier, and of regenerating the search regions.
