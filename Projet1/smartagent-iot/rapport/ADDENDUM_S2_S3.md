# Addendum au rapport — Extensions S2 et S3

> **But de ce document.** Il regroupe le texte à intégrer dans
> `Rapport_SmartAgentIoT.docx` pour couvrir les deux objectifs hebdomadaires de
> l'énoncé qui n'étaient pas encore traités : la **consultation mutuelle des
> agents via RMI** (objectif Semaine 2) et l'**intégration du coordinateur aux
> règles openHAB via l'API REST** (objectif Semaine 3). Chaque bloc indique
> **où l'insérer** dans le rapport existant (la numérotation suit celle du
> rapport). Les captures d'écran à insérer sont signalées par `[CAPTURE : …]`.

---

## À insérer dans « 3. Choix de conception »

### 3.6 Consultation pair-à-pair entre agents via RMI (objectif S2)

L'énoncé demande, en Semaine 2, de « permettre aux agents de se consulter
mutuellement via RMI (partage d'état) ». Nous distinguons donc **deux
topologies de coordination**, volontairement complémentaires :

- une coordination **centralisée** : le `CoordinateurBatiment` interroge tous
  les agents et arbitre (Section 3.3) ;
- une coordination **pair-à-pair** : chaque `AgentPiece` interroge **directement**
  ses voisins, sans intermédiaire.

Ce choix illustre que RMI n'impose aucune hiérarchie : un `AgentPiece` est à la
fois **serveur** RMI (il expose `IAgentPiece` au coordinateur) et **client**
RMI (il appelle `getTemperature()` sur ses voisins). C'est exactement la
dualité d'un système multi-agents réparti.

**Conception retenue :**

- Le voisinage suit l'adjacence imposée par l'énoncé (`salleA↔salleB`,
  `salleB↔cuisine`). `salleB`, au centre, a donc deux voisins ; `salleA` et
  `cuisine` en ont un. La table est fournie à l'agent au démarrage par
  `AgentPieceMain`, et non codée en dur dans `AgentPiece` : l'agent reste
  agnostique de la topologie du bâtiment.
- Un **thread démon dédié** (`consultation-<piece>`) effectue, toutes les 10 s,
  un `lookup` de chaque voisin dans le registre puis lit sa température en
  direct. Il est séparé du watchdog de timeout et des appels RMI entrants : la
  consultation ne bloque jamais la disponibilité de l'agent.
- Le **lookup est refait à chaque cycle** (et non mémorisé une fois pour
  toutes). C'est délibéré : un voisin peut démarrer après nous, ou tomber puis
  revenir ; refaire le lookup rend la consultation robuste à l'ordre de
  démarrage et aux pannes.
- **Partage d'état** : la dernière température connue de chaque voisin est
  conservée dans une `ConcurrentHashMap` (écrite par le thread de consultation,
  lue par les threads RMI) et exposée dans `getEtatJson()` sous la clé
  `voisins`.
- **Tolérance aux pannes** (cohérente avec la Tâche 3.1) : un voisin non encore
  enregistré lève `NotBoundException`, un voisin mort lève `RemoteException` ;
  dans les deux cas on ignore ce voisin **pour ce cycle seulement**, sans
  interrompre la consultation des autres.

Extrait (cœur de la boucle de consultation, `AgentPiece.java`) :

```java
private void consulterUnVoisin(String voisin) {
    try {
        Registry registre = LocateRegistry.getRegistry("localhost", PORT_REGISTRE);
        IAgentPiece stubVoisin = (IAgentPiece) registre.lookup("agent-" + voisin);
        double tempVoisin = stubVoisin.getTemperature();   // vrai appel RMI agent -> agent
        dernieresTempVoisins.put(voisin, tempVoisin);       // partage d'état (thread-safe)
        // ... journalisation de la comparaison ma temp / temp voisin ...
    } catch (NotBoundException e) {
        dernieresTempVoisins.remove(voisin);                // voisin pas encore démarré : normal
    } catch (RemoteException e) {
        dernieresTempVoisins.remove(voisin);                // voisin tombé : on continue
    }
}
```

### 3.7 Intégration coordinateur → règles openHAB via l'API REST (objectif S3)

L'énoncé demande, en Semaine 3, d'« intégrer le coordinateur RMI avec les
règles openHAB (via l'API REST openHAB) ». Il existe désormais **deux chemins**
par lesquels une décision du coordinateur atteint openHAB :

1. **Chemin indirect `RMI → MQTT`** (déjà présent) : le coordinateur appelle
   `setConsigneTemperature()` sur l'agent, qui publie la consigne sur
   `batiment/{piece}/actuateur/thermostat` ; openHAB la lit via son canal MQTT.
2. **Chemin direct `REST`** (ajout S3) : le coordinateur envoie lui-même une
   **commande HTTP** à un Item openHAB, ce qui déclenche une **règle DSL**.

Le second chemin apporte ce que le premier ne fait pas : il relie explicitement
une **décision d'arbitrage** du coordinateur au **moteur de règles** d'openHAB
et au tableau de bord, sans passer par le bus de mesures des capteurs.

**Conception retenue :**

- Nouvelle classe `OpenHabRestClient` (module `coordinateur`) bâtie sur
  `java.net.http.HttpClient`, standard du JDK depuis Java 11 : **aucune
  dépendance Maven ajoutée** pour un simple `POST` de texte.
- Contrat utilisé : `POST /rest/items/{item}` avec un corps `text/plain` envoie
  une **commande** à l'Item (ce qui déclenche les règles « changed » /
  « received command »), à la différence de `PUT …/state` qui ne ferait que
  forcer l'état.
- À chaque déséquilibre traité, le coordinateur pousse un résumé de sa décision
  sur un Item dédié `Batiment_Coordination` (un `String` **sans canal MQTT**,
  alimenté uniquement par le coordinateur). La règle DSL `"Coordination
  batiment"` réagit à son changement — c'est le **pont coordinateur ↔ règles
  openHAB** demandé. L'Item est aussi affiché dans le sitemap.
- **Best-effort** : si openHAB est absent ou répond en erreur,
  `envoyerCommande()` journalise et renvoie `false` sans jamais propager
  d'exception ni interrompre le cycle d'équilibrage (même exigence de tolérance
  que la Tâche 3.1). L'intégration est **injectable et optionnelle** :
  `CoordinateurBatiment` accepte un `OpenHabRestClient` pouvant être `null`
  (ce qui permet de tester la politique pure sans openHAB), et l'URL est
  configurable par `-Dopenhab.url=…` (défaut `http://localhost:8080`).

Extrait côté coordinateur (`CoordinateurBatiment.traiterPaire`) :

```java
agentChaud.setConsigneTemperature(consigneCible);          // chemin 1 : RMI -> MQTT
if (openhab != null) {                                     // chemin 2 : REST -> règle openHAB
    String decision = String.format(Locale.US,
        "%s: consigne %.1fC (desequilibre %s/%s, ecart %.1fC)",
        nomChaud, consigneCible, nomA, nomB, ecart);
    openhab.envoyerCommande("Batiment_Coordination", decision);
}
```

Règle DSL correspondante (`batiment.rules`) :

```java
rule "Coordination batiment"
when
    Item Batiment_Coordination changed
then
    logInfo("SmartAgentIoT", "Coordinateur (via REST): " + Batiment_Coordination.state)
end
```

---

## À insérer dans « 4. Implémentation détaillée par semaine »

- **4.2 (Semaine 2)** — ajouter un paragraphe : au-delà du contrat `IAgentPiece`
  et du coordinateur central, chaque `AgentPiece` reçoit sa liste de voisins
  (`new AgentPiece(piece, voisins)`) et lance un thread de consultation
  pair-à-pair (voir 3.6). Le constructeur sans voisin est conservé pour les
  scénarios mono-pièce et les tests.
- **4.3 (Semaine 3)** — ajouter un paragraphe : l'intégration openHAB par REST
  (classe `OpenHabRestClient`, Item `Batiment_Coordination`, règle
  `"Coordination batiment"`) complète la tolérance aux pannes déjà décrite ;
  l'appel REST est best-effort au même titre que la gestion des
  `RemoteException` (voir 3.7).

---

## À insérer dans « 5. Tests et validation »

### 5.4 Validation des extensions S2 et S3

**S2 — consultation agent ↔ agent (observée en exécution réelle).**
Les logs des agents montrent les appels RMI de pièce à pièce, et
`getEtatJson()` expose l'état partagé des voisins :

```
[agent-salleA] consultation RMI de salleB : 20.0°C (ma temp 17.0°C, écart 3.0°C)
[agent-salleB] consultation RMI de cuisine : 21.1°C (ma temp 20.0°C, écart 1.1°C)

getEtatJson salleB : {"piece":"salleB","temperature":20.2,...,"voisins":{"salleA":20.7,"cuisine":23.6}}
```

`[CAPTURE : terminaux des agents salleA/salleB montrant les lignes « consultation RMI de … »]`

**S3 — notification REST vers openHAB (observée de bout en bout).**
Lors d'un déséquilibre réel, le coordinateur poste sa décision sur openHAB et
reçoit `HTTP 200` ; l'état de l'Item relu correspond exactement au message :

```
[Coordinateur] DESEQUILIBRE salleB(17.8) / cuisine(25.7) -> écart 7.9 > seuil 4.0 : consigne 21.8 envoyée à cuisine
[Coordinateur->openHAB REST] Batiment_Coordination <- "cuisine: consigne 21.8C (desequilibre salleB/cuisine, ecart 7.9C)" (HTTP 200)

$ curl http://localhost:8080/rest/items/Batiment_Coordination/state
cuisine: consigne 21.8C (desequilibre salleB/cuisine, ecart 7.9C)
```

`[CAPTURE : log du coordinateur avec la ligne « …REST… (HTTP 200) », et l'Item Batiment_Coordination dans le tableau de bord openHAB]`

**Tests unitaires.** Deux tests JUnit ont été ajoutés dans
`CoordinateurBatimentTest` (via un double `FauxOpenHab` qui enregistre l'appel
au lieu d'émettre une vraie requête HTTP) : l'un vérifie qu'un déséquilibre
déclenche bien l'envoi REST sur `Batiment_Coordination`, l'autre qu'aucun envoi
n'a lieu sous le seuil. La suite passe désormais **6 tests** (`mvn clean test` →
BUILD SUCCESS, 6/6).

---

## À insérer dans « 7. Difficultés rencontrées et solutions »

### 7.8 Confusion openHAB : métadonnée « expire » vs « profile »

Les Items de capteur étaient initialement déclarés avec **à la fois**
`profile="expire"` **et** `expire="30s,state=UNDEF"`. Or, dans openHAB,
`expire` est une **métadonnée d'Item** (espace de noms `expire`), et **non** un
*profile* de lien de canal : il n'existe aucun profile nommé `expire` (les
profiles comme `offset`, `follow`, les transformations, etc., se déclarent entre
crochets `[ … ]` après le canal). Le `profile="expire"` était donc une
métadonnée morte, au mieux ignorée, au pire source de confusion. Il a été
retiré : seule subsiste la forme correcte
`{ channel="…", expire="30s,state=UNDEF" }`, qui force bien l'état à `UNDEF`
après 30 s sans mise à jour (Tâche 3.1).

---

## À mettre à jour dans « Annexe B — Correspondance avec le barème »

Les deux extensions renforcent des critères déjà couverts sans en créer de
nouveaux au barème :

- **Java RMI (20 pts)** : au contrat + 3 serveurs enregistrés s'ajoute une
  utilisation **client** de RMI entre agents (consultation pair-à-pair), qui
  démontre la maîtrise de la dualité serveur/client de RMI.
- **Coordinateur (10 pts)** et **openHAB Rules (10 pts)** : la notification REST
  relie explicitement les décisions du coordinateur au moteur de règles
  openHAB, au-delà du simple chemin MQTT.
- **Tolérance aux pannes (10 pts)** : la consultation pair-à-pair et l'appel
  REST sont tous deux best-effort (gestion `NotBound`/`RemoteException` et
  openHAB absent), cohérents avec la Tâche 3.1.

> **Rappel démonstration.** Ces ajouts sont des **objectifs de l'énoncé** (S2 et
> S3), à savoir expliquer à l'oral : pourquoi deux topologies de coordination
> (centralisée vs pair-à-pair), et pourquoi deux chemins vers openHAB (MQTT
> indirect vs REST direct déclenchant une règle).
