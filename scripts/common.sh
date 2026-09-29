# Sourced by the other scripts: sets root and finds a JDK to launch Gradle when none is configured.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && ! java -version >/dev/null 2>&1; then
  for candidate in /opt/homebrew/opt/openjdk /usr/local/opt/openjdk; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
