# AutonomicMesh — IFT605/IFT713

Middleware distribué auto-géré : 3 micro-services gRPC, sondes IPC (socket TCP),
gestionnaire autonome MAPE-K en Java pur, ChaosMonkey pour mesurer le MTTR.

## Prérequis

- JDK 21 
- Maven 3.9+ 
- Toutes les commandes se lancent **depuis la racine du projet** (le dossier qui contient ce README).

## Structure

```
autonomic-mesh/
├── pom.xml                      POM parent (Java 21, gRPC 1.68.1, Protobuf 3.25.5)
├── mesh-services/               Contrats + micro-services
│   ├── src/main/proto/mesh.proto
│   └── ca.usherbrooke.ift605.mesh
│       ├── common/              Log, MeshConfig (ports, seuils), ServiceType, PidFiles
│       ├── services/            ServiceMain, Calc/Storage/NotifServiceImpl, ChaosControlImpl,
│       │                        FaultState, HealthReporter
│       └── client/MeshTestClient   validation semaine 1
├── mape-k/                      Gestionnaire autonome
│   └── ca.usherbrooke.ift605.mapek
│       ├── GestionnaireAutonome   point d'entrée, boucle toutes les 10 s
│       ├── Monitor, Analyzer, Planner, Executor, KnowledgeBase
│       ├── Configuration, EventJournal (CSV)
│       ├── model/               MesureSante, Symptome, ActionCorrective, Incident, InstanceInfo...
│       ├── sonde/               Sonde (1 thread par instance), ProbeAgent (processus des sondes)
│       ├── infra/               ServiceManager (ProcessBuilder), RegistryServiceImpl (gRPC 50050)
│       └── client/TrafficClient   trafic continu pour la démo
└── chaos/                       ChaosMonkey (injection de pannes, MTTR, faux positifs)
```

## Architecture et ports

| Composant | Port | Rôle |
|---|---|---|
| calc-1 / storage-1 / notif-1 | 50051 / 50052 / 50053 | instances primaires |
| calc-backup / storage-backup / notif-backup | 50151 / 50152 / 50153 | secours à chaud (réacheminement) |
| calc-2, storage-2, notif-2 | 50251 / 50252 / 50253 | répliques créées par mise à l'échelle |
| Registre gRPC (dans le gestionnaire) | 50050 | routage + liste des instances |
| Monitor (IPC) | 9090 | réception des mesures JSON des sondes |

Flux : `Sonde --HealthCheck gRPC--> service` puis `Sonde --JSON/TCP 9090--> Monitor --> KnowledgeBase`.
Toutes les 10 s : `Analyze → Plan → Execute`. Les clients résolvent chaque service auprès du
registre, qui suit la table de routage de la KB : un réacheminement est donc **observable**.

Politiques :

| Symptôme | Règle | Action(s) |
|---|---|---|
| PANNE_TOTALE | statut DOWN ou mesure > 20 s | REACHEMINEMENT vers le secours (si routé) + REDEMARRER |
| DEGRADATION | latence > 500 ms | REACHEMINEMENT vers le secours |
| SURCHARGE | cpuLoad > 0,90 | MISE_A_ECHELLE (1 réplique max par défaut) |
| primaire sain 2 mesures de suite | — | RETABLIR_ROUTAGE |

## Étapes

### 1. Compiler
```
mvn clean package
```
Produit `mesh-services/target/mesh-services-1.0.0-all.jar`, `mape-k/target/mape-k-1.0.0-all.jar`,
`chaos/target/chaos-1.0.0-all.jar`.

### 2. Validation semaine 1 — services seuls
Trois terminaux :
```
java -jar mesh-services/target/mesh-services-1.0.0-all.jar calc 50051 calc-1
java -jar mesh-services/target/mesh-services-1.0.0-all.jar storage 50052 storage-1
java -jar mesh-services/target/mesh-services-1.0.0-all.jar notif 50053 notif-1
```
Quatrième terminal :
```
java -cp mesh-services/target/mesh-services-1.0.0-all.jar ca.usherbrooke.ift605.mesh.client.MeshTestClient
```
Arrêter ensuite les trois services (Ctrl+C).

