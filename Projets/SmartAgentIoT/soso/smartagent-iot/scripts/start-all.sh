#!/usr/bin/env bash
# Lance en arrière-plan : registre RMI, 3 agents, simulateur de capteurs, coordinateur.
# (Mosquitto et openHAB sont lancés séparément : docker compose up -d)
# Les journaux sont dans logs/*.log ; consulter-les avec : tail -f logs/coordinateur.log
set -euo pipefail
cd "$(dirname "$0")/.."

AGENT=agent-piece/target/agent-piece-1.0.0-all.jar
SIM=sensor-simulator/target/sensor-simulator-1.0.0-all.jar
COORD=coordinateur/target/coordinateur-1.0.0-all.jar
for f in "$AGENT" "$SIM" "$COORD"; do
  [ -f "$f" ] || { echo "Jar introuvable : $f. Lancez d'abord scripts/build.sh"; exit 1; }
done

mkdir -p logs
: > logs/pids.txt
lancer() {  # lancer <nom> <commande...>
  local nom=$1; shift
  nohup "$@" > "logs/$nom.log" 2>&1 &
  echo "$! $nom" >> logs/pids.txt
  echo "  démarré : $nom (pid $!)"
}

echo "Démarrage de la plateforme SmartAgent-IoT..."
lancer registre java -cp "$AGENT" ca.usherbrooke.ift605.agent.RegistreRmi
sleep 2
for p in salleA salleB cuisine; do lancer "agent-$p" java -jar "$AGENT" "$p"; done
sleep 2
lancer simulateur java -jar "$SIM"
sleep 1
lancer coordinateur java -jar "$COORD"
echo "Terminé. Suivre le coordinateur : tail -f logs/coordinateur.log"
