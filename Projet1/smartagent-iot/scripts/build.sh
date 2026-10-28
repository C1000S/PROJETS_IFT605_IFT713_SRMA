#!/usr/bin/env bash
# Compile les 3 modules, lance les tests JUnit et produit les jars exécutables (shadés).
# Jars attendus : <module>/target/<module>.jar (finalName fixé dans chaque pom.xml).
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B clean package
echo
echo "Jars produits :"
ls -1 sensor-simulator/target/sensor-simulator.jar \
      agent-piece/target/agent-piece.jar \
      coordinateur/target/coordinateur.jar
