# SmartAgent-IoT — IFT605 / IFT713 (SRMA)

Projet 1 du cours *Systèmes répartis et multiagents* (Université de Sherbrooke, automne 2026).

Plateforme domotique multi-agents : des **capteurs simulés** publient sur **MQTT (Mosquitto)**,
un **agent Java RMI par pièce** reçoit les mesures et expose son état, et un **coordinateur central**
équilibre les températures entre pièces adjacentes. **openHAB 4** sert de tableau de bord et de
moteur de règles.

```
capteurs simulés ─MQTT→ Mosquitto ─MQTT→ agents de pièce ─RMI→ coordinateur ─REST→ openHAB
                                              ↑ RMI ↓ (consultation agent↔agent)
```

## Prérequis

| Outil                   | Version |
| ----------------------- | ------- |
| JDK                     | 21      |
| Maven                   | 3.9+    |
| Docker + Docker Compose | récent |

## Démarrage rapide

```bash
# 1. Infrastructure (broker MQTT + openHAB 4)
docker compose up -d

# 2. Compiler + tester + produire les jars shadés
./scripts/build.sh

# 3. Lancer registre RMI + 3 agents + simulateur + coordinateur
./scripts/start-all.sh
tail -f logs/coordinateur.log      # suivre les décisions d'équilibrage

# 4. Tableau de bord openHAB
#    http://localhost:8080  (sitemap « batiment »)

# 5. Arrêter la partie Java  (puis « docker compose down » pour l'infra)
./scripts/stop-all.sh
```

## Lancement sous Windows

Le projet est **portable** : le `docker-compose.yml` publie les ports (pas de `network_mode: host`,
qui est propre à Linux), donc l'infrastructure démarre identiquement sous Windows (Docker Desktop).

**Prérequis Windows :** Docker Desktop (backend WSL2), JDK 21, Maven, et **Git for Windows**
(fournit *Git Bash*, pour exécuter les scripts `.sh`).

```bash
# Dans Git Bash (clic droit dans le dossier -> « Git Bash Here ») :
docker compose up -d          # fonctionne aussi en PowerShell / cmd
./scripts/build.sh
./scripts/start-all.sh
# ... http://localhost:8080  ...
./scripts/stop-all.sh
```

**Sans Git Bash (PowerShell/cmd uniquement)** — les scripts `.sh` ne tournent pas, mais Java est
multiplateforme : on lance chaque processus à la main (une fenêtre par commande).

```powershell
docker compose up -d
mvn clean package

# Registre RMI
java -cp agent-piece/target/agent-piece.jar ca.udes.ift605.smartagent.agent.RegistreRmiMain
# 3 agents (une fenêtre chacun)
java -jar agent-piece/target/agent-piece.jar salleA
java -jar agent-piece/target/agent-piece.jar salleB
java -jar agent-piece/target/agent-piece.jar cuisine
# Simulateur (les 3 pièces) et coordinateur
java -jar sensor-simulator/target/sensor-simulator.jar
java -jar coordinateur/target/coordinateur.jar
```

Pour la démo en PowerShell, les commandes `scripts/demo.sh` se traduisent par un
`docker exec smartagent-mosquitto mosquitto_pub ...` équivalent (voir le contenu du script).

## Démonstration pilotée (`scripts/demo.sh`)

Le simulateur fonctionne en **boucle fermée** : quand le coordinateur envoie une consigne,
la température simulée dérive vers elle — l'effet des décisions est donc visible de bout en bout.

```bash
./scripts/demo.sh ecart              # force un écart > 4 °C  -> rééquilibrage
./scripts/demo.sh vide salleA        # pièce vide    -> règle openHAB économie d'énergie
./scripts/demo.sh occupee salleA     # pièce occupée -> règle openHAB confort
./scripts/demo.sh panne salleB       # coupe les capteurs -> timeout (tolérance aux pannes)
./scripts/demo.sh reprise salleB     # relance les capteurs
./scripts/demo.sh etat Batiment_Coordination   # lit un Item openHAB via REST
```

## Structure

```
smartagent-iot/
├── pom.xml                 POM parent (3 modules)
├── sensor-simulator/       CapteurSimulateur : publie sur MQTT + boucle fermée
├── agent-piece/            IAgentPiece, AgentPiece (RMI+MQTT), registre RMI dédié
├── coordinateur/           CoordinateurBatiment, client REST openHAB, tests JUnit
├── openhab/conf/           things / items / rules / sitemap
├── mosquitto/config/       mosquitto.conf
├── docker-compose.yml      Mosquitto + openHAB 4
├── scripts/                build, start-all, stop-all, demo
├── diagrams/               architecture, MQTT vs RMI
├── DEMO_RUNSHEET.md         script de la démonstration
└── rapport/                 rapport (.docx + .pdf)
```

## Documentation

- **[DEMO_RUNSHEET.md](DEMO_RUNSHEET.md)** — déroulé de la démonstration.
- **[rapport/](rapport/)** — rapport et addendum semaines 2–3.
