# SONAR

SONAR: (S)earch-(O)ptimal (N)etwork (A)utonomous (R)epair.

## Requirements

- A JDK 17+ to launch Gradle, which is used to build and run SONAR.
- Node.js 22.12+ for the web interface.
- Docker.

## Commands

| Task | Command |
|---|---|
| Fetch Batfish and the Cornetto dataset | `scripts/fetch-batfish.sh`, `scripts/fetch-dataset.sh` |
| Build and run every check | `scripts/check.sh` |
| Run the Java tests (of one module) | `scripts/test.sh [sonar-core]` |
| Format the Java code | `scripts/format.sh` |
| Repair scenarios | `scripts/run.sh repair --scenario scenario-001` (see `scripts/run.sh --help`) |
| Start the web interface | `scripts/web.sh` (`--demo` without the engine), then open `http://127.0.0.1:8080` |
| Develop the web interface | `scripts/ui.sh dev\|build\|test\|lint\|format` |
| Run a benchmark sweep | `scripts/benchmark.sh` (exports with `scripts/benchmark-export.py`) |
| Audit the dataset | `./gradlew :sonar-app:audit -Psonar.audit.scenarios=scenario-001` |
| Check the Lean proofs | `proofs/check.sh` (see `proofs/README.md`) |

The dataset is read from `dataset/main_dataset` and results are written to `output`
(`--dataset`, `--output`). The decision model's API key is read from `OPENROUTER_API_KEY`. The
audits are the `audit`-tagged tests of `sonar-app`; they also need the ground truth in
`../aros/results/ground_truth`.

