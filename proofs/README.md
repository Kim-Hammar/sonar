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
