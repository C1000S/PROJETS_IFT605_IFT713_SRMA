package ca.usherbrooke.autonomicmesh.mapek.app;

import ca.usherbrooke.autonomicmesh.mapek.knowledge.KnowledgeBase;
import ca.usherbrooke.autonomicmesh.mapek.monitor.Monitor;
import ca.usherbrooke.autonomicmesh.mapek.monitor.Probe;
import ca.usherbrooke.autonomicmesh.mapek.analyzer.Analyzer;
import ca.usherbrooke.autonomicmesh.mapek.planner.Planner;
import ca.usherbrooke.autonomicmesh.mapek.executor.Executor;
import ca.usherbrooke.autonomicmesh.mapek.controller.MapeKController;
import ca.usherbrooke.autonomicmesh.mapek.process.ServiceProcessManager;
import ca.usherbrooke.autonomicmesh.mapek.chaos.ChaosMonkey;

import java.util.HashMap;
import java.util.Map;

public class MapeKApplication {

    public static void main(String[] args) {

        System.out.println("======================================");
        System.out.println("       AUTONOMIC MESH - MAPE-K");
        System.out.println("======================================");

        KnowledgeBase knowledgeBase = new KnowledgeBase();

        // Démarrage du serveur Monitor
        Monitor monitor = new Monitor(knowledgeBase, 9090);

        Thread monitorThread = new Thread(monitor::start);
        monitorThread.setName("Monitor-TCP");
        monitorThread.start();

        try {
            Thread.sleep(500);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            return;
        }

        // Création des sondes
        Map<String, Probe> probes = new HashMap<>();

        probes.put(
                "CalcService",
                new Probe("CalcService", 50051, "localhost", 9090)
        );

        probes.put(
                "StorageService",
                new Probe("StorageService", 50052, "localhost", 9090)
        );

        probes.put(
                "NotifService",
                new Probe("NotifService", 50053, "localhost", 9090)
        );

        // Gestionnaire des processus
        ServiceProcessManager processManager =
                new ServiceProcessManager();

        System.out.println("\n[MAPE-K] Démarrage des microservices...");

        processManager.startAll();

        processManager.startBackupServices();

        try {
            Thread.sleep(3000);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            processManager.stopAll();
            return;
        }

        // Démarrage des sondes
        System.out.println("\n[MAPE-K] Démarrage des sondes...");

        probes.values().forEach(Probe::start);

        // Création des composants MAPE-K
        Analyzer analyzer = new Analyzer();
        Planner planner = new Planner();

        // Configuration des routes de secours
        knowledgeBase.configureBackupRoute(
                "CalcService", "CalcServiceBackup"
        );

        knowledgeBase.configureBackupRoute(
                "StorageService", "StorageServiceBackup"
        );

        knowledgeBase.configureBackupRoute(
                "NotifService", "NotifServiceBackup"
        );

        Executor executor = new Executor(
                probes,
                processManager,
                knowledgeBase
        );

        MapeKController controller = new MapeKController(
                knowledgeBase,
                analyzer,
                planner,
                executor
        );

        // Démarrage du contrôleur MAPE-K
        controller.start();

        // Démarrage de ChaosMonkey avec les sondes
        ChaosMonkey chaosMonkey = new ChaosMonkey(
                processManager,
                probes
        );

        chaosMonkey.start();

        System.out.println("\n======================================");
        System.out.println("[MAPE-K] Application démarrée.");
        System.out.println("[MAPE-K] Monitor actif sur le port 9090.");
        System.out.println("[MAPE-K] Surveillance des trois services active.");
        System.out.println("[MAPE-K] Cycle automatique toutes les 10 secondes.");
        System.out.println("[ChaosMonkey] Module de test des pannes actif.");
        System.out.println("======================================");

        // Arrêt propre de l'application
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            System.out.println("\n[MAPE-K] Arrêt du système...");

            chaosMonkey.stop();

            probes.values().forEach(Probe::stop);

            controller.stop();

            processManager.stopAll();

            monitorThread.interrupt();

            System.out.println("[MAPE-K] Arrêt terminé.");
        }));
    }
}
