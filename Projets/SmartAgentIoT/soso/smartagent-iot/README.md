# SmartAgent-IoT : IFT605/IFT713

Plateforme domotique multi-agents : capteurs simulés → **MQTT (Mosquitto)** → agents de pièces
**Java RMI** → coordinateur central, avec **openHAB 4** comme tableau de bord et moteur de règles.

> **Avant de remettre ce projet** : l'énoncé donne 0 à du code « généré sans compréhension et sans
> commentaires explicatifs ». Lisez chaque classe, relancez-la, cassez-la volontairement, et
> reformulez les commentaires avec vos mots. Le fichier `docs/plan-rapport.md` liste les questions
> que l'enseignante peut poser.

## 1. Prérequis

| Outil | Version | Vérification |
|---|---|---|
| Java (JDK) | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Docker + Docker Compose | récent | `docker compose version` |

Scripts `.sh` : Linux, macOS, WSL ou Git Bash sous Windows. Sans bash, voir « §6 Lancement manuel ».

## 2. Contenu

```
smartagent-iot/
├── pom.xml                     POM parent (3 modules)
├── sensor-simulator/           Tâche 1.1 : CapteurSimulateur (publie sur MQTT)
├── agent-piece/                Tâches 2.1 et 2.2 : IAgentPiece, AgentPiece, EtatPiece, registre RMI
├── coordinateur/               Tâches 2.3, 3.1 et 3.3 : CoordinateurBatiment, client REST openHAB, tests
├── conf-openhab/               Tâche 1.2 : things / items / rules / sitemap
├── docker-compose.yml          Mosquitto + openHAB
├── docker/mosquitto.conf
├── scripts/                    build, start-all, stop-all, demo
└── docs/plan-rapport.md        plan du rapport + questions probables
```

## 3. Démarrage pas à pas

### Étape 1 : compiler et tester
```bash
./scripts/build.sh          # équivaut à : mvn clean package
```
Doit se terminer par `BUILD SUCCESS` et lister trois jars `*-all.jar`. Les tests JUnit (15) s'exécutent
automatiquement pendant le build.

### Étape 2 : lancer Mosquitto et openHAB
```bash
docker compose up -d
docker compose logs -f openhab      # Ctrl+C pour quitter les logs
```
Le premier démarrage d'openHAB prend 2 à 5 minutes (téléchargements, initialisation).

### Étape 3 : configurer openHAB (une seule fois)
1. Ouvrir <http://localhost:8080> et créer le compte administrateur.
2. **Settings → Add-ons → Bindings → MQTT Binding → Install.**
3. Attendre ~30 s : les fichiers de `conf-openhab/` sont chargés automatiquement.
4. **Settings → Things** : le bridge « mosquitto » et les trois Things (Salle A, Salle B, Cuisine) doivent
   être **ONLINE**. Si le Thing reste en erreur, ouvrir `docker compose logs openhab` : un message
   d'erreur de syntaxe dans `.things`, `.items` ou `.rules` y est affiché avec le numéro de ligne.
5. Tableau de bord : <http://localhost:8080/basicui/app?sitemap=batiment>

### Étape 4 : lancer la plateforme Java
```bash
./scripts/start-all.sh      # registre RMI + 3 agents + simulateur + coordinateur
tail -f logs/coordinateur.log
```
Arrêt : `./scripts/stop-all.sh`.

### Étape 5 : vérifier chaque couche (captures d'écran pour le rapport)

| Couche | Commande | Résultat attendu |
|---|---|---|
| MQTT (9 flux) | `./scripts/demo.sh ecouter` | des messages `batiment/<piece>/capteur/<type>` toutes les 5 s |
| openHAB | ouvrir le sitemap | températures, luminosités et occupations visibles |
| RMI | voir ci-dessous | état d'une pièce lu, consigne modifiée |
| Coordinateur | `tail -f logs/coordinateur.log` | une ligne par paire à chaque cycle de 5 s |

