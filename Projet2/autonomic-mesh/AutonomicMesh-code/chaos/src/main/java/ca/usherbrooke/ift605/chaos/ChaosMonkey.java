package ca.usherbrooke.ift605.chaos;

import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.common.PidFiles;
import ca.usherbrooke.ift605.mesh.common.ServiceType;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.ChaosControlGrpc;
import ca.usherbrooke.ift605.mesh.proto.FaultRequest;
import ca.usherbrooke.ift605.mesh.proto.FaultType;
import ca.usherbrooke.ift605.mesh.proto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.RegistryServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.ResolveRequest;
import ca.usherbrooke.ift605.mesh.proto.ResolveResponse;
import ca.usherbrooke.ift605.mesh.proto.ServingStatus;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * ChaosMonkey : injecte des pannes dans les processus du projet (et seulement eux,
 * ciblés par leurs fichiers PID ou leur port local), mesure le temps de récupération
 * observé par un client et croise avec le journal MAPE-K pour produire les métriques
 * du rapport.
 * <p>
 * Scénarios :
 * <ul>
 *   <li>kill : arrêt du processus qui sert le service (SIGTERM) ;</li>
 *   <li>degrade : latence +800 ms pendant 60 s ;</li>
 *   <li>overload : charge CPU rapportée 0,95 pendant 60 s ;</li>
 *   <li>unavailable : le service répond DOWN jusqu'au redémarrage ;</li>
 *   <li>spike : pic de latence transitoire de 3 s (mesure des faux positifs) ;</li>
 *   <li>mixed : kill, degrade ou overload au hasard.</li>
 * </ul>
 * Usage : {@code java -jar chaos/target/chaos-all.jar --scenario kill --iterations 10 --interval 30 --label auto}
 * <p>
 * Résultats : {@code results/chaos-<label>.csv} et {@code results/chaos-<label>-resume.txt}.
 */
public final class ChaosMonkey {

    enum Scenario { KILL, DEGRADE, OVERLOAD, UNAVAILABLE, SPIKE, MIXED }

    /** Une injection et ce qu'on en a mesuré. */
    static final class Injection {
        int iteration;
        Scenario scenario;
        String service;
        String instance;
        long tPanne;
        long tRecuperation = -1;   // -1 : non récupéré avant le délai maximal
        // Complété depuis results/mapek-events.csv
        long tDetection = -1;
        String action = "";
        long cycles = -1;
        Boolean fauxPositif;       // seulement pour SPIKE
    }

    private static final long SEUIL_LATENCE_CLIENT_MS = MeshConfig.SEUIL_LATENCE_MS;
    private static final long FENETRE_FAUX_POSITIF_MS = 25_000;

    private final Scenario scenario;
    private final int iterations;
    private final int intervalleS;
    private final int delaiMaxS;
    private final List<String> services;
    private final String label;
    private final Random hasard;
    private final List<Injection> injections = new ArrayList<>();

    private ChaosMonkey(Scenario scenario, int iterations, int intervalleS, int delaiMaxS, List<String> services,
                        String label, long graine) {
        this.scenario = scenario;
        this.iterations = iterations;
        this.intervalleS = intervalleS;
        this.delaiMaxS = delaiMaxS;
        this.services = services;
        this.label = label;
        this.hasard = new Random(graine);
    }

    // ───────────────────────── Boucle principale ─────────────────────────

    private void demarrer() throws InterruptedException {
        Log.info("CHAOS", String.format("Scénario %s, %d itération(s), toutes les %d s, services %s, label '%s'",
                scenario, iterations, intervalleS, services, label));
        ManagedChannel registre = ManagedChannelBuilder.forAddress(MeshConfig.HOTE, MeshConfig.PORT_REGISTRE)
                .usePlaintext().build();
        try {
            for (int i = 1; i <= iterations; i++) {
                Log.info("CHAOS", "Prochaine injection dans " + intervalleS + " s (" + i + "/" + iterations + ")");
                Thread.sleep(intervalleS * 1000L);
                Injection inj = injecter(registre, i);
                if (inj == null) {
                    continue;
                }
                injections.add(inj);
                Thread.sleep(500); // laisse le temps à la faute de prendre effet
                attendreRecuperation(registre, inj);
                apresRecuperation(inj);
                ecrireResultats();
            }
            // Laisse le gestionnaire journaliser les dernières récupérations (2 cycles)
            Log.info("CHAOS", "Fin des injections, attente de 25 s pour le journal MAPE-K...");
            Thread.sleep(25_000);
            ecrireResultats();
        } finally {
            registre.shutdownNow();
        }
    }

