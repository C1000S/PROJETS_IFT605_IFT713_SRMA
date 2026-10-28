# Fiche de démonstration — SmartAgent-IoT (barème /100)

> Runsheet prêt à dérouler le jour de la démo. Toutes les commandes ci-dessous
> ont été **exécutées et validées end-to-end le 2026-10-27** ; les sorties
> attendues sont des extraits réels capturés ce jour-là (voir § Preuves).
>
> Environnement validé : Ubuntu/Linux, Java 21, Maven 3.9, Docker 29 +
> Docker Compose v2, `eclipse-mosquitto:2`, `openhab/openhab:4.3.11-debian`.

---

## 0. Pré-vol (à faire AVANT la démo, pas devant le jury)

```bash
cd smartagent-iot

# 1. Infrastructure (broker + dashboard). openHAB met 1 à 3 min à booter.
docker compose up -d
#   Vérifier le broker :
mosquitto_pub -h localhost -t test/ping -m hello     # ne doit pas erreur
#   Vérifier openHAB (répéter jusqu'à HTTP 200, ~1-3 min au premier démarrage) :
curl -s -o /dev/null -w "openHAB HTTP %{http_code}\n" http://localhost:8080/rest/

# 2. Build + tests unitaires (doit finir sur BUILD SUCCESS, Tests run: 4)
mvn clean package
```

> ⚠️ **Piège de port** : si un broker Mosquitto (ou un openHAB) tourne déjà sur
> l'hôte (port 1883 / 8080 occupé), `docker compose up` boucle en
> `Restarting (1) Address in use`. Vérifier avec `ss -ltnp | grep -E ':1883|:8080'`
> et réutiliser l'instance déjà en place, ou l'arrêter avant.

---

## 1. MQTT — Pub/Sub (15 pts)

Lancer les **3 simulateurs dans des processus séparés** (obligatoire pour le
test de panne en § 6) :

```bash
java -jar sensor-simulator/target/sensor-simulator.jar salleA &
java -jar sensor-simulator/target/sensor-simulator.jar salleB &
java -jar sensor-simulator/target/sensor-simulator.jar cuisine &
```

Montrer les 9 flux (3 pièces × 3 mesures) + les topics retained :

```bash
mosquitto_sub -h localhost -t 'batiment/#' -v -W 6 | sort -u
```

Sortie attendue (valeurs variables) :

```
batiment/cuisine/capteur/luminosite 416
batiment/cuisine/capteur/occupation false
batiment/cuisine/capteur/temperature 17.0
batiment/salleA/capteur/luminosite 175
batiment/salleA/capteur/occupation true
batiment/salleA/capteur/temperature 19.3
batiment/salleB/capteur/...
```

Points à verbaliser : QoS 0 + `retained=true` sur les capteurs (un nouvel
abonné reçoit la dernière valeur immédiatement), topic `batiment/{piece}/capteur/{type}`.

---

## 2. openHAB — Things (10 pts)