### 3. Gestionnaire autonome (sondes + Monitor + MAPE-K)
```
java -jar mape-k/target/mape-k-1.0.0-all.jar
```
Il lance lui-même les 6 instances et le processus des sondes (journaux dans `logs/`).
À vérifier : `Première mesure de calc-1=SERVING(...)` pour chaque instance, puis une ligne
`[MONITOR] cycle N ...` toutes les 10 s.

### 4. Trafic client (autre terminal)
```
java -cp mape-k/target/mape-k-1.0.0-all.jar ca.usherbrooke.ift605.mapek.client.TrafficClient
```

### 5. Validation semaine 2 — une panne et une dégradation (autre terminal)
```
java -jar chaos/target/chaos-1.0.0-all.jar --scenario kill --iterations 1 --interval 5 --label test-kill
java -jar chaos/target/chaos-1.0.0-all.jar --scenario degrade --iterations 1 --interval 5 --label test-degrade
```
Le terminal du gestionnaire montre `MONITOR → ANALYZE → PLAN → EXECUTE → RECUPERATION`,
le TrafficClient montre le changement d'instance qui sert les requêtes.

### 6. Semaine 3 — mesures pour le rapport
Avec MAPE-K (gestionnaire en mode normal) :
```
java -jar chaos/target/chaos-1.0.0-all.jar --scenario kill --iterations 10 --interval 30 --label auto
java -jar chaos/target/chaos-1.0.0-all.jar --scenario spike --iterations 5 --interval 30 --label spike-c1
```
Sans MAPE-K (intervention manuelle simulée) : relancer le gestionnaire avec
```
java -jar mape-k/target/mape-k-1.0.0-all.jar --mode manuel --delai-operateur 60
```
puis
```
java -jar chaos/target/chaos-1.0.0-all.jar --scenario kill --iterations 10 --interval 30 --label manuel
```
Réduction des faux positifs : gestionnaire avec `--confirmations 2`, puis refaire `spike` avec `--label spike-c2`.

Résultats dans `results/` :
- `chaos-<label>.csv` et `chaos-<label>-resume.txt` : MTTR, taux de détection ≤ 15 s, cycles, faux positifs ;
- `mapek-events.csv` : DETECTION / ACTION / RECUPERATION (recréé à chaque lancement du gestionnaire, à copier entre deux campagnes) ;
- `overhead.csv` : CPU, heap et threads du gestionnaire à chaque cycle.

## Options

Gestionnaire : `--mode auto|manuel`, `--delai-operateur S`, `--confirmations N`,
`--seuil-latence MS`, `--seuil-cpu X`, `--max-repliques N`, `--cycle S`, `--sans-services`, `--sans-sondes`.

ChaosMonkey : `--scenario kill|degrade|overload|unavailable|spike|mixed`, `--iterations N`,
`--interval S`, `--timeout S`, `--services calc,storage,notif`, `--label nom`, `--seed N`.

## Choix et limites à discuter dans le rapport

- Le processus des sondes est séparé du gestionnaire (vrai IPC entre processus).
- La surcharge CPU et la latence sont **simulées** par injection (ChaosControl), pas un vrai calcul intensif.
- Le stockage est en mémoire par instance : données perdues au redémarrage, non répliquées sur le secours.
- Si le processus des sondes tombe, toutes les mesures se périment : le gestionnaire voit une panne
  générale (faux positif massif). Le gestionnaire lui-même est un point de défaillance unique.
- Pas de réduction d'échelle automatique.

## Dépannage

- **Port déjà utilisé** : un ancien processus tourne encore. Fermer les terminaux, ou chercher le
  PID dans `run/pids/`.
- **Mac Apple Silicon et erreur sur `protoc`** : ajouter `-Dos.detected.classifier=osx-x86_64` à la commande `mvn`.