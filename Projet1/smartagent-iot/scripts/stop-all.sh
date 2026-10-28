#!/usr/bin/env bash
# Arrête les processus Java lancés par start-all.sh (registre, agents, simulateur, coordinateur).
# N'arrête PAS Mosquitto/openHAB : pour ça, utiliser « docker compose down ».
cd "$(dirname "$0")/.."
[ -f logs/pids.txt ] || { echo "Rien à arrêter (logs/pids.txt absent)."; exit 0; }
while read -r pid nom; do
  if kill "$pid" 2>/dev/null; then echo "  arrêté : $nom ($pid)"; fi
done < logs/pids.txt
rm -f logs/pids.txt
echo "Terminé."