Tableau de bord : [http://localhost:8080/basicui/app?sitemap=batiment](http://localhost:8080/basicui/app?sitemap=batiment)

Les 3 pièces doivent afficher des valeurs numériques (plus d'`UNDEF`) quelques
secondes après le lancement des simulateurs. Vérification REST :

```bash
curl -s "http://localhost:8080/rest/items?fields=name,state" | python3 -m json.tool
```

Montrer le Bridge `mqtt:broker:mosquitto` + 3 `mqtt:topic` enfants, chacun avec
4 canaux (temperature / luminosite / occupation / thermostat).

---

## 3. openHAB — Rules (10 pts)

**Règle d'économie d'énergie** (pièce vide → consigne 18 °C) — déclenchement
fiable en forçant l'occupation à OFF :

```bash
mosquitto_pub -h localhost -t 'batiment/salleA/capteur/occupation' -m 'false' -r
# puis vérifier :
curl -s http://localhost:8080/rest/items/SalleA_Thermostat/state    # -> 18
```

**Règle d'alerte** (température hors plage 15–27 °C) :

```bash
mosquitto_pub -h localhost -t 'batiment/salleA/capteur/temperature' -m '30.0' -r
# dans les logs openHAB : "ALERTE: SalleA hors plage (30.0 degC)"
docker logs smartagent-openhab --tail 20 2>&1 | grep SmartAgentIoT
```

---

## 4. Java RMI (20 pts)

Lancer les **3 agents dans des processus séparés** :

```bash
java -jar agent-piece/target/agent-piece.jar salleA &
java -jar agent-piece/target/agent-piece.jar salleB &
java -jar agent-piece/target/agent-piece.jar cuisine &
```

Au démarrage : un agent **crée** le registre (port 2199), les deux autres
**s'y connectent** (montrer les 3 lignes "enregistré sous 'agent-X'").

Appel de méthode distant depuis un **autre processus/JVM** (lecture + écriture
de consigne via l'interface `IAgentPiece` uniquement) :

```bash
java -cp agent-piece/target/agent-piece.jar \
  ca.udes.ift605.smartagent.agent.AgentPieceTestClient salleA 21.0
```

Sortie réelle validée :

```
=== Lecture de l'état de salleA via RMI ===
Température : 20.3
Luminosité  : 198
Occupée     : false
getEtatJson : {"enTimeout":false,"dernierMessage":"2026-10-27T22:04:42.186Z","piece":"salleA",...}
=== Envoi d'une nouvelle consigne (21.0) via RMI ===
Commande envoyée avec succès
```

Boucler la boucle RMI → MQTT → openHAB : la consigne 21.0 apparaît dans
`SalleA_Thermostat` du tableau de bord.

```bash
curl -s http://localhost:8080/rest/items/SalleA_Thermostat/state    # -> 21.0
```

---

## 5. Coordinateur (10 pts)

```bash
java -jar coordinateur/target/coordinateur.jar &
```

Les simulateurs gardent des températures proches (17–26 °C), donc **forcer un
déséquilibre > 4 °C** de façon déterministe (le simulateur réécrit la valeur
toutes les 5 s, on republie donc en boucle sur ~2 cycles de 10 s) :

```bash
for i in $(seq 1 18); do
  mosquitto_pub -h localhost -t 'batiment/salleA/capteur/temperature' -m '29.0' -r
  sleep 1.2
done
```

Sortie réelle validée dans le log du coordinateur :

```
[Coordinateur] DESEQUILIBRE salleA(29.0) / salleB(21.4) -> écart 7.6 > seuil 4.0 : consigne 25.2 envoyée à salleA
```

Point à verbaliser : la pièce **la plus chaude** reçoit comme consigne la
**moyenne** des deux températures (convergence douce, pas d'alignement brutal).

---

## 6. Tolérance aux pannes (10 pts)

1. **État avant panne** : les 3 pièces affichent des valeurs numériques.
2. **Couper UNE SEULE pièce** (le simulateur salleA) :

   ```bash
   # Retrouver le PID du simulateur salleA (PAS l'agent) :
   pgrep -fa 'sensor-simulator.jar salleA'
   kill <PID>
   ```

   > ⚠️ **Ne pas** faire `pkill -f 'sensor-simulator.jar salleA'` : le motif se
   > retrouve dans la propre ligne de commande du shell et `pkill` se tue
   > lui-même. Utiliser `kill <PID>` ciblé.
   >
3. **Attendre > 30 s**, puis montrer les 4 effets simultanés :

   ```bash
   # a) Agent : détection du timeout
   #    "[agent-salleA] TIMEOUT détecté : aucun message MQTT depuis plus de 30s..."
   # b) Coordinateur : "[Coordinateur] salleA en timeout (>30s) - utilisation de la dernière valeur connue"
   # c) openHAB : salleA passe à UNDEF (profil expire 30s)
   curl -s http://localhost:8080/rest/items/SalleA_Temperature/state   # -> UNDEF
   # d) salleB et cuisine : INCHANGÉES (toujours numériques)
   curl -s http://localhost:8080/rest/items/SalleB_Temperature/state   # -> valeur numérique
   ```
4. **Reprise** — relancer le simulateur salleA :

   ```bash
   java -jar sensor-simulator/target/sensor-simulator.jar salleA &
   ```

   Montrer : `"[agent-salleA] Reprise : messages MQTT reçus normalement à nouveau."`
   et `SalleA_Temperature` revenu à une valeur numérique.

---

## 7. Rapport PDF (15 pts) + Démonstration (10 pts)

- `rapport/Rapport_SmartAgentIoT.pdf` — vérifier que les placeholders (noms,
  matricules, date), les captures d'écran et la conclusion (§8) sont complétés
  **avant** la remise.
- La démonstration = ce runsheet déroulé proprement du § 1 au § 6.

---

## Annexe — Arrêt propre

```bash
# Arrêter les processus Java
pkill -f 'target/sensor-simulator.jar'
pkill -f 'target/agent-piece.jar'
pkill -f 'target/coordinateur.jar'
# Arrêter l'infra
docker compose down
```