    private Injection injecter(ManagedChannel registre, int iteration) {
        String service = services.get(hasard.nextInt(services.size()));
        Scenario s = scenario == Scenario.MIXED
                ? new Scenario[]{Scenario.KILL, Scenario.DEGRADE, Scenario.OVERLOAD}[hasard.nextInt(3)]
                : scenario;
        Optional<ResolveResponse> cible = resoudre(registre, service);
        if (cible.isEmpty()) {
            Log.warn("CHAOS", "Registre injoignable ou service " + service + " introuvable : injection annulée");
            return null;
        }
        ResolveResponse r = cible.get();
        Injection inj = new Injection();
        inj.iteration = iteration;
        inj.scenario = s;
        inj.service = service;
        inj.instance = r.getInstanceId();

        boolean ok = switch (s) {
            case KILL -> tuer(r.getInstanceId());
            case DEGRADE -> fauter(r, FaultType.LATENCY, 800, 0, 60);
            case OVERLOAD -> fauter(r, FaultType.CPU_OVERLOAD, 0, 0.95, 60);
            case UNAVAILABLE -> fauter(r, FaultType.UNAVAILABLE, 0, 0, 0);
            case SPIKE -> fauter(r, FaultType.LATENCY, 800, 0, 3);
            case MIXED -> false;
        };
        if (!ok) {
            return null;
        }
        inj.tPanne = System.currentTimeMillis();
        Log.info("CHAOS", String.format("[Chaos] %s sur %s (%s)", s, r.getInstanceId(), service));
        return inj;
    }

    /** Envoie SIGTERM au processus de l'instance (via son fichier PID). */
    private boolean tuer(String instanceId) {
        Optional<ProcessHandle> ph = PidFiles.lire(instanceId).flatMap(ProcessHandle::of);
        if (ph.isEmpty() || !ph.get().isAlive()) {
            Log.warn("CHAOS", "Pas de processus vivant pour " + instanceId + " (run/pids/" + instanceId + ".pid)");
            return false;
        }
        Log.info("CHAOS", "Arrêt de " + instanceId + " (pid " + ph.get().pid() + ")");
        return ph.get().destroy();
    }

    private boolean fauter(ResolveResponse r, FaultType type, long latenceMs, double cpu, int dureeS) {
        ManagedChannel c = ManagedChannelBuilder.forAddress(r.getHost(), r.getPort()).usePlaintext().build();
        try {
            var rep = ChaosControlGrpc.newBlockingStub(c).withDeadlineAfter(2, TimeUnit.SECONDS)
                    .injectFault(FaultRequest.newBuilder().setType(type).setLatencyMs(latenceMs)
                            .setCpuLoad(cpu).setDurationSec(dureeS).build());
            return rep.getApplied();
        } catch (StatusRuntimeException e) {
            Log.warn("CHAOS", "Injection impossible sur " + r.getInstanceId() + " : " + e.getStatus().getCode());
            return false;
        } finally {
            c.shutdownNow();
        }
    }

    /**
     * Interroge le service tel qu'un client le verrait (résolution par le registre puis
     * HealthCheck) toutes les 250 ms, jusqu'à obtenir SERVING sous le seuil de latence.
     */
    private void attendreRecuperation(ManagedChannel registre, Injection inj) throws InterruptedException {
        long limite = inj.tPanne + delaiMaxS * 1000L;
        while (System.currentTimeMillis() < limite) {
            Optional<ResolveResponse> r = resoudre(registre, inj.service);
            if (r.isPresent() && sain(inj.service, r.get())) {
                inj.tRecuperation = System.currentTimeMillis();
                Log.info("CHAOS", String.format("Service %s rendu par %s après %d ms",
                        inj.service, r.get().getInstanceId(), inj.tRecuperation - inj.tPanne));
                return;
            }
            Thread.sleep(250);
        }
        Log.warn("CHAOS", "Service " + inj.service + " non récupéré après " + delaiMaxS + " s");
    }

