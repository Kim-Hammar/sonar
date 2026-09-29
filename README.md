# sonar

SONAR: Search-Optimal Network Agentic Repair.

## Requirements

- A JDK 17 or newer to launch Gradle. Gradle downloads JDK 25, which builds and runs sonar.
- Node.js 22.12 or newer for the web interface.
- Docker, once, to extract the embedded Batfish jar.

## Commands

| Task | Command |
|---|---|
| Fetch the Batfish jar | `scripts/fetch-batfish.sh` |
| Build and run every check | `scripts/check.sh` |
| Run the Java tests | `scripts/test.sh` |
| Run the tests of one module | `scripts/test.sh sonar-core` |
| Format the Java code | `scripts/format.sh` |
| Coverage report | `./gradlew jacocoTestReport`, then open `<module>/build/reports/jacoco/test/html/index.html` |
| Show the command-line help | `scripts/run.sh --help` |
| Repair scenarios from the command line | `scripts/run.sh repair --dataset <dir> --scenario scenario-001 --output <dir>` |
| Build the web interface | `scripts/ui.sh build` |
| Start the web server | `scripts/web.sh --dataset <dir> --output <dir>`, then open `http://127.0.0.1:8080` |
| Try the interface without the engine | `scripts/web.sh --dataset <dir> --output <dir> --demo` |
| Develop the web interface with hot reload | `scripts/ui.sh dev` while the server runs, then open `http://127.0.0.1:5173` |
| Test, lint or format the web interface | `scripts/ui.sh test`, `scripts/ui.sh lint`, `scripts/ui.sh format` |

The server listens on `127.0.0.1:8080`; `--port` and `--host` change that. The decision model's
API key is read from `OPENROUTER_API_KEY` in the server's environment. Runs are stored under
`<output>/runs` and stay available after a restart; runs of `sonar repair` with the same output
directory appear in the interface too.

## Modules

| Module | Contents |
|---|---|
| `sonar-core` | Domain model, grammar and relation languages, repair generation, search, decisions, tracing, and parallel execution; depends on the JDK only |
| `sonar-batfish` | Data-plane verification with Batfish embedded in the process |
| `sonar-jev` | The Jev decision model over HTTP |
| `sonar-dataset` | Reading benchmark scenarios and specifications |
| `sonar-app` | Run settings, the assembled pipeline, and persistent run logs |
| `sonar-web` | The web server and API; the React interface is in `sonar-web/frontend` |
| `sonar-cli` | The `sonar` command with `repair` and `serve` |

The Cisco IOS dialect is defined in `sonar-core/src/main/resources/se/kth/sonar/dialect/`.
