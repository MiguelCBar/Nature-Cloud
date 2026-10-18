#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${1:-.}"
PATTERN="${2:-req-*.sh}"

mapfile -t scripts < <(find "$ROOT_DIR" -type f -name "$PATTERN" | sort)

if [ "${#scripts[@]}" -eq 0 ]; then
  echo "No scripts found under '$ROOT_DIR' matching pattern '$PATTERN'"
  exit 1
fi

for script in "${scripts[@]}"; do
  echo "=================================================="
  echo "Running: $script"
  echo "=================================================="

  (
    cd "$(dirname "$script")"
    bash "$(basename "$script")"
  )

  echo
  echo "Finished: $script"
  echo
done
