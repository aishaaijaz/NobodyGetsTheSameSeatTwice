#!/usr/bin/env bash
# Usage: ./run.sh [seed]
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
javac -d out $(find src -name '*.java')
java -cp out tickets.AllTests "$@"
