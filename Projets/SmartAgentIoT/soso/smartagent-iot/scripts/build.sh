#!/usr/bin/env bash
# Compile les 3 modules, lance les tests JUnit et produit les jars exécutables (*-all.jar).
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B clean package
echo
echo "Jars produits :"
ls -1 sensor-simulator/target/*-all.jar agent-piece/target/*-all.jar coordinateur/target/*-all.jar
