#!/bin/bash

if [ -z "${BASH_VERSINFO:-}" ] || [ "${BASH_VERSINFO[0]}" -lt 4 ]; then
  if [ "$(uname -s)" = "Darwin" ]; then
    if command -v /opt/homebrew/bin/bash >/dev/null 2>&1; then
      exec /opt/homebrew/bin/bash "$0" "$@"
    fi
  fi
  echo "Error: Bash 4+ required."
  exit 1
fi

set -e

force_run=false
minecraft_run=false

for arg in "$@"; do
  case "$arg" in
    --force)
      force_run=true
      echo "Force mode enabled."
      ;;
    --minecraft)
      minecraft_run=true
      echo "Minecraft patches mode enabled."
      ;;
  esac
done

declare -A scheduled_fixup
declare -A scheduled_rebuild

has_changes() {
  local dir="$1"
  [ -d "$dir" ] || return 1
  (cd "$dir" && { ! git diff --quiet || ! git diff --cached --quiet; })
}

schedule() {
  local label="$1"
  local dir="$2"
  local fixup_task="$3"
  local rebuild_task="$4"

  if [ ! -d "$dir" ]; then
    echo "Skipping $label: directory '$dir' does not exist."
    return
  fi

  if $force_run || has_changes "$dir"; then
    echo "Changes detected in $label ($dir). Scheduling rebuild."
    scheduled_fixup["$fixup_task"]="true"
    scheduled_rebuild["$rebuild_task"]="true"
  else
    echo "No changes in $label ($dir)"
  fi
}

run_task() {
  local task="$1"
  echo "Running: ./gradlew $task"
  ./gradlew "$task" || echo "Task '$task' failed, continuing..."
}

run_if_fixup() {
  local task="$1"
  if [ "${scheduled_fixup[$task]}" = "true" ]; then
    run_task "$task"
  else
    echo "Skipping fixup: $task"
  fi
}

run_if_rebuild() {
  local task="$1"
  if [ "${scheduled_rebuild[$task]}" = "true" ]; then
    run_task "$task"
  else
    echo "Skipping rebuild: $task"
  fi
}

# --- API layers ---
schedule "Canvas API"  "./canvas-api/"   "fixupCanvasApiFilePatches"              "rebuildCanvasApiFilePatches"
schedule "Folia API"   "./folia-api/"    "fixupFoliaApiFilePatches"               "rebuildFoliaApiFilePatches"
schedule "Paper API"   "./paper-api/"    "fixupPaperApiFilePatches"               "rebuildPaperApiFilePatches"

# --- Server layers ---
schedule "Canvas Server"  "./canvas-server/"  "surf-canvas-server:fixupCanvasServerFilePatches"  "surf-canvas-server:rebuildCanvasServerFilePatches"
schedule "Folia Server"   "./folia-server/"   "surf-canvas-server:fixupFoliaServerFilePatches"   "surf-canvas-server:rebuildFoliaServerFilePatches"
schedule "Paper Server"   "./paper-server/"   "surf-canvas-server:fixupPaperServerFilePatches"   "surf-canvas-server:rebuildPaperServerFilePatches"

# --- Minecraft patches (explicit --minecraft flag or --force) ---
if $minecraft_run || $force_run; then
  echo "Scheduling Minecraft patches rebuild."
  scheduled_fixup["surf-canvas-server:fixupMinecraftSourcePatches"]="true"
  scheduled_fixup["surf-canvas-server:fixupMinecraftResourcePatches"]="true"
  scheduled_rebuild["surf-canvas-server:rebuildMinecraftFilePatches"]="true"
fi

# --- Canvas single-file patches (build.gradle.kts changes) ---
if $force_run \
   || ! git diff --quiet "./surf-canvas-server/build.gradle.kts" \
   || ! git diff --cached --quiet "./surf-canvas-server/build.gradle.kts" \
   || ! git diff --quiet "./surf-canvas-api/build.gradle.kts" \
   || ! git diff --cached --quiet "./surf-canvas-api/build.gradle.kts"; then
  echo "build.gradle.kts changes detected. Scheduling rebuildCanvasSingleFilePatches."
  scheduled_rebuild["rebuildCanvasSingleFilePatches"]="true"
fi

echo ""
echo "--- Running fixup tasks ---"
run_if_fixup "fixupCanvasApiFilePatches"
run_if_fixup "fixupFoliaApiFilePatches"
run_if_fixup "fixupPaperApiFilePatches"
run_if_fixup "surf-canvas-server:fixupCanvasServerFilePatches"
run_if_fixup "surf-canvas-server:fixupFoliaServerFilePatches"
run_if_fixup "surf-canvas-server:fixupPaperServerFilePatches"
run_if_fixup "surf-canvas-server:fixupMinecraftSourcePatches"
run_if_fixup "surf-canvas-server:fixupMinecraftResourcePatches"

echo ""
echo "--- Running rebuild tasks ---"
run_if_rebuild "rebuildCanvasApiFilePatches"
run_if_rebuild "rebuildFoliaApiFilePatches"
run_if_rebuild "rebuildPaperApiFilePatches"
run_if_rebuild "surf-canvas-server:rebuildCanvasServerFilePatches"
run_if_rebuild "surf-canvas-server:rebuildFoliaServerFilePatches"
run_if_rebuild "surf-canvas-server:rebuildPaperServerFilePatches"
run_if_rebuild "surf-canvas-server:rebuildMinecraftFilePatches"
run_if_rebuild "rebuildCanvasSingleFilePatches"

echo ""
echo "Done!"
