package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mapek.infra.ServiceManager;
import ca.usherbrooke.ift605.mapek.model.ActionCorrective;
import ca.usherbrooke.ift605.mapek.model.Incident;
import ca.usherbrooke.ift605.mapek.model.InstanceInfo;
import ca.usherbrooke.ift605.mapek.model.Role;
import ca.usherbrooke.ift605.mapek.model.TypeAction;
import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.common.ServiceType;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * E de MAPE-K : réalise réellement les actions correctives.
 * <ul>
 *   <li>REDEMARRER : relance le processus gRPC (ProcessBuilder) ;</li>
 *   <li>REACHEMINEMENT : modifie la table de routage de la KB, suivie par le registre gRPC ;</li>
 *   <li>MISE_A_ECHELLE : démarre un nouveau processus réplique et l'ajoute au registre ;</li>
 *   <li>RETABLIR_ROUTAGE : remet le trafic sur l'instance primaire.</li>
 * </ul>
 * Les relances de processus sont asynchrones pour ne pas bloquer la boucle.
 * En mode manuel, chaque action est retardée du temps de réaction de l'opérateur simulé.
 */
public class Executor implements AutoCloseable {

    /** Refroidissement après un redémarrage automatique : relance + 1 à 2 mesures de sonde. */
    static final long REFROIDISSEMENT_REDEMARRAGE_MS = 15_000;
    /** Refroidissement après une mise à l'échelle. */
    static final long REFROIDISSEMENT_ECHELLE_MS = 30_000;

    private final KnowledgeBase kb;
    private final ServiceManager services;
    private final EventJournal journal;
    private final Configuration conf;
    private final LongSupplier cycleCourant;
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "executor");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param kb           base de connaissances
     * @param services     gestionnaire de processus
     * @param journal      journal CSV
     * @param conf         configuration (mode manuel, délai opérateur)
     * @param cycleCourant fournit le numéro du cycle en cours (pour le journal)
     */
    public Executor(KnowledgeBase kb, ServiceManager services, EventJournal journal, Configuration conf,
                    LongSupplier cycleCourant) {
        this.kb = kb;
        this.services = services;
        this.journal = journal;
        this.conf = conf;
        this.cycleCourant = cycleCourant;
    }

    /**
     * Exécute une action (immédiatement en mode AUTO, après le délai opérateur en mode MANUEL).
     *
     * @param action action planifiée
     */
    public void executer(ActionCorrective action) {
        if (!conf.modeManuel()) {
            realiser(action);
            return;
        }
        // Opérateur humain simulé : il voit l'alerte puis agit après un délai variable (±20 %)
        long base = conf.delaiOperateurS() * 1000L;
        long delai = (long) (base * ThreadLocalRandom.current().nextDouble(0.8, 1.2));
        kb.marquerActionEnCours(action.instanceId(), delai + REFROIDISSEMENT_REDEMARRAGE_MS);
        Log.info("EXECUTE", String.format("[MANUEL] Alerte transmise à l'opérateur pour %s, intervention dans %d s",
                action.instanceId(), delai / 1000));
        journal.evenement(cycleCourant.getAsLong(), "ALERTE_OPERATEUR", action.service(), action.instanceId(),
                action.raison(), action.type(), delai, -1, "intervention humaine simulée");
        pool.schedule(() -> realiser(action), delai, TimeUnit.MILLISECONDS);
    }

    private void realiser(ActionCorrective action) {
        try {
            switch (action.type()) {
                case REDEMARRER -> redemarrer(action);
                case REACHEMINEMENT -> reacheminer(action);
                case MISE_A_ECHELLE -> mettreAEchelle(action);
                case RETABLIR_ROUTAGE -> retablir(action);
            }
        } catch (RuntimeException e) {
            Log.error("EXECUTE", "Échec de l'action " + action, e);
        }
    }

    private void tracer(ActionCorrective action, String detail) {
        long now = System.currentTimeMillis();
        Incident inc = kb.incident(action.instanceId());
        if (inc != null) {
            inc.enregistrerAction(action.type(), now);
        }
        Log.info("EXECUTE", action + " — " + detail);
        journal.evenement(cycleCourant.getAsLong(), "ACTION", action.service(), action.instanceId(),
                action.raison(), action.type(), inc == null ? -1 : now - inc.tDetectionMs(), -1, detail);
    }

    private void redemarrer(ActionCorrective action) {
        Optional<InstanceInfo> info = kb.instance(action.cible());
        if (info.isEmpty()) {
            Log.warn("EXECUTE", "Instance inconnue : " + action.cible());
            return;
        }
        kb.marquerActionEnCours(action.cible(), REFROIDISSEMENT_REDEMARRAGE_MS);
        int pannes = kb.incrementerPannes(action.cible());
        tracer(action, "relance du processus (panne n°" + pannes + ")");
        pool.execute(() -> {
            try {
                long pid = services.redemarrer(info.get());
                Log.info("EXECUTE", action.cible() + " relancé (nouveau pid " + pid + ")");
            } catch (IOException e) {
                Log.error("EXECUTE", "Relance impossible de " + action.cible(), e);
                kb.terminerAction(action.cible()); // on retentera au prochain cycle
            }
        });
    }

    private void reacheminer(ActionCorrective action) {
        String ancien = kb.setRoutage(action.service(), action.cible());
        tracer(action, "routage " + action.service() + " : " + ancien + " → " + action.cible());
    }

    private void retablir(ActionCorrective action) {
        String ancien = kb.setRoutage(action.service(), action.cible());
        Log.info("EXECUTE", action + " — routage " + action.service() + " : " + ancien + " → " + action.cible());
        journal.evenement(cycleCourant.getAsLong(), "ACTION", action.service(), action.cible(), "",
                action.type(), -1, -1, "retour sur le primaire");
    }

    private void mettreAEchelle(ActionCorrective action) {
        kb.marquerActionEnCours("scale:" + action.service(), REFROIDISSEMENT_ECHELLE_MS);
        int n = kb.prochainNumeroReplique(action.service());
        int base = ServiceType.depuisNom(action.service()).portParDefaut();
        int port = base + MeshConfig.DECALAGE_REPLIQUE + (n - 2);
        InstanceInfo replique = new InstanceInfo(action.service() + "-" + n, action.service(),
                MeshConfig.HOTE, port, Role.REPLIQUE);
        kb.enregistrerInstance(replique);
        tracer(action, "nouvelle réplique " + replique.instanceId() + " sur le port " + port);
        pool.execute(() -> {
            try {
                long pid = services.demarrer(replique);
                Log.info("EXECUTE", replique.instanceId() + " démarrée (pid " + pid + ")");
            } catch (IOException e) {
                Log.error("EXECUTE", "Démarrage impossible de " + replique.instanceId(), e);
            }
        });
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
