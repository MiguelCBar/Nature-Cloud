#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8000}"

mkdir -p /tmp/nature-cloud-tests

echo "Testing health..."
curl -sS "${BASE_URL}/health"
echo

echo "Testing fractals..."
curl -sS "${BASE_URL}/fractals?w=200&h=200&iterations=50" > /tmp/nature-cloud-tests/fractals.out
echo "Fractals response saved to /tmp/nature-cloud-tests/fractals.out"

echo "Testing DNA..."
curl -sS "${BASE_URL}/dna?seq1=seq1%3AATGCATGCATGCATGC&seq2=seq2%3ATTTATGCATGCAAA&minLength=4&stopOnFirst=false" > /tmp/nature-cloud-tests/dna.out
echo "DNA response saved to /tmp/nature-cloud-tests/dna.out"

echo "Testing GrayScott..."
curl -sS "${BASE_URL}/grayscott?size=64&maxIterations=20&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center" > /tmp/nature-cloud-tests/grayscott.out
echo "GrayScott response saved to /tmp/nature-cloud-tests/grayscott.out"

echo
echo "Latest metrics:"
if [ -f metrics-output/metrics.jsonl ]; then
    tail -n 10 metrics-output/metrics.jsonl
elif [ -f metrics.jsonl ]; then
    tail -n 10 metrics.jsonl
else
    echo "No metrics file found yet."
fi
