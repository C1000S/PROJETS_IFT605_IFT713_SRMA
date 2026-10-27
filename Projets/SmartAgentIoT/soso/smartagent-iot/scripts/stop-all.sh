#!/usr/bin/env bash
# Arrête les processus lancés par start-all.sh.
cd "$(dirname "$0")/.."
[ -f logs/pids.txt ] || { echo "Rien à arrêter."; exit 0; }
while read -r pid nom; do
  if kill "$pid" 2>/dev/null; then echo "  arrêté : $nom ($pid)"; fi
done < logs/pids.txt
rm -f logs/pids.txt
