package ca.usherbrooke.autonomicmesh.mapek.process;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ServiceProcessManager {

    private static final String PROJECT_ROOT =
            "C:\\Users\\Mame Tabara\\Documents\\AutonomicMesh";

    private static final String MAVEN =
            "C:\\apache-maven-3.10.0-bin\\apache-maven-3.10.0\\bin\\mvn.cmd";

    private static class ServiceConfig {
        final String module;
        final String mainClass;
        final int port;

        ServiceConfig(String module, String mainClass, int port) {
            this.module = module;
            this.mainClass = mainClass;
            this.port = port;
        }
    }

    // Configuration des six instances
    private final Map<String, ServiceConfig> configurations = Map.of(

            "CalcService",
            new ServiceConfig(
                    "mesh-services\\calc-service",
                    "ca.usherbrooke.autonomicmesh.calc.CalcServer",
                    50051
            ),

            "CalcServiceBackup",
            new ServiceConfig(
                    "mesh-services\\calc-service",
                    "ca.usherbrooke.autonomicmesh.calc.CalcServer",
                    50054
            ),

            "StorageService",
            new ServiceConfig(
                    "mesh-services\\storage-service",
                    "ca.usherbrooke.autonomicmesh.storage.StorageServer",
                    50052
            ),

            "StorageServiceBackup",
            new ServiceConfig(
                    "mesh-services\\storage-service",
                    "ca.usherbrooke.autonomicmesh.storage.StorageServer",
                    50055
            ),

            "NotifService",
            new ServiceConfig(
                    "mesh-services\\notif-service",
                    "ca.usherbrooke.autonomicmesh.notif.NotifServer",
                    50053
            ),

            "NotifServiceBackup",
            new ServiceConfig(
                    "mesh-services\\notif-service",
                    "ca.usherbrooke.autonomicmesh.notif.NotifServer",
                    50056
            )
    );

    private final Map<String, Process> processes =
            new ConcurrentHashMap<>();

    /**
     * Démarre les trois services principaux.
     */
    public synchronized void startAll() {

        startService("CalcService");
        startService("StorageService");
        startService("NotifService");
    }

    /**
     * Démarre les trois instances secondaires.
     */
    public synchronized void startBackupServices() {

        System.out.println(
                "\n[ProcessManager] Démarrage des instances secondaires..."
        );

        startService("CalcServiceBackup");
        startService("StorageServiceBackup");
        startService("NotifServiceBackup");
    }

    /**
     * Démarre un service sur le port configuré.
     */
    public synchronized void startService(String service) {

        ServiceConfig config = configurations.get(service);

        if (config == null) {
            System.err.println(
                    "[ProcessManager] Service inconnu : " + service
            );
            return;
        }

        Process existing = processes.get(service);

        if (existing != null && existing.isAlive()) {
            System.out.println(
                    "[ProcessManager] " + service + " est déjà actif."
            );
            return;
        }

        File moduleDirectory = new File(PROJECT_ROOT, config.module);

        if (!moduleDirectory.isDirectory()) {
            System.err.println(
                    "[ProcessManager] Dossier introuvable : "
                            + moduleDirectory.getAbsolutePath()
            );
            return;
        }

        if (!new File(MAVEN).isFile()) {
            System.err.println(
                    "[ProcessManager] Maven introuvable : " + MAVEN
            );
            return;
        }

        try {
            File logDirectory = new File(PROJECT_ROOT, "logs");

            if (!logDirectory.exists() && !logDirectory.mkdirs()) {
                throw new IOException(
                        "Impossible de créer le dossier logs."
                );
            }

            File logFile = new File(
                    logDirectory,
                    service + ".log"
            );

            String command =
                    "\"" + MAVEN + "\" -q "
                    + "org.codehaus.mojo:exec-maven-plugin:3.5.0:java "
                    + "-Dexec.mainClass=" + config.mainClass
                    + " -Dexec.args=" + config.port;

            ProcessBuilder builder = new ProcessBuilder(
                    "cmd.exe", "/c", command
            );

            builder.directory(moduleDirectory);
            builder.redirectErrorStream(true);
            builder.redirectOutput(
                    ProcessBuilder.Redirect.appendTo(logFile)
            );

            Process process = builder.start();
            processes.put(service, process);

            System.out.println(
                    "[ProcessManager] " + service
                            + " lancé sur le port " + config.port
                            + ". PID : " + process.pid()
            );

            System.out.println(
                    "[ProcessManager] Journal : "
                            + logFile.getAbsolutePath()
            );

            if (process.waitFor(2, TimeUnit.SECONDS)) {

                processes.remove(service, process);

                System.err.println(
                        "[ProcessManager] Échec du démarrage de "
                                + service + ". Code : "
                                + process.exitValue()
                );
            }

        } catch (IOException e) {

            System.err.println(
                    "[ProcessManager] Erreur de démarrage de "
                            + service + " : " + e.getMessage()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            System.err.println(
                    "[ProcessManager] Vérification interrompue."
            );
        }
    }

    /**
     * Arrête un service et ses processus descendants.
     */
    public synchronized void stopService(String service) {

        Process process = processes.get(service);

        if (process == null) {
            System.err.println(
                    "[ProcessManager] Aucun processus géré pour "
                            + service
            );
            return;
        }

        System.out.println(
                "[ProcessManager] Arrêt demandé pour " + service
        );

        stopProcessTree(process);

        processes.remove(service, process);

        System.out.println(
                "[ProcessManager] Arrêt terminé pour " + service
        );
    }

    /**
     * Redémarre un service principal ou secondaire.
     */
    public synchronized void restartService(String service) {

        if (!configurations.containsKey(service)) {
            System.err.println(
                    "[ProcessManager] Service inconnu : " + service
            );
            return;
        }

        System.out.println(
                "[ProcessManager] Redémarrage demandé : " + service
        );

        stopService(service);
        startService(service);
    }

    /**
     * Vérifie si un processus est actif.
     */
    public boolean isRunning(String service) {

        Process process = processes.get(service);

        return process != null && process.isAlive();
    }

    /**
     * Arrête l'arbre de processus.
     */
    private void stopProcessTree(Process process) {

        List<ProcessHandle> descendants =
                new ArrayList<>(
                        process.toHandle().descendants().toList()
                );

        for (int i = descendants.size() - 1; i >= 0; i--) {

            ProcessHandle child = descendants.get(i);

            if (child.isAlive()) {
                child.destroy();
            }
        }

        process.destroy();

        try {

            process.waitFor(5, TimeUnit.SECONDS);

            for (ProcessHandle child : descendants) {

                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }

            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            for (ProcessHandle child : descendants) {

                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }

            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Arrête tous les services principaux et secondaires.
     */
    public synchronized void stopAll() {

        System.out.println(
                "[ProcessManager] Arrêt de tous les processus gérés..."
        );

        for (String service :
                new ArrayList<>(processes.keySet())) {

            stopService(service);
        }

        System.out.println(
                "[ProcessManager] Tous les processus sont arrêtés."
        );
    }
}
