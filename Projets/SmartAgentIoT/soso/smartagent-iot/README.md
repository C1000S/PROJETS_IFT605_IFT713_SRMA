# SmartAgent-IoT : IFT605/IFT713

Projet 1 du cours *Systèmes répartis et multiagents* (Université de Sherbrooke, automne 2026).


Plateforme domotique multi-agents : des capteurs simulés publient sur **MQTT (Mosquitto)**, un agent
**Java RMI** par pièce reçoit les mesures, et un coordinateur central équilibre les températures.
**openHAB 4** sert de tableau de bord et de moteur de règles.

```
capteurs simulés -> Mosquitto (MQTT) -> agents de pièces (RMI) -> coordinateur -> openHAB (REST)
```

## 1. Prérequis

| Outil | Version | Vérification |
|---|---|---|
| Java (JDK) | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Docker + Docker Compose | récent | `docker compose version` |

Les scripts `.sh` fonctionnent sous Linux, macOS, WSL ou Git Bash. Sous Windows PowerShell, voir
« 6. Lancement manuel ».

## 2. Contenu

```
smartagent-iot/
├── pom.xml                     POM parent (3 modules)
├── sensor-simulator/           CapteurSimulateur : publie sur MQTT
├── agent-piece/                IAgentPiece, AgentPiece, EtatPiece, registre RMI
├── coordinateur/               CoordinateurBatiment, client REST openHAB, ClientRmi, tests
├── conf-openhab/               things / items / rules / sitemap
├── docker-compose.yml          Mosquitto + openHAB
├── docker/mosquitto.conf
├── scripts/                    build, start-all, stop-all, demo
└── rapport/                    rapport LaTeX et PDF
```

## 3. Démarrage pas à pas

### Étape 1 : compiler et tester
```bash
./scripts/build.sh          # équivaut à : mvn clean package
```
Le build doit se terminer par `BUILD SUCCESS` et produire trois jars `*-all.jar`. Les 15 tests JUnit
s'exécutent pendant le build.

### Étape 2 : lancer Mosquitto et openHAB
```bash
docker compose up -d
docker compose logs -f openhab      # Ctrl+C pour quitter les logs
```
Le premier démarrage d'openHAB prend 2 à 5 minutes.

### Étape 3 : configurer openHAB (une seule fois)
1. Ouvrir <http://localhost:8080> et créer le compte administrateur.
2. **Settings -> Add-ons -> Bindings -> MQTT Binding -> Install.**
3. Attendre environ 30 s : les fichiers de `conf-openhab/` sont chargés automatiquement.
4. **Settings -> Things** : le bridge « mosquitto » et les trois Things (Salle A, Salle B, Cuisine)
   doivent être **ONLINE**. En cas d'erreur, `docker compose logs openhab` affiche l'erreur de syntaxe
   dans `.things`, `.items` ou `.rules` avec le numéro de ligne.
5. Tableau de bord : <http://localhost:8080/basicui/app?sitemap=batiment>

### Étape 4 : lancer la plateforme Java
```bash
./scripts/start-all.sh      # registre RMI + 3 agents + simulateur + coordinateur
tail -f logs/coordinateur.log
```
Arrêt : `./scripts/stop-all.sh`.

### Étape 5 : vérifier chaque couche

| Couche | Commande | Résultat attendu |
|---|---|---|
| MQTT (9 flux) | `./scripts/demo.sh ecouter` | des messages `batiment/<piece>/capteur/<type>` toutes les 5 s |
| openHAB | ouvrir le sitemap | températures, luminosités et occupations visibles |
| RMI | voir ci-dessous | état d'une pièce lu, consigne modifiée |
| Coordinateur | `tail -f logs/coordinateur.log` | une ligne par paire à chaque cycle de 5 s |

Client RMI de validation :
```bash
java -cp coordinateur/target/coordinateur-1.0.0-all.jar \
     ca.usherbrooke.ift605.coordinateur.ClientRmi salleA 19.5
```
Puis `./scripts/demo.sh ecouter "batiment/+/actuateur/#"` doit montrer la consigne 19.5 publiée.

## 4. Scénario de démonstration (10 min)

| # | Action | Résultat à montrer |
|---|---|---|
| 1 | `docker compose up -d`, `./scripts/start-all.sh` | tout démarre ; Things ONLINE |
| 2 | ouvrir le sitemap openHAB | mesures en temps réel |
| 3 | `./scripts/demo.sh vide salleA` | Occupation -> OFF, la règle envoie 18 °C, puis la température dérive vers 18 |
| 4 | `./scripts/demo.sh ecart` | le log du coordinateur indique « écart > 4.0 -> consigne envoyée » ; le message apparaît sur `batiment/salleA/actuateur/thermostat` ; l'Item « Dernier équilibrage » se met à jour |
| 5 | `./scripts/demo.sh panne salleB` | après environ 30 s : WARN « capteur en timeout », `salleB=TIMEOUT` dans openHAB, Items de salleB à **UNDEF** ; salleA et cuisine continuent |
| 5b | `./scripts/demo.sh reprise salleB` | retour à `salleB=OK` et valeurs revenues |
| 5c | tuer le processus `agent-salleB` | `RemoteException` dans le log, `salleB=HORS_LIGNE`, le cycle continue ; relancer l'agent -> retour à la normale |

