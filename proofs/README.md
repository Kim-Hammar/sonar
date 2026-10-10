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
| | | |

## Coverage

What is formalized and what is assumed, statement by statement, is noted here.
