package ca.usherbrooke.ift605.mapek.infra;

import ca.usherbrooke.ift605.mapek.model.InstanceInfo;
import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.common.PidFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lance, arrête et relance les processus Java du maillage avec {@link ProcessBuilder}.
 * <p>
 * Chaque instance est un processus indépendant ({@code java -cp <classpath> ServiceMain ...})
 * dont la sortie est redirigée vers {@code logs/<instanceId>.log}. Le classpath utilisé est
 * celui du gestionnaire (le JAR {@code mape-k-all.jar} contient aussi les services).
 */
public class ServiceManager {

    private static final String CLASSE_SERVICE = "ca.usherbrooke.ift605.mesh.services.ServiceMain";
    private static final String CLASSE_SONDES = "ca.usherbrooke.ift605.mapek.sonde.ProbeAgent";

    private final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    private final String classpath = System.getProperty("java.class.path");
    private final Map<String, Process> processus = new ConcurrentHashMap<>();

    /**
     * Démarre l'instance décrite.
     *
     * @param info instance à démarrer
     * @return le PID du nouveau processus
     * @throws IOException si le processus ne peut pas être lancé
     */
    public long demarrer(InstanceInfo info) throws IOException {
        return lancer(info.instanceId(), List.of(CLASSE_SERVICE, info.service(),
                Integer.toString(info.port()), info.instanceId()));
    }

    /**
     * Démarre le processus des sondes (ProbeAgent).
     *
     * @return le PID du processus des sondes
     * @throws IOException si le processus ne peut pas être lancé
     */
    public long demarrerSondes() throws IOException {
        return lancer("sondes", List.of(CLASSE_SONDES));
    }

    private long lancer(String nom, List<String> argsJava) throws IOException {
        Files.createDirectories(MeshConfig.DOSSIER_LOGS);
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.addAll(argsJava);
        File log = MeshConfig.DOSSIER_LOGS.resolve(nom + ".log").toFile();
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start();
        processus.put(nom, p);
        return p.pid();
    }

    /**
     * Arrête puis relance une instance. Si le processus est encore vivant (ex. service
     * bloqué en DOWN), il est d'abord terminé pour libérer le port.
     *
     * @param info instance à relancer
     * @return le PID du nouveau processus
     * @throws IOException si la relance échoue
     */
    public long redemarrer(InstanceInfo info) throws IOException {
        arreter(info.instanceId());
        return demarrer(info);
    }

    /**
     * Termine le processus d'une instance (SIGTERM puis kill forcé après 3 s).
     * Couvre aussi le cas d'un processus lancé hors du gestionnaire, via son fichier PID.
     *
     * @param instanceId instance à arrêter
     */
    public void arreter(String instanceId) {
        Optional<ProcessHandle> h = Optional.ofNullable(processus.remove(instanceId))
                .map(Process::toHandle)
                .or(() -> PidFiles.lire(instanceId).flatMap(ProcessHandle::of));
        h.filter(ProcessHandle::isAlive).ifPresent(ph -> {
            ph.destroy();
            try {
                ph.onExit().get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                ph.destroyForcibly();
                try {
                    ph.onExit().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    Log.warn("SERVICES", "Le processus " + ph.pid() + " ne s'arrête pas");
                }
            }
        });
    }

    /** @return vrai si le processus lancé pour cette instance est vivant */
    public boolean estVivant(String instanceId) {
        Process p = processus.get(instanceId);
        return p != null && p.isAlive();
    }

    /** Termine tous les processus enfants (appelé à l'arrêt du gestionnaire). */
    public void arreterTout() {
        for (String id : List.copyOf(processus.keySet())) {
            arreter(id);
        }
    }
}
