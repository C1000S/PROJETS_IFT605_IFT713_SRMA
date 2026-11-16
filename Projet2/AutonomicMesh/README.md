# AutonomicMesh

**Middleware distribué auto-géré avec Java, gRPC, IPC et la boucle MAPE-K**

Projet réalisé dans le cadre du cours **IFT605 / IFT713 — Systèmes répartis et multi-agents**, à l'Université de Sherbrooke, Automne 2026.

## 1. Présentation du projet

AutonomicMesh est un système distribué composé de trois microservices Java qui communiquent à l'aide de gRPC. Un gestionnaire autonome surveille leur état et applique des actions correctives lorsqu'une panne ou une dégradation est détectée.

Le projet met en œuvre le modèle **MAPE-K** :

- **Monitor** : collecte les mesures de santé des microservices.
- **Analyze** : analyse les mesures et détecte les anomalies.
- **Plan** : choisit une action corrective selon le problème détecté.
- **Execute** : exécute l'action corrective.
- **Knowledge** : conserve les états des services et les informations de routage.

L'objectif est de démontrer comment un système distribué peut surveiller son fonctionnement et réagir automatiquement à certains incidents.

## 2. Architecture générale

Le système comprend trois microservices principaux et leurs instances de secours :

| Microservice | Port principal | Port de secours |
|---|---:|---:|
| CalcService | 50051 | 50054 |
| StorageService | 50052 | 50055 |
| NotifService | 50053 | 50056 |

Le gestionnaire autonome utilise également le port TCP **9090** pour recevoir les mesures transmises par les sondes.

### Cycle MAPE-K

Le gestionnaire exécute un cycle de supervision toutes les 10 secondes :

1. Les sondes vérifient la disponibilité des microservices à l'aide des contrôles de santé gRPC.
2. Elles transmettent les mesures au gestionnaire par communication IPC/TCP.
3. L'Analyzer identifie les problèmes.
4. Le Planner sélectionne une action corrective.
5. L'Executor applique l'action.
6. La Knowledge Base conserve les états et les informations de routage.

### Règles de détection

| Condition détectée | Symptôme |
|---|---|
| Service indisponible (`DOWN`) | `PANNE_TOTALE` |
| Latence supérieure à 500 ms | `DEGRADATION` |
| Charge CPU supérieure à 0,90 | `SURCHARGE` |

### Actions correctives

Le Planner associe les symptômes aux actions suivantes :

| Symptôme | Action |
|---|---|
| `PANNE_TOTALE` | `RESTART_SERVICE` |
| `DEGRADATION` | `REROUTE_TRAFFIC` |
| `SURCHARGE` | `REROUTE_TRAFFIC` |

Le redémarrage relance le processus du microservice concerné. Le réacheminement modifie la table de routage afin d'utiliser l'instance de secours correspondante.

Le fichier `logs/routes.properties` contient les ports utilisés par le client pour joindre les services.

## 3. Technologies utilisées

- **Java 21** : développement des microservices et du gestionnaire autonome.
- **Maven** : gestion des dépendances et construction du projet multi-modules.
- **gRPC** : communication entre les clients et les microservices.
- **Protocol Buffers** : définition des messages et des services RPC.
- **Sockets TCP / IPC** : transmission des mesures des sondes au gestionnaire.
- **MAPE-K** : supervision et gestion autonome.
- **PowerShell / Windows 11** : environnement de développement et d'exécution.

## 4. Structure du projet

```text
AutonomicMesh/
│
├── pom.xml
│
├── mesh-proto/
│   ├── pom.xml
│   └── src/main/proto/
│       └── mesh.proto
│
├── mesh-services/
│   ├── pom.xml
│   ├── calc-service/
│   │   └── src/main/java/.../calc/
│   │       ├── CalcServer.java
│   │       └── CalcServiceImpl.java
│   │
│   ├── storage-service/
│   │   └── src/main/java/.../storage/
│   │       ├── StorageServer.java
│   │       └── StorageServiceImpl.java
│   │
│   └── notif-service/
│       └── src/main/java/.../notif/
│           ├── NotifServer.java
│           └── NotifServiceImpl.java
│
├── mape-k/
│   └── src/main/java/.../mapek/
│       ├── analyzer/
│       │   └── Analyzer.java
│       ├── app/
│       │   └── MapeKApplication.java
│       ├── chaos/
│       │   ├── ChaosApplication.java
│       │   └── ChaosMonkey.java
│       ├── controller/
│       │   └── MapeKController.java
│       ├── executor/
│       │   └── Executor.java
│       ├── knowledge/
│       │   ├── KnowledgeBase.java
│       │   └── Measurement.java
│       ├── monitor/
│       │   ├── Monitor.java
│       │   └── Probe.java
│       ├── planner/
│       │   └── Planner.java
│       └── process/
│           └── ServiceProcessManager.java
│
├── chaos/
│   └── pom.xml
│
├── tests/
│   ├── pom.xml
│   └── src/main/java/.../tests/
│       └── MeshTestClient.java
│
└── logs/
    ├── routes.properties
    ├── CalcService.log
    ├── StorageService.log
    └── NotifService.log
```