    /** Nettoie les fautes temporaires et observe la fenêtre de faux positif pour SPIKE. */
    private void apresRecuperation(Injection inj) throws InterruptedException {
        if (inj.scenario == Scenario.SPIKE) {
            long reste = inj.tPanne + FENETRE_FAUX_POSITIF_MS - System.currentTimeMillis();
            if (reste > 0) {
                Log.info("CHAOS", "Observation de la réaction au pic pendant " + reste / 1000 + " s");
                Thread.sleep(reste);
            }
        } else if (inj.scenario == Scenario.DEGRADE || inj.scenario == Scenario.OVERLOAD) {
            Thread.sleep(5_000);
            int port = portInstance(inj.service, inj.instance);
            ResolveResponse r = ResolveResponse.newBuilder().setInstanceId(inj.instance)
                    .setHost(MeshConfig.HOTE).setPort(port).build();
            if (fauter(r, FaultType.CLEAR, 0, 0, 0)) {
                Log.info("CHAOS", "Faute retirée de " + inj.instance);
            }
        }
    }

    // ───────────────────────── Outils gRPC ─────────────────────────

    private static Optional<ResolveResponse> resoudre(ManagedChannel registre, String service) {
        try {
            ResolveResponse r = RegistryServiceGrpc.newBlockingStub(registre).withDeadlineAfter(1, TimeUnit.SECONDS)
                    .resolve(ResolveRequest.newBuilder().setService(service).build());
            return r.getFound() ? Optional.of(r) : Optional.empty();
        } catch (StatusRuntimeException e) {
            registre.resetConnectBackoff();
            return Optional.empty();
        }
    }

    private static boolean sain(String service, ResolveResponse r) {
        ManagedChannel c = ManagedChannelBuilder.forAddress(r.getHost(), r.getPort()).usePlaintext().build();
        long t0 = System.nanoTime();
        try {
            HealthReq req = HealthReq.newBuilder().setServiceName(service).build();
            HealthResp h = switch (ServiceType.depuisNom(service)) {
                case CALC -> CalcServiceGrpc.newBlockingStub(c).withDeadlineAfter(2, TimeUnit.SECONDS).healthCheck(req);
                case STORAGE -> StorageServiceGrpc.newBlockingStub(c).withDeadlineAfter(2, TimeUnit.SECONDS).healthCheck(req);
                case NOTIF -> NotifServiceGrpc.newBlockingStub(c).withDeadlineAfter(2, TimeUnit.SECONDS).healthCheck(req);
            };
            long latence = (System.nanoTime() - t0) / 1_000_000;
            return h.getStatus() == ServingStatus.SERVING && latence <= SEUIL_LATENCE_CLIENT_MS;
        } catch (StatusRuntimeException e) {
            return false;
        } finally {
            c.shutdownNow();
        }
    }

    private static int portInstance(String service, String instanceId) {
        int base = ServiceType.depuisNom(service).portParDefaut();
        if (instanceId.endsWith("-backup")) return base + MeshConfig.DECALAGE_SECOURS;
        if (instanceId.endsWith("-1")) return base;
        try {
            int n = Integer.parseInt(instanceId.substring(instanceId.lastIndexOf('-') + 1));
            return base + MeshConfig.DECALAGE_REPLIQUE + (n - 2);
        } catch (NumberFormatException e) {
            return base;
        }
    }

    // ───────────────────────── Croisement avec le journal MAPE-K ─────────────────────────

