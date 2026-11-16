package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mapek.model.Incident;
import ca.usherbrooke.ift605.mapek.model.InstanceInfo;
import ca.usherbrooke.ift605.mapek.model.MesureSante;
import ca.usherbrooke.ift605.mapek.model.Role;
import ca.usherbrooke.ift605.mapek.model.TypeSymptome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base de connaissances (K de MAPE-K) : état partagé du système.
 * <p>
 * Toutes les structures sont des {@link ConcurrentHashMap} : la KB est écrite par le
 * Monitor (threads de connexion IPC), lue par Analyzer/Planner (thread de la boucle),
 * modifiée par l'Executor et lue par le registre gRPC (threads gRPC).
 * L'historique par instance est un deque protégé par son propre verrou.
 */
public class KnowledgeBase {

    /** Taille maximale de l'historique conservé par instance. */
    public static final int TAILLE_HISTORIQUE = 20;

    private final ConcurrentHashMap<String, MesureSante> mesures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<MesureSante>> historique = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> nbPannes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> routage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InstanceInfo> instances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> enregistrements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> actionsEnCours = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Incident> incidents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TypeSymptome> acquittes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> tourniquet = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> numerosRepliques = new ConcurrentHashMap<>();

    // ───────────── Instances ─────────────

    /** @param info instance à ajouter (ou remplacer) */
    public void enregistrerInstance(InstanceInfo info) {
        instances.put(info.instanceId(), info);
        enregistrements.put(info.instanceId(), System.currentTimeMillis());
    }

    /** @return depuis combien de ms l'instance est connue de la KB (0 si inconnue) */
    public long ageEnregistrementMs(String instanceId) {
        Long t = enregistrements.get(instanceId);
        return t == null ? 0 : System.currentTimeMillis() - t;
    }

