#!/usr/bin/env bash
set -euo pipefail

# Change this to your LB URL or worker URL.
# For LB testing:
BASE_URL="http://localhost:8000"
# For direct worker testing, use:
# BASE_URL="http://localhost:8000"

SESSION_NAME="${SESSION_NAME:-nature-stress}"

command -v tmux >/dev/null 2>&1 || {
  echo "tmux is not installed."
  exit 1
}

# Kill any previous session with the same name.
tmux kill-session -t "$SESSION_NAME" 2>/dev/null || true

# Helper for pretty terminal titles.
pane_title() {
  local title="$1"
  printf '\033]0;%s\007' "$title"
}

# Pane 1: Fractals - small/medium/large but not wild jumps.
pane_fractals() {
  pane_title "fractals"
  while true; do
    curl -sS "${BASE_URL}/fractals?w=200&h=200&iterations=50" >/dev/null
    curl -sS "${BASE_URL}/fractals?w=250&h=250&iterations=60" >/dev/null
    curl -sS "${BASE_URL}/fractals?w=300&h=300&iterations=75" >/dev/null
  done
}

# Pane 2: DNA - moderate sequence lengths, same workload type, normal variation.
pane_dna() {
  pane_title "dna"
  seq_a_1="seq1:ATGCGTATGCGTATGCGTATGCGTATGCGT"
  seq_a_2="seq2:ATGCGTATGCGTATGCGTATGCGTATGCGT"
  seq_b_1="seq1:ATGCGTATGCGTATGCGTATGCGTATGCGTATGCGTATGC"
  seq_b_2="seq2:ATGCGTATGCGTATGCGTATGCGTATGCGTATGCGTATGC"
  seq_c_1="seq1:ATGCGTATGCGTATGCGTATGCGTATGCGTATGCGTATGCGTATGC"
  seq_c_2="seq2:ATGCGTATGCGTATGCGTATGCGTATGCGTATGCGTATGCGTATGC"

  while true; do
    curl -sS "${BASE_URL}/dna?seq1=$(printf '%s' "$seq_a_1" | sed 's/:/%3A/g')&seq2=$(printf '%s' "$seq_a_2" | sed 's/:/%3A/g')&minLength=4&stopOnFirst=false" >/dev/null
    curl -sS "${BASE_URL}/dna?seq1=$(printf '%s' "$seq_b_1" | sed 's/:/%3A/g')&seq2=$(printf '%s' "$seq_b_2" | sed 's/:/%3A/g')&minLength=5&stopOnFirst=false" >/dev/null
    curl -sS "${BASE_URL}/dna?seq1=$(printf '%s' "$seq_c_1" | sed 's/:/%3A/g')&seq2=$(printf '%s' "$seq_c_2" | sed 's/:/%3A/g')&minLength=6&stopOnFirst=true" >/dev/null
  done
}

# Pane 3: GrayScott - small/medium/large-ish, normal values only.
pane_grayscott() {
  pane_title "grayscott"
  while true; do
    curl -sS "${BASE_URL}/grayscott?size=128&maxIterations=1000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center" >/dev/null
    curl -sS "${BASE_URL}/grayscott?size=192&maxIterations=1500&f=0.028&k=0.060&stopOnExtinction=false&seedMode=ring" >/dev/null
    curl -sS "${BASE_URL}/grayscott?size=256&maxIterations=2000&f=0.031&k=0.063&stopOnExtinction=true&seedMode=stripe" >/dev/null
  done
}

# Pane 4: Mixed repeat traffic to test bucketing reuse.
pane_mixed_repeat() {
  pane_title "mixed-repeat"
  while true; do
    curl -sS "${BASE_URL}/fractals?w=250&h=250&iterations=60" >/dev/null
    curl -sS "${BASE_URL}/dna?seq1=seq1%3AATGCGTATGCGTATGC&seq2=seq2%3AATGCGTATGCGTATGC&minLength=4&stopOnFirst=false" >/dev/null
    curl -sS "${BASE_URL}/grayscott?size=192&maxIterations=1500&f=0.028&k=0.060&stopOnExtinction=false&seedMode=ring" >/dev/null
  done
}

# Pane 5: Health + light mixed traffic to keep the infrastructure busy.
pane_light_mix() {
  pane_title "light-mix"
  while true; do
    curl -sS "${BASE_URL}/health" >/dev/null || true
    curl -sS "${BASE_URL}/fractals?w=200&h=200&iterations=50" >/dev/null
    curl -sS "${BASE_URL}/dna?seq1=seq1%3AATGCATGCATGC&seq2=seq2%3AATGCATGCATGC&minLength=3&stopOnFirst=false" >/dev/null
  done
}
export BASE_URL
export -f pane_title
export -f pane_fractals
export -f pane_dna
export -f pane_grayscott
export -f pane_mixed_repeat
export -f pane_light_mix

# Create tmux session with 5 panes.
tmux new-session -d -s "$SESSION_NAME" "bash -lc 'pane_fractals'"

tmux split-window -h -t "$SESSION_NAME" "bash -lc 'pane_dna'"
tmux split-window -v -t "$SESSION_NAME":0.0 "bash -lc 'pane_grayscott'"
tmux split-window -v -t "$SESSION_NAME":0.1 "bash -lc 'pane_mixed_repeat'"
tmux split-window -h -t "$SESSION_NAME":0.0 "bash -lc 'pane_light_mix'"

tmux select-layout -t "$SESSION_NAME" tiled
tmux attach -t "$SESSION_NAME"
