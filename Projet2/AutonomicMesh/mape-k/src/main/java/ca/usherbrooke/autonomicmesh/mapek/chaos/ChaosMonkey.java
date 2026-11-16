package ca.usherbrooke.autonomicmesh.mapek.chaos;

import ca.usherbrooke.autonomicmesh.mapek.monitor.Probe;
import ca.usherbrooke.autonomicmesh.mapek.process.ServiceProcessManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ChaosMonkey {

    private final ServiceProcessManager processManager;
    private final Map<String, Probe> probes;

    private final List<String> services =
            List.of("CalcService", "StorageService", "NotifService");

    private final AtomicInteger nextService = new AtomicInteger(0);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    public ChaosMonkey(
            ServiceProcessManager processManager,
            Map<String, Probe> probes) {

        this.processManager = processManager;
        this.probes = probes;
    }

    // Démarrer le planificateur
    public void start() {

        System.out.println("[ChaosMonkey] Démarrage du planificateur.");
        System.out.println("[ChaosMonkey] Première injection dans 5 secondes.");

        scheduler.scheduleWithFixedDelay(() -> {

            try {
                injectFailure();

            } catch (Exception e) {

                System.err.println(
                        "[ChaosMonkey] Erreur pendant l'injection : "
                                + e.getMessage()
                );

                e.printStackTrace();
            }

        }, 5, 30, TimeUnit.SECONDS);
    }

    // Injecter une panne uniquement lorsque tous les services sont opérationnels
    private void injectFailure() {

        System.out.println(
                "\n[ChaosMonkey] Vérification de l'état des services..."
        );

        // Vérifier l'état de tous les microservices
        for (String service : services) {

            Probe probe = probes.get(service);

            if (probe == null) {

                System.out.println(
                        "[ChaosMonkey] Sonde absente pour " + service
                );

                return;
            }

            String status = probe.getLastStatus();

            boolean processRunning =
                    processManager.isRunning(service);

            System.out.println(
                    "[ChaosMonkey] " + service
                            + " | Processus actif : " + processRunning
                            + " | État gRPC : " + status
            );

            // Reporter l'injection si un service n'est pas opérationnel
            if (!processRunning || !probe.isHealthy()) {

                System.out.println(
                        "[ChaosMonkey] Injection reportée : "
                                + service + " n'est pas opérationnel."
                );

                return;
            }
        }

        // Tous les services sont opérationnels
        int index = Math.floorMod(
                nextService.getAndIncrement(),
                services.size()
        );

        String service = services.get(index);

        long timestamp = System.currentTimeMillis();

        System.out.println("\n======================================");
        System.out.println("[ChaosMonkey] INJECTION DE PANNE");
        System.out.println("[ChaosMonkey] Service : " + service);
        System.out.println("[ChaosMonkey] Timestamp : " + timestamp);
        System.out.println("======================================");

        processManager.stopService(service);
    }

    // Arrêter le planificateur
    public void stop() {

        scheduler.shutdownNow();

        System.out.println("[ChaosMonkey] Arrêté.");
    }
}
