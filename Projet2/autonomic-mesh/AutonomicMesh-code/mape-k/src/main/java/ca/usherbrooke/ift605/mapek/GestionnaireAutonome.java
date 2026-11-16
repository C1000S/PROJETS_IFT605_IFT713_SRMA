package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mapek.infra.RegistryServiceImpl;
import ca.usherbrooke.ift605.mapek.infra.ServiceManager;
import ca.usherbrooke.ift605.mapek.model.ActionCorrective;
import ca.usherbrooke.ift605.mapek.model.Incident;
import ca.usherbrooke.ift605.mapek.model.InstanceInfo;
import ca.usherbrooke.ift605.mapek.model.MesureSante;
import ca.usherbrooke.ift605.mapek.model.Role;
import ca.usherbrooke.ift605.mapek.model.Symptome;
import ca.usherbrooke.ift605.mapek.model.TypeSymptome;
import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.common.ServiceType;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gestionnaire autonome : orchestre Monitor → Analyze → Plan → Execute autour de la
 * KnowledgeBase, toutes les 10 s.
 * <p>
 * Au démarrage, il :
 * <ol>
 *   <li>ouvre le port IPC du Monitor (9090) ;</li>
 *   <li>expose le registre gRPC (50050) ;</li>
 *   <li>lance les 3 services primaires et leurs secours à chaud (processus séparés) ;</li>
 *   <li>lance le processus des sondes ;</li>
 *   <li>planifie la boucle MAPE-K.</li>
 * </ol>
 * Usage : {@code java -jar mape-k/target/mape-k-all.jar [--mode auto|manuel] [--delai-operateur 60]
 * [--confirmations 1] [--seuil-latence 500] [--seuil-cpu 0.9] [--max-repliques 1] [--cycle 10]
 * [--sans-services] [--sans-sondes]}
 */
public class GestionnaireAutonome {