## 5. Rôle des principaux fichiers

### Module `mesh-proto`

**`mesh.proto`**

Définit les services gRPC, les méthodes RPC et les structures de messages échangées entre les clients et les microservices. Les classes Java gRPC et Protocol Buffers sont générées lors de la construction Maven.

### Module `mesh-services`

Ce module contient les trois microservices.

**CalcService**
- `CalcServer.java` : démarre le serveur gRPC de calcul sur le port configuré.
- `CalcServiceImpl.java` : implémente les opérations de calcul exposées par gRPC.

**StorageService**
- `StorageServer.java` : démarre le serveur gRPC de stockage.
- `StorageServiceImpl.java` : implémente les opérations d'enregistrement et de récupération des données.

**NotifService**
- `NotifServer.java` : démarre le serveur gRPC de notification.
- `NotifServiceImpl.java` : implémente le traitement des demandes de notification.

Chaque serveur peut être démarré sur un port différent afin de permettre l'exécution d'une instance principale et d'une instance de secours.

### Module `mape-k`

**`MapeKApplication.java`**

Point d'entrée de l'application. Initialise la base de connaissances, le gestionnaire de processus, le Monitor, les sondes, le contrôleur MAPE-K et le mécanisme d'injection de pannes.

**`Monitor.java`**

Reçoit les mesures transmises par les sondes via le canal TCP/IPC et les rend disponibles pour le gestionnaire autonome.

**`Probe.java`**

Surveille un microservice, vérifie son état de santé gRPC et transmet ses mesures au Monitor.

**`Analyzer.java`**

Analyse les mesures reçues et identifie les symptômes : panne totale, dégradation ou surcharge.

**`Planner.java`**

Transforme les symptômes détectés en décisions correctives. Il ne réalise pas lui-même les actions.

**`Executor.java`**

Exécute les actions décidées par le Planner, notamment le redémarrage d'un service et le réacheminement du trafic.

**`KnowledgeBase.java`**

Conserve les mesures et les états des services ainsi que la table de routage. Elle permet notamment de modifier et de sauvegarder les ports utilisés pour joindre les instances de secours.

**`Measurement.java`**

Représente les mesures associées à un microservice, notamment son état, sa latence et sa charge CPU.

**`MapeKController.java`**

Orchestre les cycles MAPE-K périodiques et coordonne les étapes Analyze, Plan et Execute à partir des mesures disponibles.

**`ServiceProcessManager.java`**

Gère les processus des microservices : démarrage, arrêt et redémarrage des instances.

**`ChaosMonkey.java`**

Injecte des pannes contrôlées sur les processus des microservices afin de tester la capacité de détection et de récupération du système.

**`ChaosApplication.java`**

Point d'entrée associé au mécanisme de test de chaos.

### Module `tests`

**`MeshTestClient.java`**

Client de test gRPC. Il vérifie les fonctionnalités des trois microservices : calcul, stockage, notification et contrôle de santé. Il lit également la table de routage pour déterminer le port à utiliser.

## 6. Prérequis

Avant de démarrer le projet, installer :

- Java JDK 21.
- Apache Maven 3.10 ou une version compatible.
- Git.
- Un environnement Windows, Linux ou macOS adapté à la configuration des chemins et des processus.

Vérifier les installations :

```powershell
java -version
javac -version
mvn -version
git --version
```

## 7. Récupérer le projet

Cloner le dépôt :

```powershell
git clone URL_DU_DEPOT
cd AutonomicMesh
```

