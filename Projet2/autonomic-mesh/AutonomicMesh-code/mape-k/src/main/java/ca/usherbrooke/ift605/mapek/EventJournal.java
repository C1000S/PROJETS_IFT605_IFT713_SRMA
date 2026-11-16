package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.MeshConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Journal CSV des événements MAPE-K ({@code results/mapek-events.csv}) et de l'overhead
 * du gestionnaire ({@code results/overhead.csv}). Ces fichiers alimentent le rapport
 * (délai de détection, MTTR, cycles, faux positifs, consommation).
 * <p>
 * Le fichier d'événements est recréé à chaque démarrage du gestionnaire.
 */
public class EventJournal implements AutoCloseable {

    /** En-tête du CSV d'événements, lu aussi par le ChaosMonkey. */
    public static final String ENTETE =
            "timestamp_ms,iso,mode,cycle,evenement,service,instance,symptome,action,duree_ms,cycles,detail";

    private final String mode;
    private BufferedWriter evenements;
    private BufferedWriter overhead;

    /**
     * @param mode AUTO ou MANUEL (copié dans chaque ligne)
     */
    public EventJournal(String mode) {
        this.mode = mode;
        try {
            Files.createDirectories(MeshConfig.DOSSIER_RESULTATS);
            evenements = ouvrir(MeshConfig.JOURNAL_EVENEMENTS, ENTETE);
            overhead = ouvrir(MeshConfig.DOSSIER_RESULTATS.resolve("overhead.csv"),
                    "timestamp_ms,mode,cycle,cpu_processus,heap_utilise_mo,threads");
        } catch (IOException e) {
            Log.error("JOURNAL", "Journal CSV indisponible, la boucle continue sans journal", e);
        }
    }

    private static BufferedWriter ouvrir(Path p, String entete) throws IOException {
        BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        w.write(entete);
        w.newLine();
        w.flush();
        return w;
    }

    /**
     * Ajoute un événement.
     *
     * @param cycle     numéro de cycle MAPE-K
     * @param evenement DETECTION, ACTION, RECUPERATION, RECUPERATION_SPONTANEE, ALERTE...
     * @param service   service logique
     * @param instance  instance concernée
     * @param symptome  symptôme (ou vide)
     * @param action    action (ou vide)
     * @param dureeMs   durée associée (ex. détection → récupération), ou -1
     * @param cycles    nombre de cycles associés, ou -1
     * @param detail    texte libre (sans virgule de préférence)
     */
    public synchronized void evenement(long cycle, String evenement, String service, String instance,
                                       Object symptome, Object action, long dureeMs, long cycles, String detail) {
        if (evenements == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            evenements.write(String.join(",", Long.toString(now), Instant.ofEpochMilli(now).toString(), mode,
                    Long.toString(cycle), evenement, nz(service), nz(instance), nz(symptome), nz(action),
                    Long.toString(dureeMs), Long.toString(cycles), nz(detail).replace(',', ';')));
            evenements.newLine();
            evenements.flush();
        } catch (IOException e) {
            Log.warn("JOURNAL", "Écriture impossible : " + e.getMessage());
        }
    }

    /**
     * Ajoute une mesure d'overhead du gestionnaire.
     *
     * @param cycle   numéro de cycle
     * @param cpu     charge CPU du processus gestionnaire (0..1)
     * @param heapMo  mémoire heap utilisée (Mo)
     * @param threads nombre de threads actifs
     */
    public synchronized void overhead(long cycle, double cpu, double heapMo, int threads) {
        if (overhead == null) {
            return;
        }
        try {
            overhead.write(String.format(java.util.Locale.ROOT, "%d,%s,%d,%.4f,%.2f,%d",
                    System.currentTimeMillis(), mode, cycle, cpu, heapMo, threads));
            overhead.newLine();
            overhead.flush();
        } catch (IOException e) {
            Log.warn("JOURNAL", "Écriture overhead impossible : " + e.getMessage());
        }
    }

    private static String nz(Object o) {
        return o == null ? "" : o.toString();
    }

    @Override
    public synchronized void close() {
        try {
            if (evenements != null) evenements.close();
            if (overhead != null) overhead.close();
        } catch (IOException ignored) {
            // fermeture à l'arrêt
        }
    }
}