Client RMI de validation (semaine 2) :
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
| 3 | `./scripts/demo.sh vide salleA` | Occupation → OFF, la règle envoie 18 °C, la consigne s'affiche, puis la température dérive vers 18 |
| 4 | `./scripts/demo.sh ecart` | le log du coordinateur indique « écart 8.0 > 4.0 -> consigne 23.0 envoyée à salleA » ; le message apparaît sur `batiment/salleA/actuateur/thermostat` ; l'Item « Dernier équilibrage » se met à jour |
| 5 | `./scripts/demo.sh panne salleB` | après ~30 s : WARN « capteur en timeout » dans le log, `salleB=TIMEOUT` dans openHAB, Items de salleB à **UNDEF**, règle « capteur hors ligne » ; salleA et cuisine continuent |
| 5b | `./scripts/demo.sh reprise salleB` | retour à `salleB=OK` et valeurs revenues |
| 5c | (bonus) tuer le processus `agent-salleB` (voir `logs/pids.txt`) | `RemoteException` dans le log, `salleB=HORS_LIGNE`, le cycle continue ; relancer l'agent → retour à la normale |
| 6 | questions | voir `docs/plan-rapport.md` |

Conseil : répétez ce scénario au chronomètre, et gardez des captures d'écran comme plan B.

## 5. Choix de conception (à reprendre dans le rapport)

* **Un agent = une JVM** : une panne n'en affecte pas d'autres. Le **registre RMI est un processus
  séparé** (`RegistreRmi`) pour qu'arrêter un agent ne le détruise pas.
* **`EtatPiece` séparé d'`AgentPiece`** : l'état partagé (champs `volatile`) est testable sans réseau
  et l'horloge est injectable (timeout de 30 s testé sans attendre).
* **Politique d'équilibrage** : si écart > 4 °C, consigne = moyenne des deux températures envoyée à la
  pièce la plus chaude. Le seuil est strict (écart = 4,0 ne déclenche rien).
* **Tolérance aux pannes** : lecture de chaque agent dans son propre `try/catch RemoteException` ;
  lookup RMI paresseux (reconnexion naturelle) ; reconnexion MQTT automatique avec réabonnement ;
  tâche planifiée protégée par `catch (Throwable)`.
* **Écarts par rapport à l'énoncé** (à signaler dans le rapport) : (1) `getEtatJson()` est utilisé
  partout (le code de l'énoncé montrait `getEtat()`) ; (2) `isEnTimeout()` est ajoutée à l'interface
  pour que le coordinateur détecte la panne ; (3) dans openHAB 4 le broker est un `Bridge` ;
  (4) le simulateur écoute des topics de contrôle `batiment/{piece}/simulateur/#` pour rendre la
  démo reproductible.

## 6. Lancement manuel (sans scripts, ex. Windows PowerShell)

Un terminal par commande, depuis la racine du projet :
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
| `Port already in use` (2199) | Un ancien registre tourne encore : `./scripts/stop-all.sh`, ou fermer les JVM restantes (`jps`). |
| Coordinateur : `[openHAB] indisponible` | Normal si openHAB n'est pas prêt ; le coordinateur continue. Si l'API répond 401, créer un jeton dans le profil openHAB et définir `OPENHAB_TOKEN`. |
| Things openHAB « UNINITIALIZED / OFFLINE » | MQTT Binding non installé, ou Mosquitto injoignable. Voir `docker compose logs openhab`. |
| Les Items ne passent jamais à UNDEF | Le timer « expire » démarre à la première mise à jour reçue ; attendre 30 s après l'arrêt des capteurs. |
| Accents affichés `?` dans la console | Encodage du terminal ; purement cosmétique (Windows : `chcp 65001`). |
| Sous Linux, fichiers de `conf-openhab/` non modifiables | Décommenter `USER_ID` / `GROUP_ID` dans `docker-compose.yml`, ou `sudo chown -R $USER conf-openhab`. |

## 8. Ce qui a été vérifié, et ce qui ne l'a pas été

Vérifié dans l'environnement de génération : compilation de tous les modules en Java 21 ; 15 tests JUnit
réussis ; démarrage du registre RMI, de 3 agents, du client RMI et du coordinateur (lecture, appel de
consigne, `RemoteException` après arrêt brutal d'un agent, coordinateur qui continue, openHAB
injoignable toléré).

**Non vérifié ici** (pas d'accès à Maven Central, ni à Docker, ni à un broker) : le build Maven complet
avec la vraie bibliothèque Paho, la communication réelle avec Mosquitto, et le chargement des
fichiers `.things`/`.items`/`.rules` dans openHAB. Le code MQTT a été compilé contre une réplique
de l'API Paho 1.2.5. Si une erreur apparaît à ces étapes, c'est l'endroit à regarder en premier.
