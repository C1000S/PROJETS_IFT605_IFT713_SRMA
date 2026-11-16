package ca.usherbrooke.autonomicmesh.mapek.chaos;

import ca.usherbrooke.autonomicmesh.mapek.knowledge.KnowledgeBase;
import ca.usherbrooke.autonomicmesh.mapek.monitor.Probe;
import ca.usherbrooke.autonomicmesh.mapek.monitor.Monitor;
import ca.usherbrooke.autonomicmesh.mapek.process.ServiceProcessManager;

import java.util.HashMap;
import java.util.Map;

public class ChaosApplication {

    public static void main(String[] args) {

        System.out.println("======================================");
        System.out.println("       AUTONOMIC MESH - CHAOS TEST");
        System.out.println("======================================");

        // Initialisation de la base de connaissances
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

        // Initialisation du gestionnaire de processus
        ServiceProcessManager processManager =
                new ServiceProcessManager();

        // Démarrage des microservices
        System.out.println("\n[ChaosApplication] Démarrage des services...");
        processManager.startAll();

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            processManager.stopAll();
            return;
        }

        // Démarrage des sondes
        System.out.println("\n[ChaosApplication] Démarrage des sondes...");
        probes.values().forEach(Probe::start);

        // Création et démarrage de ChaosMonkey
        ChaosMonkey chaosMonkey = new ChaosMonkey(
                processManager,
                probes
        );

        chaosMonkey.start();

        System.out.println("\n======================================");
        System.out.println("[ChaosApplication] Application démarrée.");
        System.out.println("[ChaosApplication] Surveillance active.");
        System.out.println("[ChaosApplication] Injection contrôlée des pannes.");
        System.out.println("======================================");

        // Arrêt propre de l'application
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            System.out.println("\n[ChaosApplication] Arrêt...");

            chaosMonkey.stop();

            probes.values().forEach(Probe::stop);

            processManager.stopAll();

            monitorThread.interrupt();

            System.out.println("[ChaosApplication] Arrêt terminé.");
        }));
    }
}