    /** @return instance par identifiant */
    public Optional<InstanceInfo> instance(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    /** @return copie triée de toutes les instances connues */
    public List<InstanceInfo> instances() {
        List<InstanceInfo> l = new ArrayList<>(instances.values());
        l.sort(Comparator.comparing(InstanceInfo::instanceId));
        return l;
    }

    /** @return instances d'un service ayant un rôle donné */
    public List<InstanceInfo> instances(String service, Role role) {
        return instances().stream().filter(i -> i.service().equals(service) && i.role() == role).toList();
    }

    /** @return l'instance primaire du service */
    public Optional<InstanceInfo> primaire(String service) {
        return instances(service, Role.PRIMAIRE).stream().findFirst();
    }

    /** @return l'instance de secours du service */
    public Optional<InstanceInfo> secours(String service) {
        return instances(service, Role.SECOURS).stream().findFirst();
    }

    /** @return prochain numéro de réplique pour le service (2, 3, ...) */
    public int prochainNumeroReplique(String service) {
        return numerosRepliques.computeIfAbsent(service, s -> new AtomicInteger(1)).incrementAndGet();
    }

    // ───────────── Mesures ─────────────

    /**
     * Remplace la mesure courante de l'instance et l'ajoute à l'historique.
     *
     * @param m mesure validée
     * @return la mesure précédente, ou null
     */
    public MesureSante enregistrerMesure(MesureSante m) {
        Deque<MesureSante> h = historique.computeIfAbsent(m.instanceId, k -> new ArrayDeque<>());
        synchronized (h) {
            h.addFirst(m);
            while (h.size() > TAILLE_HISTORIQUE) {
                h.removeLast();
            }
        }
        return mesures.put(m.instanceId, m);
    }

    /** @return vue non modifiable des dernières mesures par instance */
    public Map<String, MesureSante> getMesures() {
        return Collections.unmodifiableMap(mesures);
    }

    /** @return dernière mesure de l'instance, ou null */
    public MesureSante derniereMesure(String instanceId) {
        return mesures.get(instanceId);
    }

    /**
     * @param instanceId instance
     * @param n          nombre de mesures voulues
     * @return au plus n mesures, de la plus récente à la plus ancienne
     */
    public List<MesureSante> dernieresMesures(String instanceId, int n) {
        Deque<MesureSante> h = historique.get(instanceId);
        if (h == null) {
            return List.of();
        }
        synchronized (h) {
            return h.stream().limit(n).toList();
        }
    }

    /**
     * Une instance est utilisable si sa dernière mesure est récente et n'est pas DOWN.
     *
     * @param instanceId   instance
     * @param peremptionMs âge maximal de la mesure
     * @return vrai si l'instance peut recevoir du trafic
     */
    public boolean estUtilisable(String instanceId, long peremptionMs) {
        MesureSante m = mesures.get(instanceId);
        return m != null && !"DOWN".equals(m.statut)
                && System.currentTimeMillis() - m.timestamp <= peremptionMs;
    }

    // ───────────── Routage ─────────────

    /** @return instance vers laquelle le service est routé, ou null */
    public String routage(String service) {
        return routage.get(service);
    }

    /**
     * @param service    service logique
     * @param instanceId nouvelle cible
     * @return ancienne cible, ou null
     */
    public String setRoutage(String service, String instanceId) {
        return routage.put(service, instanceId);
    }

    /** @return vue non modifiable de la table de routage */
    public Map<String, String> getRoutage() {
        return Collections.unmodifiableMap(routage);
    }

    /**
     * Choisit à tour de rôle un indice parmi n candidats pour le service.
     *
     * @param service service logique
     * @param n       nombre de candidats (> 0)
     * @return indice dans [0, n)
     */
    public int tourSuivant(String service, int n) {
        return Math.floorMod(tourniquet.computeIfAbsent(service, s -> new AtomicInteger()).getAndIncrement(), n);
    }

    // ───────────── Pannes et actions en cours ─────────────

    /** @return nombre de pannes après incrément */
    public int incrementerPannes(String instanceId) {
        return nbPannes.merge(instanceId, 1, Integer::sum);
    }

    /** @return vue non modifiable du nombre de pannes par instance */
    public Map<String, Integer> getNbPannes() {
        return Collections.unmodifiableMap(nbPannes);
    }

    /**
     * Marque une action en cours pour une clé (instance ou « scale:service ») afin
     * d'éviter de répéter la même action à chaque cycle (période de refroidissement).
     *
     * @param cle    clé de l'action
     * @param dureeMs durée de refroidissement
     */
    public void marquerActionEnCours(String cle, long dureeMs) {
        actionsEnCours.put(cle, System.currentTimeMillis() + dureeMs);
    }

    /** @return vrai si une action est encore en refroidissement pour la clé */
    public boolean actionEnCours(String cle) {
        Long fin = actionsEnCours.get(cle);
        if (fin == null) {
            return false;
        }
        if (System.currentTimeMillis() >= fin) {
            actionsEnCours.remove(cle, fin);
            return false;
        }
        return true;
    }

    /** @param cle clé dont le refroidissement est levé */
    public void terminerAction(String cle) {
        actionsEnCours.remove(cle);
    }

    // ───────────── Incidents ─────────────

    /** @return incident ouvert pour l'instance, ou null */
    public Incident incident(String instanceId) {
        return incidents.get(instanceId);
    }

    /** @param incident incident à ouvrir (ignoré si un incident est déjà ouvert) */
    public void ouvrirIncident(Incident incident) {
        incidents.putIfAbsent(incident.instanceId(), incident);
    }

    /** @return incidents ouverts */
    public Collection<Incident> incidents() {
        return List.copyOf(incidents.values());
    }

    /** @param instanceId instance dont l'incident est fermé */
    public void fermerIncident(String instanceId) {
        incidents.remove(instanceId);
    }

    /**
     * Mémorise qu'un symptôme persistant a déjà été traité (ex. primaire toujours lent
     * mais trafic déjà réacheminé), pour ne pas rouvrir un incident à chaque cycle.
     */
    public void acquitter(String instanceId, TypeSymptome type) {
        acquittes.put(instanceId, type);
    }

    /** @return vrai si ce symptôme est déjà acquitté pour l'instance */
    public boolean estAcquitte(String instanceId, TypeSymptome type) {
        return acquittes.get(instanceId) == type;
    }

    /** @param instanceId instance redevenue saine : son acquittement est levé */
    public void leverAcquittement(String instanceId) {
        acquittes.remove(instanceId);
    }
}