## 5. Choix de conception

* **Un agent = une JVM** : une panne n'en affecte pas d'autres. Le **registre RMI est un processus
  séparé** (`RegistreRmi`) pour qu'arrêter un agent ne le détruise pas.
* **`EtatPiece` séparé d'`AgentPiece`** : l'état partagé (champs `volatile`) est testable sans réseau
  et l'horloge est injectable, ce qui permet de tester le timeout de 30 s sans attendre.
* **Politique d'équilibrage** : si l'écart dépasse 4 °C, la consigne est la moyenne des deux
  températures, envoyée à la pièce la plus chaude. Le seuil est strict (un écart de 4,0 ne déclenche rien).
* **Tolérance aux pannes** : lecture de chaque agent dans son propre `try/catch RemoteException` ;
  lookup RMI paresseux (reconnexion naturelle) ; reconnexion MQTT automatique avec réabonnement ;
  tâche planifiée protégée par `catch (Throwable)`.
* **Écarts par rapport à l'énoncé** : (1) `getEtatJson()` remplace `getEtat()` ; (2) `isEnTimeout()` est
  ajoutée à l'interface pour que le coordinateur détecte la panne ; (3) dans openHAB 4 le broker est un
  `Bridge` ; (4) le simulateur écoute des topics de contrôle `batiment/{piece}/simulateur/#` pour rendre
  la démonstration reproductible ; (5) `consulterVoisin` permet à un agent d'interroger un autre agent.

## 6. Lancement manuel (sans scripts, ex. Windows PowerShell)

Depuis la racine du projet, une commande par terminal :
```
java -cp agent-piece/target/agent-piece-1.0.0-all.jar ca.usherbrooke.ift605.agent.RegistreRmi
java -jar agent-piece/target/agent-piece-1.0.0-all.jar salleA
java -jar agent-piece/target/agent-piece-1.0.0-all.jar salleB
java -jar agent-piece/target/agent-piece-1.0.0-all.jar cuisine
java -jar sensor-simulator/target/sensor-simulator-1.0.0-all.jar
java -jar coordinateur/target/coordinateur-1.0.0-all.jar
```
Les commandes de `demo.sh` s'écrivent directement, par exemple :
```
docker exec smartagent-mosquitto mosquitto_pub -h localhost -t batiment/salleA/simulateur/occupation -m false
docker exec smartagent-mosquitto mosquitto_pub -h localhost -t batiment/salleB/simulateur/panne -m true
```

## 7. Dépannage

| Symptôme | Cause probable et solution |
|---|---|
| `mvn` : `release version 21 not supported` | JDK trop ancien : installer un JDK 21 et vérifier `JAVA_HOME`. |
| Les agents affichent `Unable to connect to server` | Mosquitto non démarré : `docker compose ps`. |
| `Port already in use` (2199) | Un ancien registre tourne encore : `./scripts/stop-all.sh`, ou fermer les JVM restantes. |
| Port 1883 déjà utilisé | Un Mosquitto installé sur la machine occupe le port : l'arrêter. |
| Coordinateur : `[openHAB] indisponible` | Normal si openHAB n'est pas prêt ; le coordinateur continue. Si l'API répond 401, créer un jeton dans le profil openHAB et définir `OPENHAB_TOKEN`. |
| Things openHAB « UNINITIALIZED / OFFLINE » | MQTT Binding non installé, ou Mosquitto injoignable. Voir `docker compose logs openhab`. |
| Les Items ne passent jamais à UNDEF | Le timer « expire » démarre à la première mise à jour reçue ; attendre 30 s après l'arrêt des capteurs. |
| Accents affichés `?` dans la console | Encodage du terminal, purement cosmétique (Windows : `chcp 65001`). |
| Docker Desktop ne démarre pas (Windows) | Activer « Virtual Machine Platform », lancer `wsl --update`, puis redémarrer. |

## 8. Ce qui a été vérifié

Sur nos machines (Windows 11, Docker Desktop, openHAB 4.3.0, Mosquitto 2) :

* build Maven complet : 15 tests JUnit réussis, `BUILD SUCCESS` ;
* 12 processus Java (6 programmes) et flux MQTT réel visibles dans openHAB ; Things ONLINE ;
* règles DSL openHAB (économie d'énergie, confort, alerte, capteur hors ligne, équilibrage signalé) ;
* intégration coordinateur -> openHAB par l'API REST ;
* rééquilibrage : écart de 4,1 °C -> consigne de 19,6 °C envoyée à la pièce la plus chaude ;
* panne de capteur : TIMEOUT, Item à UNDEF, puis reprise ; panne d'un agent : `RemoteException`,
  HORS_LIGNE, puis retour à OK après relance ;
* `consulterVoisin` et `ClientRmi`.

**Limites connues :** l'alerte de surchauffe (supérieure à 28 °C) n'a pas été déclenchée en conditions
réelles ; `consulterVoisin` et les scénarios réseau ne sont pas couverts par des tests JUnit
automatisés (tests manuels) ; la règle « pièce vide » d'openHAB et le coordinateur peuvent tous deux
écrire la consigne du thermostat, et le dernier écrit l'emporte.