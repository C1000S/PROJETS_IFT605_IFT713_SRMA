#!/usr/bin/env bash
# Commandes de démonstration : elles publient sur MQTT via le conteneur Mosquitto
# (aucun client MQTT à installer sur la machine).
#
#   ./scripts/demo.sh vide [piece]            pièce vide  -> la règle openHAB baisse la consigne à 18
#   ./scripts/demo.sh occupee [piece]         pièce occupée -> consigne remontée à 21
#   ./scripts/demo.sh ecart                   force salleA=27 et salleB=19 (écart > 4 °C)
#   ./scripts/demo.sh temp <piece> <valeur>   force une température (15 à 30)
#   ./scripts/demo.sh panne [piece]           arrête les capteurs de la pièce (défaut : salleB)
#   ./scripts/demo.sh reprise [piece]         relance les capteurs de la pièce
#   ./scripts/demo.sh ecouter [filtre]        affiche les messages MQTT (défaut : batiment/#)
#   ./scripts/demo.sh etat <Item>             lit un Item openHAB via REST (ex. SalleA_Thermostat)
set -euo pipefail
C=smartagent-mosquitto
pub() { docker exec "$C" mosquitto_pub -h localhost -t "$1" -m "$2"; echo "publié : $1 = $2"; }

case "${1:-}" in
  vide)     pub "batiment/${2:-salleA}/simulateur/occupation" false ;;
  occupee)  pub "batiment/${2:-salleA}/simulateur/occupation" true ;;
  ecart)    pub batiment/salleA/simulateur/temperature 27; pub batiment/salleB/simulateur/temperature 19 ;;
  temp)     pub "batiment/$2/simulateur/temperature" "$3" ;;
  panne)    pub "batiment/${2:-salleB}/simulateur/panne" true ;;
  reprise)  pub "batiment/${2:-salleB}/simulateur/panne" false ;;
  ecouter)  docker exec -it "$C" mosquitto_sub -h localhost -t "${2:-batiment/#}" -v ;;
  etat)     curl -s "http://localhost:8080/rest/items/$2/state"; echo ;;
  *) sed -n '2,12p' "$0" ;;
esac
