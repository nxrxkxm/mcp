#!/bin/bash
# SessionStart hook: build the Salesforce Data 360 MCP server so the
# "data360" entry in .mcp.json has a runnable JAR in this remote container.
#
# Runs only in Claude Code on the web ($CLAUDE_CODE_REMOTE=true). It is
# idempotent: once the JAR is built it is cached with the container and the
# build is skipped on later sessions. Failures are logged but never block
# session start.
set -uo pipefail

# Only run in the remote (Claude Code on the web) environment.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
D360_DIR="$PROJECT_DIR/.data360"
SRC_DIR="$D360_DIR/d360-mcp-server"
JAR_DEST="$D360_DIR/data360-mcp-server.jar"
# Source ref and tarball URL. We download a tarball from codeload instead of
# `git clone`: some remote-env egress policies block the git smart-http
# endpoint (git-upload-pack -> 403) while still allowing codeload + Maven Central.
D360_REF="${DATA360_MCP_REF:-main}"
TARBALL_URL="https://codeload.github.com/forcedotcom/d360-mcp-server/tar.gz/refs/heads/${D360_REF}"

log() { echo "[data360] $*"; }

mkdir -p "$D360_DIR"

# Build prerequisites (this environment ships Java 21 + Maven 3.9).
if ! command -v java >/dev/null 2>&1; then
  log "ERROR: java not found (need Java 17+); skipping build" >&2
  exit 0
fi
if ! command -v mvn >/dev/null 2>&1; then
  log "ERROR: mvn not found (need Maven 3.9+); skipping build" >&2
  exit 0
fi

# Cached container: JAR already present -> nothing to do.
if [ -f "$JAR_DEST" ]; then
  log "JAR already present at $JAR_DEST; skipping build"
  exit 0
fi

# Fetch the Data 360 MCP server source as a tarball, falling back to git clone.
# Some proxy policies block codeload (CONNECT 403) while allowing github.com git
# over HTTPS — observed in this environment on 2026-07-15.
if [ -f "$SRC_DIR/pom.xml" ]; then
  log "Source already present in $SRC_DIR; skipping download"
else
  log "Downloading source tarball ($D360_REF)"
  rm -rf "$SRC_DIR"
  mkdir -p "$SRC_DIR"
  if ! curl -fsSL --max-time 180 "$TARBALL_URL" | tar -xz -C "$SRC_DIR" --strip-components=1; then
    log "tarball download failed; falling back to git clone"
    rm -rf "$SRC_DIR"
    if ! git clone --depth 1 --branch "$D360_REF" \
        https://github.com/forcedotcom/d360-mcp-server.git "$SRC_DIR"; then
      log "ERROR: source download failed (tarball and git clone); skipping build" >&2
      exit 0
    fi
  fi
fi

# Apply the repo-local overlay (i2message tools, catalog/test updates) on top of
# the upstream source before building. Files in d360-overlay/ replace their
# upstream counterparts wholesale, pinning them until upstreamed.
OVERLAY_DIR="$PROJECT_DIR/d360-overlay"
if [ -d "$OVERLAY_DIR/src" ]; then
  log "Applying d360-overlay sources"
  cp -r "$OVERLAY_DIR/src/." "$SRC_DIR/src/"
fi

# Build the runnable JAR (tests skipped for faster startup).
log "Building with Maven (this can take a few minutes on first run)"
if ! mvn -q -f "$SRC_DIR/pom.xml" -DskipTests package; then
  log "ERROR: maven build failed; skipping" >&2
  exit 0
fi

# Pick the runnable JAR (largest non-source/javadoc artifact in target/).
BUILT_JAR="$(ls -S "$SRC_DIR"/target/*.jar 2>/dev/null \
  | grep -vE 'original-|-sources|-javadoc' | head -1 || true)"
if [ -z "$BUILT_JAR" ]; then
  log "ERROR: build produced no usable jar in $SRC_DIR/target" >&2
  exit 0
fi

cp "$BUILT_JAR" "$JAR_DEST"
log "Installed JAR -> $JAR_DEST"
