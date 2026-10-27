# Plan du rapport (8 à 12 pages) et préparation de la démonstration

## Plan suggéré (aligné sur le barème)

1. **Introduction** (½ page) : contexte, objectifs.
2. **Architecture** (1,5 page) : schéma des composants (simulateurs, Mosquitto, agents, registre RMI,
   coordinateur, openHAB) ; convention des topics ; diagramme de séquence « écart > 4 °C » :
   coordinateur → RMI → agent → MQTT → simulateur/openHAB.
3. **MQTT** (1 page) : topics, QoS choisis (0 pour les mesures, 1 pour la consigne) et pourquoi ; plages
   de valeurs simulées (15–30 °C, 0–1000 lux, true/false) ; capture des 9 flux.
4. **openHAB** (1,5 page) : Bridge/Things/Items/Rules, capture du tableau de bord, règles
   (économie d'énergie, confort, alerte surchauffe, capteur hors ligne, équilibrage), `expire` → UNDEF.
5. **Java RMI** (2 pages) : interface, registre (port 2199), un agent par JVM, thread-safety (`volatile`),
   reconnexion MQTT.
6. **Coordinateur** (1 page) : politique d'équilibrage, seuil strict, journalisation, intégration REST.
7. **Tolérance aux pannes** (1 page) : avant / pendant / après (captures), timeout de 30 s,
   `RemoteException`.
8. **Tests** (½ page) : JUnit (15 tests) et ce qu'ils couvrent.
9. **Analyse critique** (1 page) : voir ci-dessous.
10. **Difficultés rencontrées** (½ page) : incohérences de l'énoncé (`getEtat`/`getEtatJson`, Bridge openHAB).

## Idées pour l'analyse critique
* RMI : couplage fort à Java, traversée de pare-feu difficile, registre = point unique de défaillance.
* MQTT sans authentification ni TLS ; QoS 0 : perte possible de mesures.
* `volatile` suffit pour des champs indépendants, pas pour des invariants entre plusieurs champs
  (alors : `synchronized` ou objet immuable remplacé atomiquement).
* Politique d'équilibrage naïve : pas d'hystérésis, peut envoyer plusieurs consignes d'affilée ;
  ne tient pas compte de l'occupation.
* Mise à l'échelle : N pièces → N JVM ; alternative : un agent multi-pièces ou des conteneurs.

## Questions probables de l'enseignante
* Pourquoi `volatile` et non `synchronized` ? Quand ne suffirait-il plus ?
* Que se passe-t-il si le registre RMI tombe ? Si Mosquitto tombe ?
* Différence entre QoS 0, 1 et 2 ? Lequel avez-vous choisi, et pourquoi ?
* Que fait `UnicastRemoteObject` ? Pourquoi `java.rmi.server.hostname` ?
* Pourquoi un `try/catch` par agent dans le coordinateur ? Et pourquoi `catch (Throwable)` dans la tâche planifiée ?
* Pourquoi se réabonner dans `connectComplete` après une reconnexion ?
* Comment `expire` met-il l'Item à UNDEF ? Pourquoi le timeout est-il détecté à deux endroits (agent et openHAB) ?
* Que montre exactement votre test JUnit de panne ?