Remplacer `URL_DU_DEPOT` par l'adresse HTTPS ou SSH du dépôt GitHub.

## 8. Construire le projet

Depuis le dossier racine, exécuter :

```powershell
mvn clean package
```

Cette commande construit les modules Maven, génère les classes gRPC à partir de `mesh.proto`, compile les sources et exécute les étapes de construction configurées.

La construction est réussie lorsque Maven affiche :

```text
BUILD SUCCESS
```

## 9. Démarrer le système

Le point d'entrée principal est :

```text
ca.usherbrooke.autonomicmesh.mapek.app.MapeKApplication
```

Depuis le dossier racine, lancer :

```powershell
mvn -pl mape-k exec:java "-Dexec.mainClass=ca.usherbrooke.autonomicmesh.mapek.app.MapeKApplication"
```

L'application initialise le gestionnaire autonome et démarre les processus et les composants de supervision prévus par sa configuration.

**Important :** `ServiceProcessManager.java` doit utiliser les chemins locaux corrects vers le projet et Maven. Si les chemins sont configurés en dur, ils doivent être adaptés à l'environnement avant le lancement sur une autre machine.

Les ports utilisés sont :

| Composant | Port |
|---|---:|
| CalcService principal | 50051 |
| StorageService principal | 50052 |
| NotifService principal | 50053 |
| CalcService de secours | 50054 |
| StorageService de secours | 50055 |
| NotifService de secours | 50056 |
| Monitor | 9090 |

## 10. Exécuter les tests

Laisser le gestionnaire autonome fonctionner, puis ouvrir un deuxième terminal à la racine du projet.

Lancer :

```powershell
mvn -pl tests exec:java "-Dexec.mainClass=ca.usherbrooke.autonomicmesh.tests.MeshTestClient"
```

Le client effectue les tests suivants :

1. Test de calcul : addition de deux valeurs.
2. Test de stockage : enregistrement et récupération d'une donnée.
3. Test de notification : envoi d'une demande de notification.
4. Test de santé : vérification de l'état des trois microservices.

Le client affiche également les ports sélectionnés à partir de la table de routage.

## 11. Tester la résilience

Le mécanisme ChaosMonkey permet d'injecter des pannes contrôlées sur les microservices.

Le gestionnaire autonome doit ensuite :

1. Détecter l'indisponibilité ou la dégradation.
2. Identifier le symptôme avec l'Analyzer.
3. Choisir une action avec le Planner.
4. Exécuter l'action avec l'Executor.
5. Mettre à jour l'état du système et, en cas de réacheminement, la table de routage.

Exemples de comportements attendus :

- Une panne totale déclenche le redémarrage du processus concerné.
- Une dégradation ou une surcharge déclenche le réacheminement vers l'instance de secours.

Le fichier `logs/routes.properties` permet de vérifier les ports utilisés après une modification de routage.

## 12. Journaux et fichiers générés

Le dossier `logs/` contient les journaux d'exécution des microservices et le fichier de routage.

Exemple de table de routage :

```properties
CalcService=50054
StorageService=50052
NotifService=50053
```

Dans cet exemple, CalcService utilise son instance de secours sur le port 50054.

Les fichiers de compilation Maven (`target/`) sont générés automatiquement et ne sont pas nécessaires dans le dépôt GitHub.

## 13. Arrêter le système

Arrêter l'application principale avec `Ctrl + C` dans le terminal où `MapeKApplication` est exécutée.

L'application doit arrêter les composants et les processus qu'elle a démarrés lors de son arrêt normal.

## 14. Limites et points d'attention

- Les chemins vers Maven et le dossier du projet doivent être adaptés à chaque environnement si la configuration utilise des chemins absolus.
- Les instances de secours sont des processus distincts. Les données conservées uniquement en mémoire par un service ne sont pas nécessairement partagées avec son instance de secours.
- Les mesures de latence et de charge CPU peuvent varier selon la machine et les conditions d'exécution.
- Les résultats des tests de résilience doivent être interprétés à partir des journaux et des mesures réellement collectées.

## 15. Contexte académique

Projet réalisé dans le cadre du cours IFT605 / IFT713 — Systèmes répartis et multi-agents, Université de Sherbrooke, Automne 2026.

**AutonomicMesh — Middleware distribué auto-géré avec gRPC, IPC et MAPE-K.**