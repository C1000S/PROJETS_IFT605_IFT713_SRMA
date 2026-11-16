package ca.usherbrooke.autonomicmesh.mapek.knowledge;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Map;
import java.util.List;
import java.util.Properties;

public class KnowledgeBase {

    private final Map<String, Measurement> currentStates =
            new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<Measurement> history =
            new ConcurrentLinkedDeque<>();

    // Tables de routage
    private final Map<String, String> routes =
            new ConcurrentHashMap<>();

    private final Map<String, String> backupRoutes =
            new ConcurrentHashMap<>();

    private static final int MAX_HISTORY = 1000;

    // Fichier partagé entre MAPE-K et le client gRPC
    private static final Path ROUTES_FILE = Path.of(
            "C:\\Users\\Mame Tabara\\Documents\\AutonomicMesh\\logs\\routes.properties"
    );

    // Ports associés aux instances principales et de secours
    private static final Map<String, Integer> SERVICE_PORTS = Map.of(
            "CalcService", 50051,
            "CalcServiceBackup", 50054,
            "StorageService", 50052,
            "StorageServiceBackup", 50055,
            "NotifService", 50053,
            "NotifServiceBackup", 50056
    );

    public void update(Measurement measurement) {
        currentStates.put(measurement.service(), measurement);

        history.addLast(measurement);

        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }

        System.out.println("[KnowledgeBase] Mesure enregistrée : "
                + measurement.service() + " = "
                + measurement.status());
    }

    public Measurement getState(String service) {
        return currentStates.get(service);
    }

    public Map<String, Measurement> getAllStates() {
        return Map.copyOf(currentStates);
    }

    public List<Measurement> getHistory() {
        return List.copyOf(history);
    }

    // Configure la destination de secours d'un service
    public void configureBackupRoute(String service, String backup) {
        backupRoutes.put(service, backup);
        routes.putIfAbsent(service, service);

        saveRoutesToFile();

        System.out.println("[KnowledgeBase] Route de secours configurée : "
                + service + " -> " + backup);
    }

    // Retourne la destination actuellement utilisée
    public String getRoute(String service) {
        return routes.getOrDefault(service, service);
    }

    // Réachemine le service vers sa destination de secours
    public boolean reroute(String service) {
        String backup = backupRoutes.get(service);

        if (backup == null) {
            System.out.println("[KnowledgeBase] Aucune route de secours "
                    + "configurée pour " + service);
            return false;
        }

        routes.put(service, backup);
        saveRoutesToFile();

        System.out.println("[KnowledgeBase] REACHEMINEMENT : "
                + service + " -> " + backup);

        return true;
    }

    // Retourne le service vers sa destination initiale
    public void restoreRoute(String service) {
        routes.put(service, service);
        saveRoutesToFile();

        System.out.println("[KnowledgeBase] Route restaurée : "
                + service + " -> " + service);
    }

    public Map<String, String> getAllRoutes() {
        return Map.copyOf(routes);
    }

    // Enregistre les ports actifs dans un fichier partagé
    private synchronized void saveRoutesToFile() {
        Properties properties = new Properties();

        for (Map.Entry<String, String> entry : routes.entrySet()) {
            String service = entry.getKey();
            String destination = entry.getValue();

            Integer port = SERVICE_PORTS.get(destination);

            if (port != null) {
                properties.setProperty(service, String.valueOf(port));
            }
        }

        try {
            Files.createDirectories(ROUTES_FILE.getParent());

            Path temporaryFile = ROUTES_FILE.resolveSibling(
                    "routes.properties.tmp"
            );

            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                properties.store(output, "AutonomicMesh - Table de routage");
            }

            try {
                Files.move(
                        temporaryFile,
                        ROUTES_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException e) {
                Files.move(
                        temporaryFile,
                        ROUTES_FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            System.out.println("[KnowledgeBase] Table de routage sauvegardée.");

        } catch (IOException e) {
            System.err.println(
                    "[KnowledgeBase] Erreur de sauvegarde des routes : "
                    + e.getMessage()
            );
        }
    }
}