    private void completerDepuisJournal() {
        List<String[]> evts = new ArrayList<>();
        try {
            if (Files.exists(MeshConfig.JOURNAL_EVENEMENTS)) {
                List<String> lignes = Files.readAllLines(MeshConfig.JOURNAL_EVENEMENTS, StandardCharsets.UTF_8);
                for (String l : lignes.subList(Math.min(1, lignes.size()), lignes.size())) {
                    evts.add(l.split(",", -1));
                }
            }
        } catch (IOException e) {
            Log.warn("CHAOS", "Journal MAPE-K illisible : " + e.getMessage());
        }
        // Colonnes : 0 timestamp_ms, 4 evenement, 5 service, 6 instance, 8 action, 10 cycles
        for (Injection inj : injections) {
            long fin = inj.tPanne + Math.max(FENETRE_FAUX_POSITIF_MS,
                    (inj.tRecuperation > 0 ? inj.tRecuperation - inj.tPanne : delaiMaxS * 1000L) + 30_000);
            boolean actionVue = false;
            for (String[] e : evts) {
                if (e.length < 11 || !e[5].equals(inj.service)) continue;
                long ts;
                try {
                    ts = Long.parseLong(e[0]);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (ts < inj.tPanne || ts > fin) continue;
                switch (e[4]) {
                    case "DETECTION" -> {
                        if (inj.tDetection < 0) inj.tDetection = ts;
                    }
                    case "ACTION", "ALERTE_OPERATEUR" -> {
                        if (!"RETABLIR_ROUTAGE".equals(e[8])) {
                            actionVue = true;
                            if (inj.action.isEmpty()) inj.action = e[8];
                        }
                    }
                    case "RECUPERATION", "RECUPERATION_SPONTANEE" -> {
                        if (inj.cycles < 0 && inj.tDetection > 0) {
                            try {
                                inj.cycles = Long.parseLong(e[10]);
                            } catch (NumberFormatException ignored) {
                                // valeur absente
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            if (inj.scenario == Scenario.SPIKE) {
                inj.fauxPositif = actionVue;
            }
        }
    }

    /**
     * Formate un instant en heure locale ISO-8601 avec décalage horaire
     * (ex. {@code 2026-11-10T21:52:07.974-05:00}).
     *
     * @param epochMs instant en millisecondes depuis l'époque Unix
     * @return l'heure formatée, ou une chaîne vide si l'événement n'a pas eu lieu (valeur &lt;= 0)
     */
    private static String heure(long epochMs) {
        return epochMs > 0
                ? Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                : "";
    }

    private void ecrireResultats() {
        completerDepuisJournal();
        Path csv = MeshConfig.DOSSIER_RESULTATS.resolve("chaos-" + label + ".csv");
        Path resume = MeshConfig.DOSSIER_RESULTATS.resolve("chaos-" + label + "-resume.txt");
        // Colonnes exigées par l'énoncé : service ciblé, heure de la panne, heure de détection,
        // action choisie et heure de récupération. Les durées dérivées suivent.
        StringBuilder sb = new StringBuilder("iteration,label,scenario,service,instance,"
                + "heure_panne,heure_detection,action,heure_recuperation,"
                + "delai_detection_ms,detecte_15s,mttr_depuis_panne_ms,mttr_depuis_detection_ms,"
                + "cycles_mapek,faux_positif\n");
        for (Injection inj : injections) {
            long det = inj.tDetection > 0 ? inj.tDetection - inj.tPanne : -1;
            long mttrPanne = inj.tRecuperation > 0 ? inj.tRecuperation - inj.tPanne : -1;
            long mttrDet = inj.tRecuperation > 0 && inj.tDetection > 0
                    ? Math.max(0, inj.tRecuperation - inj.tDetection) : -1;
            sb.append(String.join(",", Integer.toString(inj.iteration), label, inj.scenario.name(), inj.service,
                    inj.instance, heure(inj.tPanne), heure(inj.tDetection), inj.action, heure(inj.tRecuperation),
                    Long.toString(det), Boolean.toString(det >= 0 && det <= 15_000), Long.toString(mttrPanne),
                    Long.toString(mttrDet), Long.toString(inj.cycles),
                    inj.fauxPositif == null ? "" : inj.fauxPositif.toString())).append('\n');
        }
        try {
            Files.createDirectories(MeshConfig.DOSSIER_RESULTATS);
            Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
            String r = resume();
            Files.writeString(resume, r, StandardCharsets.UTF_8);
            System.out.println(r);
        } catch (IOException e) {
            Log.error("CHAOS", "Écriture des résultats impossible", e);
        }
    }

    private String resume() {
        List<Injection> reelles = injections.stream().filter(i -> i.scenario != Scenario.SPIKE).toList();
        List<Injection> pics = injections.stream().filter(i -> i.scenario == Scenario.SPIKE).toList();
        double mttrPanne = reelles.stream().filter(i -> i.tRecuperation > 0)
                .mapToLong(i -> i.tRecuperation - i.tPanne).average().orElse(Double.NaN);
        double mttrDet = reelles.stream().filter(i -> i.tRecuperation > 0 && i.tDetection > 0)
                .mapToLong(i -> Math.max(0, i.tRecuperation - i.tDetection)).average().orElse(Double.NaN);
        long detectees15 = reelles.stream().filter(i -> i.tDetection > 0 && i.tDetection - i.tPanne <= 15_000).count();
        long recuperees = reelles.stream().filter(i -> i.tRecuperation > 0).count();
        double cycles = reelles.stream().filter(i -> i.cycles > 0).mapToLong(i -> i.cycles).average().orElse(Double.NaN);
        long fp = pics.stream().filter(i -> Boolean.TRUE.equals(i.fauxPositif)).count();
        StringBuilder sb = new StringBuilder();
        sb.append("===== Résumé chaos [").append(label).append("] =====\n");
        sb.append(String.format(Locale.ROOT, "Pannes réelles injectées      : %d (récupérées : %d)%n", reelles.size(), recuperees));
        sb.append(String.format(Locale.ROOT, "MTTR moyen depuis la panne    : %.0f ms%n", mttrPanne));
        sb.append(String.format(Locale.ROOT, "MTTR moyen depuis la détection: %.0f ms%n", mttrDet));
        sb.append(String.format(Locale.ROOT, "Taux de détection (<= 15 s)   : %s%n",
                reelles.isEmpty() ? "n/a" : String.format(Locale.ROOT, "%.0f %% (%d/%d)",
                        100.0 * detectees15 / reelles.size(), detectees15, reelles.size())));
        sb.append(String.format(Locale.ROOT, "Cycles MAPE-K moyens          : %.1f%n", cycles));
        if (!pics.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "Faux positifs (pics)          : %d/%d%n", fp, pics.size()));
        }
        return sb.toString();
    }

    // ───────────────────────── Point d'entrée ─────────────────────────

    /**
     * @param args --scenario kill|degrade|overload|unavailable|spike|mixed  --iterations N  --interval S
     *             --timeout S  --services calc,storage,notif  --label nom  --seed N
     * @throws InterruptedException si interrompu
     */
    public static void main(String[] args) throws InterruptedException {
        Scenario scenario = Scenario.KILL;
        int iterations = 10;
        int intervalle = 30;
        int delaiMax = 180;
        List<String> services = List.of("calc", "storage", "notif");
        String label = "auto";
        long graine = System.currentTimeMillis();
        try {
            for (int i = 0; i < args.length; i++) {
                String opt = args[i];
                String v = i + 1 < args.length ? args[++i] : null;
                if (v == null) throw new IllegalArgumentException("Valeur manquante pour " + opt);
                switch (opt) {
                    case "--scenario" -> scenario = Scenario.valueOf(v.toUpperCase(Locale.ROOT));
                    case "--iterations" -> iterations = Integer.parseInt(v);
                    case "--interval" -> intervalle = Integer.parseInt(v);
                    case "--timeout" -> delaiMax = Integer.parseInt(v);
                    case "--services" -> {
                        services = Arrays.stream(v.split(",")).map(String::trim).toList();
                        services.forEach(ServiceType::depuisNom); // validation
                    }
                    case "--label" -> label = v.replaceAll("[^A-Za-z0-9_-]", "_");
                    case "--seed" -> graine = Long.parseLong(v);
                    default -> throw new IllegalArgumentException("Option inconnue : " + opt);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage : --scenario kill|degrade|overload|unavailable|spike|mixed --iterations N"
                    + " --interval S --timeout S --services calc,storage,notif --label nom --seed N");
            System.exit(2);
            return;
        }
        new ChaosMonkey(scenario, iterations, intervalle, delaiMax, services, label, graine).demarrer();
    }
}