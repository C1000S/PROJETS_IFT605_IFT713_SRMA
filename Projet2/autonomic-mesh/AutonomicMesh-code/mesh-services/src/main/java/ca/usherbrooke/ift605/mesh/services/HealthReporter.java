package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.proto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.ServingStatus;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Calcule la réponse HealthCheck commune aux trois services.
 * <ul>
 *   <li>DOWN : une faute UNAVAILABLE est active (le processus vit, mais ne sert plus) ;</li>
 *   <li>DEGRADED : latence injectée ou charge CPU supérieure au seuil ;</li>
 *   <li>SERVING : sinon.</li>
 * </ul>
 * Une instance injoignable (processus tué) est classée DOWN par la sonde, pas ici.
 * <p>
 * La charge CPU réelle est échantillonnée en arrière-plan une fois par seconde par un
 * thread dédié, et le HealthCheck renvoie la dernière valeur connue. Lire
 * {@code getProcessCpuLoad()} à chaque appel s'est révélé lent sous Windows
 * (100 à 250 ms par HealthCheck, parfois plus de 2 s), ce qui provoquait des
 * faux positifs PANNE_TOTALE par dépassement du délai de la sonde.
 */
public final class HealthReporter {

    /** Période d'échantillonnage de la charge CPU (ms). */
    static final long PERIODE_ECHANTILLON_CPU_MS = 1_000;

    /**
     * Un seul thread d'échantillonnage par processus, partagé par toutes les instances
     * de HealthReporter (un processus = une instance de service en pratique).
     */
    private static final ScheduledExecutorService ECHANTILLONNEUR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "echantillonnage-cpu");
                t.setDaemon(true); // ne retient pas l'arrêt du processus
                return t;
            });

    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /** Dernière charge CPU mesurée (0..1), mise à jour par le thread d'échantillonnage. */
    private static volatile double cpuMesure = 0.0;

    static {
        ECHANTILLONNEUR.scheduleAtFixedRate(() -> {
            double v = OS.getProcessCpuLoad();
            if (v >= 0) { // valeur négative = mesure pas encore disponible
                cpuMesure = v;
            }
        }, 0, PERIODE_ECHANTILLON_CPU_MS, TimeUnit.MILLISECONDS);
    }

    private final String instanceId;
    private final FaultState fautes;
    private final long demarrageMs = System.currentTimeMillis();

    /**
     * @param instanceId identifiant de l'instance
     * @param fautes     état des fautes injectées
     */
    public HealthReporter(String instanceId, FaultState fautes) {
        this.instanceId = instanceId;
        this.fautes = fautes;
    }

    /** @return charge CPU du processus (dernière mesure réelle, ou valeur simulée), entre 0 et 1 */
    public double chargeCpu() {
        double force = fautes.cpuForce();
        return force >= 0 ? force : cpuMesure;
    }

    /**
     * Construit la réponse de santé. La latence injectée est appliquée avant de répondre,
     * pour que la sonde la mesure réellement. Aucun appel coûteux n'est fait ici.
     *
     * @return réponse HealthCheck
     */
    public HealthResp construire() {
        fautes.attendreLatenceInjectee();
        double cpu = chargeCpu();
        ServingStatus statut;
        if (fautes.indisponible()) {
            statut = ServingStatus.DOWN;
        } else if (fautes.latenceAjouteeMs() > 0 || cpu > MeshConfig.SEUIL_CPU) {
            statut = ServingStatus.DEGRADED;
        } else {
            statut = ServingStatus.SERVING;
        }
        return HealthResp.newBuilder()
                .setStatus(statut)
                .setUptimeMs(System.currentTimeMillis() - demarrageMs)
                .setCpuLoad(cpu)
                .setInstanceId(instanceId)
                .build();
    }
}