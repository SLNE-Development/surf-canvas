#!/usr/bin/env bash
# Human-in-the-loop loop for the Folia region split crash
# ("Thread failed main thread check: Async chunk retrieval" in RegionizedPlayerChunkLoader.sendUnloadChunkRaw).
#
# The only manual steps are joining the server with a client and typing one command. Everything else
# (geometry, waiting, teleport, trigger, verdict) is done by the RegionSplitReproducer plugin.
#
# Usage:
#   bash test-plugin/scripts/regionsplit-hitl.sh [path/to/server/logs/latest.log]
#
# Exit code 0 = crash reproduced (RED), 1 = not reproduced (GREEN or precondition failure).

set -euo pipefail

LOG="${1:-run/logs/latest.log}"
SIGNATURE='Thread failed main thread check: Async chunk retrieval'
HALT='A region-threading exception occurred, halting the server'

step() {
  printf '\n>>> %s\n' "$1"
  read -r -p "    [Enter when done] " _
}

capture() {
  local var="$1" question="$2" answer
  printf '\n>>> %s\n' "$question"
  read -r -p "    > " answer
  printf -v "$var" '%s' "$answer"
}

step "Start the server with regionsplit-reproducer-1.0.0.jar in plugins/ (e.g. ./gradlew :surf-canvas-server:runDevServer, or your test server). Wait for 'Done'."
step "Join with a client (creative or spectator is fine). Stand still in an open area and do NOT move again until told."
step "Run: /regionsplit run   (console alternative: regionsplit run <name>). Watch the console until it prints ARMED, FAILED or the server halts."
capture OBSERVED "What did the console show last? (crash / FAILED / still waiting)"

if [[ -f "$LOG" ]]; then
  if grep -q "$SIGNATURE" "$LOG"; then
    printf '\n--- Captured ---\nOBSERVED=%s\nSIGNATURE_IN_LOG=yes\nHALTED=%s\n' "$OBSERVED" "$(grep -q "$HALT" "$LOG" && echo yes || echo no)"
    grep -n "\[REPRO\]" "$LOG" | tail -15
    exit 0
  fi
  printf '\n--- Captured ---\nOBSERVED=%s\nSIGNATURE_IN_LOG=no\n' "$OBSERVED"
  grep -n "\[REPRO\]" "$LOG" | tail -15 || true
  exit 1
fi

printf '\n--- Captured ---\nOBSERVED=%s\nSIGNATURE_IN_LOG=unknown (log %s not found)\n' "$OBSERVED" "$LOG"
exit 1
