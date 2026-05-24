#!/bin/bash
# SessionStart hook — bootstrap the miniforge toolchain in remote cloud sessions.
#
# DRAFT: staged from a container where repo.clojars.org was blocked, so the
# dependency-warming + lint/test steps could not be validated here. Verify and
# adjust on the first cloud session that has Clojars reachable (Full network or
# a Custom allowlist including repo.clojars.org).
#
# Why this exists: a fresh cloud VM ships Java/Node but NOT babashka, the
# Clojure CLI, clj-kondo, or Polylith, and `bb bootstrap` only auto-installs
# those on macOS/Windows (on Linux it just prints manual hints). So we install
# them directly here. Every installer is curl-based and trusts the system CA,
# which avoids the Java/GitHub-release TLS validation failure seen with
# deps.clj's built-in downloader behind the security proxy.
set -uo pipefail

# Remote-only: never touch a developer's local machine.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

log() { printf '[session-start] %s\n' "$*"; }
BIN_DIR="/usr/local/bin"

# 1. babashka — the `bb` task runner and miniforge's primary entrypoint.
if ! command -v bb >/dev/null 2>&1; then
  log "installing babashka"
  curl -fsSL https://raw.githubusercontent.com/babashka/babashka/master/install -o /tmp/bb-install \
    && bash /tmp/bb-install --dir "$BIN_DIR" \
    || log "WARN: babashka install failed"
else
  log "babashka present: $(bb --version)"
fi

# 2. Clojure CLI — required to launch the JVM with a resolved classpath
#    (tests, poly, mf). The linux-install script fetches clojure-tools via curl.
if ! command -v clojure >/dev/null 2>&1; then
  log "installing Clojure CLI"
  curl -fsSL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh -o /tmp/clj-install \
    && chmod +x /tmp/clj-install \
    && /tmp/clj-install --prefix /usr/local \
    || log "WARN: Clojure CLI install failed"
else
  log "Clojure CLI present"
fi

# 3. clj-kondo — lint gate. Best-effort; non-fatal.
if ! command -v clj-kondo >/dev/null 2>&1; then
  log "installing clj-kondo"
  curl -fsSL https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo -o /tmp/install-clj-kondo \
    && chmod +x /tmp/install-clj-kondo \
    && /tmp/install-clj-kondo --dir "$BIN_DIR" \
    || log "WARN: clj-kondo install failed (lint gate may be unavailable)"
fi

# 4. Polylith `poly` CLI — workspace checks. Best-effort; non-fatal.
#    Release assets are versioned (poly-<ver>.jar), so resolve the latest tag
#    via the releases/latest redirect rather than a fixed URL.
if ! command -v poly >/dev/null 2>&1; then
  log "installing Polylith poly CLI"
  POLY_JAR="/usr/local/lib/polylith/poly.jar"
  mkdir -p "$(dirname "$POLY_JAR")"
  POLY_VER="$(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/polyfy/polylith/releases/latest | sed -E 's@.*/tag/v?@@')"
  if [ -n "$POLY_VER" ] && curl -fsSL "https://github.com/polyfy/polylith/releases/download/v${POLY_VER}/poly-${POLY_VER}.jar" -o "$POLY_JAR"; then
    cat > "$BIN_DIR/poly" <<'POLY'
#!/bin/bash
exec java -jar /usr/local/lib/polylith/poly.jar "$@"
POLY
    chmod +x "$BIN_DIR/poly"
  else
    log "WARN: poly install failed (polylith check may be unavailable)"
  fi
fi

# 5. markdownlint — docs lint. npm is preinstalled in cloud VMs. Best-effort.
if ! command -v markdownlint >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
  log "installing markdownlint-cli"
  npm install -g markdownlint-cli >/dev/null 2>&1 || log "WARN: markdownlint install failed"
fi

# 6. Standards submodule — phase prompts read .standards/; absent submodule is a
#    soft failure (clone may already be initialized).
if [ -f .gitmodules ] && [ ! -e .standards/README.md ]; then
  log "initializing .standards submodule"
  git submodule update --init .standards || log "WARN: .standards submodule init failed"
fi

# 7. Warm the dependency cache so the first real command isn't a cold download.
#    Pulls from Maven Central + Clojars; requires Clojars reachable.
if command -v bb >/dev/null 2>&1; then
  log "warming dependency cache (bb tasks)"
  bb tasks >/dev/null 2>&1 || log "WARN: dependency warm failed — is repo.clojars.org allowed in this environment's network policy?"
fi

log "bootstrap complete"
