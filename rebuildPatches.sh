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

for arg in "$@"; do
  case "$arg" in
    --force)
      force_run=true
      echo "Force mode enabled."
      ;;
  esac
done

declare -A gradle_tasks

process_changes() {
  local dir="$1"
  local project="$2"

  if [ ! -d "$dir" ]; then
    echo "Error: Directory '$dir' does not exist."
    exit 1
  fi

  cd "$dir"
  if $force_run || ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Changes detected in $dir. Scheduling rebuild."
    gradle_tasks["fixup${project}FilePatches"]="true"
    gradle_tasks["rebuild${project}FilePatches"]="true"
  else
    echo "No changes in $dir"
  fi
  cd - > /dev/null
}

run_gradle_task() {
  local task="$1"
  if [ "${gradle_tasks[$task]}" = "true" ]; then
    echo "Running: $task"
    ./gradlew "$task" -Dpaperweight.debug=true || echo "Task '$task' failed, continuing..."
  else
    echo "Skipping: $task (no changes)"
  fi
}

process_changes "./canvas-api/"    "CanvasApi"
process_changes "./canvas-server/" "CanvasServer"

gradle_rebuild_task=false
if $force_run \
   || ! git diff --quiet "./surf-canvas-server/build.gradle.kts" \
   || ! git diff --cached --quiet "./surf-canvas-server/build.gradle.kts" \
   || ! git diff --quiet "./surf-canvas-api/build.gradle.kts" \
   || ! git diff --cached --quiet "./surf-canvas-api/build.gradle.kts"; then
  gradle_rebuild_task=true
fi

if $gradle_rebuild_task; then
  gradle_tasks["rebuildCanvasSingleFilePatches"]="true"
fi

echo "--- Running fixup tasks ---"
run_gradle_task "fixupCanvasApiFilePatches"
run_gradle_task "fixupCanvasServerFilePatches"

echo "--- Running rebuild tasks ---"
run_gradle_task "rebuildCanvasApiFilePatches"
run_gradle_task "rebuildCanvasServerFilePatches"
run_gradle_task "rebuildCanvasSingleFilePatches"

echo "Done!"