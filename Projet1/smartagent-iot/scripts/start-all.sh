#!/usr/bin/env bash
# Lance toute la plateforme Java en arrière-plan, dans le bon ordre :
#   1. le registre RMI dédié (port 2199)
#   2. les 3 agents de pièce (salleA, salleB, cuisine) -- chacun sa JVM
#   3. le simulateur de capteurs (les 3 pièces)
#   4. le coordinateur
#
# Mosquitto et openHAB sont lancés séparément (infrastructure) :
#   docker compose up -d
#
# Les journaux vont dans logs/*.log. Suivre le coordinateur :
#   tail -f logs/coordinateur.log
# Arrêter : scripts/stop-all.sh
set -euo pipefail
cd "$(dirname "$0")/.."

AGENT=agent-piece/target/agent-piece.jar
SIM=sensor-simulator/target/sensor-simulator.jar
COORD=coordinateur/target/coordinateur.jar
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
lancer registre java -cp "$AGENT" ca.udes.ift605.smartagent.agent.RegistreRmiMain
sleep 2
for p in salleA salleB cuisine; do
  lancer "agent-$p" java -jar "$AGENT" "$p"
done
sleep 2
lancer simulateur java -jar "$SIM"
sleep 1
lancer coordinateur java -jar "$COORD"
echo "Terminé. Suivre le coordinateur : tail -f logs/coordinateur.log"