    private final Configuration conf;
    // Ordre de déclaration important : la KB doit exister avant les composants qui la reçoivent
    private final KnowledgeBase kb = new KnowledgeBase();
    private final ServiceManager services = new ServiceManager();
    private final EventJournal journal;
    private final AtomicLong cycle = new AtomicLong();
    private final Monitor monitor;
    private final Analyzer analyzer;
    private final Planner planner;
    private final Executor executor;
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "boucle-mapek");
        t.setDaemon(false);
        return t;
    });
    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memoire = ManagementFactory.getMemoryMXBean();
    private Server registre;

    /** @param conf configuration lue sur la ligne de commande */
    public GestionnaireAutonome(Configuration conf) {
        this.conf = conf;
        this.journal = new EventJournal(conf.modeManuel() ? "MANUEL" : "AUTO");
        this.monitor = new Monitor(kb);
        this.analyzer = new Analyzer(kb, conf);
        this.planner = new Planner(kb, conf);
        this.executor = new Executor(kb, services, journal, conf, cycle::get);
    }

    /**
     * Démarre tous les composants puis la boucle MAPE-K.
     *
     * @throws IOException si le registre gRPC ne peut pas démarrer
     */
    public void demarrer() throws IOException {
        Log.info("GESTIONNAIRE", "Configuration : " + conf);

        Thread tMonitor = new Thread(() -> {
            try {
                monitor.demarrer(MeshConfig.PORT_IPC);
            } catch (IOException e) {
                Log.error("MONITOR", "Port IPC " + MeshConfig.PORT_IPC + " indisponible", e);
                System.exit(1);
            }
        }, "monitor");
        tMonitor.setDaemon(true);
        tMonitor.start();

        registre = ServerBuilder.forPort(MeshConfig.PORT_REGISTRE)
                .addService(new RegistryServiceImpl(kb, conf.peremptionMs()))
                .build().start();
        Log.info("GESTIONNAIRE", "Registre gRPC démarré sur le port " + MeshConfig.PORT_REGISTRE);

        for (ServiceType t : ServiceType.values()) {
            InstanceInfo prim = new InstanceInfo(t.nom() + "-1", t.nom(), MeshConfig.HOTE, t.portParDefaut(),
                    Role.PRIMAIRE);
            InstanceInfo sec = new InstanceInfo(t.nom() + "-backup", t.nom(), MeshConfig.HOTE,
                    t.portParDefaut() + MeshConfig.DECALAGE_SECOURS, Role.SECOURS);
            kb.enregistrerInstance(prim);
            kb.enregistrerInstance(sec);
            kb.setRoutage(t.nom(), prim.instanceId());
            if (conf.lancerServices()) {
                lancer(prim);
                lancer(sec);
            }
        }
        if (conf.lancerSondes()) {
            long pid = services.demarrerSondes();
            Log.info("GESTIONNAIRE", "Processus des sondes lancé (pid " + pid + ", journal logs/sondes.log)");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::arreter, "arret-gestionnaire"));

        sched.scheduleAtFixedRate(this::cycleMapeK, conf.periodeCycleS(), conf.periodeCycleS(), TimeUnit.SECONDS);
        Log.info("GESTIONNAIRE", "Boucle MAPE-K planifiée toutes les " + conf.periodeCycleS() + " s");
    }

    private void lancer(InstanceInfo i) {
        try {
            long pid = services.demarrer(i);
            Log.info("GESTIONNAIRE", String.format("%s lancé sur le port %d (pid %d, journal logs/%s.log)",
                    i.instanceId(), i.port(), pid, i.instanceId()));
        } catch (IOException e) {
            Log.error("GESTIONNAIRE", "Lancement impossible de " + i.instanceId(), e);
        }
    }

    /** Un cycle complet. Toute exception est capturée pour ne pas arrêter la planification. */
    void cycleMapeK() {
        long n = cycle.incrementAndGet();
        try {
            // M — Monitor : les mesures arrivent en continu par IPC ; on affiche l'état de la KB
            Log.info("MONITOR", String.format("cycle %d — %d mesure(s) reçue(s), %d rejetée(s) — %s",
                    n, monitor.nbRecues(), monitor.nbRejetees(), etatCourant()));

            // A — Analyze
            List<Symptome> symptomes = analyzer.analyser();
            if (!symptomes.isEmpty()) {
                Log.info("ANALYZE", symptomes.size() + " symptôme(s) : " + symptomes);
            }
            ouvrirIncidents(symptomes, n);

            // P — Plan
            List<ActionCorrective> actions = conf.modeManuel()
                    ? planner.planifierManuel(symptomes)
                    : planner.planifier(symptomes);
            if (!actions.isEmpty()) {
                Log.info("PLAN", actions.size() + " action(s) : " + actions);
            }

            // E — Execute
            actions.forEach(executor::executer);

            verifierRecuperations(n, symptomes);
            journal.overhead(n, Math.max(0, os.getProcessCpuLoad()),
                    memoire.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0), Thread.activeCount());
        } catch (RuntimeException e) {
            Log.error("CYCLE " + n, "Cycle interrompu", e);
        }
    }

    private void ouvrirIncidents(List<Symptome> symptomes, long n) {
        Set<String> enDefaut = new HashSet<>();
        for (Symptome s : symptomes) {
            enDefaut.add(s.instanceId());
            if (kb.incident(s.instanceId()) == null && !kb.estAcquitte(s.instanceId(), s.type())) {
                kb.ouvrirIncident(new Incident(s.instanceId(), s.service(), s.type(), System.currentTimeMillis(), n));
                journal.evenement(n, "DETECTION", s.service(), s.instanceId(), s.type(), "", -1, -1,
                        s.preuve() == null ? "aucune mesure récente" : s.preuve().toString());
            }
        }
        // Instance redevenue normale : on lève l'acquittement
        for (InstanceInfo i : kb.instances()) {
            if (!enDefaut.contains(i.instanceId())) {
                kb.leverAcquittement(i.instanceId());
            }
        }
    }

    /**
     * Ferme les incidents dont le service est de nouveau rendu : la cible du routage ou
     * une réplique a produit, après l'action, une mesure SERVING sous les seuils.
     */
    private void verifierRecuperations(long n, List<Symptome> symptomes) {
        for (Incident inc : kb.incidents()) {
            long apres = inc.tActionMs() > 0 ? inc.tActionMs() : inc.tDetectionMs();
            if (!serviceRendu(inc.service(), apres)) {
                continue;
            }
            long now = System.currentTimeMillis();
            long cycles = n - inc.cycleDetection() + 1;
            boolean spontanee = inc.action() == null;
            String evt = spontanee ? "RECUPERATION_SPONTANEE" : "RECUPERATION";
            Log.info("RECUPERATION", String.format("%s : service %s rendu %d ms après détection (%d cycle(s), action %s)",
                    inc.instanceId(), inc.service(), now - inc.tDetectionMs(), cycles,
                    spontanee ? "aucune" : inc.action()));
            journal.evenement(n, evt, inc.service(), inc.instanceId(), inc.symptome(), inc.action(),
                    now - inc.tDetectionMs(), cycles, "");
            kb.fermerIncident(inc.instanceId());
            boolean persiste = symptomes.stream()
                    .anyMatch(s -> s.instanceId().equals(inc.instanceId()) && s.type() == inc.symptome());
            if (persiste) {
                kb.acquitter(inc.instanceId(), inc.symptome());
            }
            if (inc.symptome() == TypeSymptome.PANNE_TOTALE) {
                kb.terminerAction(inc.instanceId());
            }
        }
    }

    private boolean serviceRendu(String service, long apresMs) {
        String cible = kb.routage(service);
        Set<String> candidats = new HashSet<>();
        if (cible != null) candidats.add(cible);
        kb.instances(service, Role.REPLIQUE).forEach(i -> candidats.add(i.instanceId()));
        for (String id : candidats) {
            MesureSante m = kb.derniereMesure(id);
            if (m != null && m.timestamp > apresMs && m.estSaine(conf.seuilLatenceMs(), conf.seuilCpu())) {
                return true;
            }
        }
        return false;
    }

    private String etatCourant() {
        StringJoiner j = new StringJoiner("  ");
        for (InstanceInfo i : kb.instances()) {
            MesureSante m = kb.derniereMesure(i.instanceId());
            j.add(m == null ? i.instanceId() + "=?" : m.toString());
        }
        return j + "  | routage " + kb.getRoutage();
    }

    /** Arrête la boucle, le registre, le Monitor et tous les processus enfants. */
    public void arreter() {
        Log.info("GESTIONNAIRE", "Arrêt : fermeture de la boucle et des processus enfants");
        sched.shutdownNow();
        executor.close();
        monitor.arreter();
        if (registre != null) registre.shutdownNow();
        services.arreterTout();
        journal.close();
    }

    /**
     * @param args options (voir la Javadoc de la classe)
     */
    public static void main(String[] args) {
        Configuration conf;
        try {
            conf = Configuration.depuisArguments(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Options : --mode auto|manuel  --delai-operateur S  --confirmations N"
                    + "  --seuil-latence MS  --seuil-cpu X  --max-repliques N  --cycle S  --sans-services  --sans-sondes");
            System.exit(2);
            return;
        }
        try {
            new GestionnaireAutonome(conf).demarrer();
        } catch (IOException e) {
            Log.error("GESTIONNAIRE", "Démarrage impossible (port " + MeshConfig.PORT_REGISTRE + " occupé ?)", e);
            System.exit(1);
        }
    }
}
