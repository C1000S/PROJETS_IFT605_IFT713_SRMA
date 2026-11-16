package ca.usherbrooke.ift605.mesh.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Gestion des fichiers PID ({@code run/pids/<instanceId>.pid}).
 * <p>
 * Chaque service écrit son PID au démarrage. Le ChaosMonkey s'en sert pour cibler
 * uniquement les processus du projet, de façon portable (Linux, macOS, Windows).
 */
public final class PidFiles {

    private PidFiles() {
    }

    private static Path fichier(String instanceId) {
        return MeshConfig.DOSSIER_PIDS.resolve(instanceId + ".pid");
    }

    /**
     * Écrit le PID du processus courant pour l'instance donnée.
     *
     * @param instanceId identifiant de l'instance (ex. calc-1)
     */
    public static void ecrire(String instanceId) {
        try {
            Files.createDirectories(MeshConfig.DOSSIER_PIDS);
            Files.writeString(fichier(instanceId), Long.toString(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("PID", "Impossible d'écrire le fichier PID de " + instanceId + " : " + e.getMessage());
        }
    }

    /**
     * Lit le PID enregistré pour une instance.
     *
     * @param instanceId identifiant de l'instance
     * @return le PID, ou vide si le fichier est absent ou illisible
     */
    public static Optional<Long> lire(String instanceId) {
        try {
            Path f = fichier(instanceId);
            if (!Files.exists(f)) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(Files.readString(f, StandardCharsets.UTF_8).trim()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Supprime le fichier PID, seulement s'il contient encore le PID du processus courant
     * (évite d'effacer celui d'une instance redémarrée entre-temps).
     *
     * @param instanceId identifiant de l'instance
     */
    public static void supprimerSiCourant(String instanceId) {
        long moi = ProcessHandle.current().pid();
        if (lire(instanceId).filter(pid -> pid == moi).isPresent()) {
            try {
                Files.deleteIfExists(fichier(instanceId));
            } catch (IOException ignored) {
                // arrêt du processus : rien de plus à faire
            }
        }
    }
}
