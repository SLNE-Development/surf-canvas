#!/usr/bin/env bash
#
# Applies all patches. When one of surf-canvas' own file-patch layers fails to apply, that layer is
# re-applied with fuzzy matching, its patches are rebuilt and the full apply is retried.
#
# Feature patches (git am) and upstream (Canvas/Paper) layers have no fuzzy fallback; a failure
# there fails the script.
#
# Usage: apply-patches.sh <log-file> <fuzzy-report-file>
#   <log-file>           receives the Gradle output of every attempt
#   <fuzzy-report-file>  receives one line per layer that needed fuzzy matching
#
# Exit codes: 0 applied (possibly with fuzzy matching), 1 failed.

set -uo pipefail

LOG_FILE="${1:?log file required}"
FUZZY_REPORT="${2:?fuzzy report file required}"
GRADLE_ARGS=(--no-scan --stacktrace)

# Failing apply task -> "<fuzzy apply task> <rebuild task>"
declare -A FUZZY_LAYERS=(
  [applyCanvasSingleFilePatches]="applyCanvasSingleFilePatchesFuzzy rebuildCanvasSingleFilePatches"
  [applyCanvasApiFilePatches]="applyCanvasApiFilePatchesFuzzy rebuildCanvasApiFilePatches"
  [applyPaperApiFilePatches]="applyPaperApiFilePatchesFuzzy rebuildPaperApiFilePatches"
  [applyCanvasServerFilePatches]="applyCanvasServerFilePatchesFuzzy rebuildCanvasServerFilePatches"
  [applyPaperServerFilePatches]="applyPaperServerFilePatchesFuzzy rebuildPaperServerFilePatches"
  [applyMinecraftSourcePatches]="applyMinecraftSourcePatchesFuzzy rebuildMinecraftSourcePatches"
)

declare -A attempted=()
: > "$LOG_FILE"
: > "$FUZZY_REPORT"

gradle() {
  ./gradlew "$@" "${GRADLE_ARGS[@]}" 2>&1 | tee -a "$LOG_FILE"
  return "${PIPESTATUS[0]}"
}

for _ in $(seq 1 $(( ${#FUZZY_LAYERS[@]} + 1 ))); do
  attempt_start=$(wc -l < "$LOG_FILE")

  if gradle applyAllPatches; then
    exit 0
  fi

  failed_task=$(tail -n +"$(( attempt_start + 1 ))" "$LOG_FILE" \
    | grep -oE "Execution failed for task '[^']+'" \
    | head -n1 \
    | sed -E "s/.*'(.*)'/\1/; s/.*://")

  if [ -z "$failed_task" ] || [ -z "${FUZZY_LAYERS[$failed_task]:-}" ]; then
    echo "::error::applyAllPatches failed in '${failed_task:-unknown task}', which has no fuzzy fallback."
    exit 1
  fi

  if [ -n "${attempted[$failed_task]:-}" ]; then
    echo "::error::'$failed_task' still fails after fuzzy matching and rebuilding."
    exit 1
  fi
  attempted[$failed_task]=1

  read -r fuzzy_task rebuild_task <<< "${FUZZY_LAYERS[$failed_task]}"
  echo "::warning::'$failed_task' failed; retrying with '$fuzzy_task'."

  if [ "$failed_task" = "applyCanvasSingleFilePatches" ]; then
    rm -f surf-canvas-server/build.gradle.kts surf-canvas-api/build.gradle.kts
  fi

  if ! gradle "$fuzzy_task" || ! gradle "$rebuild_task"; then
    echo "::error::Fuzzy recovery via '$fuzzy_task' failed."
    exit 1
  fi

  echo "$failed_task" >> "$FUZZY_REPORT"
done

echo "::error::Giving up after too many apply attempts."
exit 1
