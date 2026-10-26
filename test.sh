#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
cd "$root"
python3 -B scripts/sync.py check
python3 -B sample-suite/audit.py
mvn --batch-mode package -DskipTests
python3 -B sample-suite/run-suite.py java -jar "$root/target/lmcode-kotlin-sample.jar"
python3 -B sample-suite/test_runner.py
python3 -B scripts/check-bytecode.py
python3 -B scripts/check-note-bytes.py java -jar "$root/target/lmcode-kotlin-sample.jar"
