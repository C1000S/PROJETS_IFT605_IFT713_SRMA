package ca.usherbrooke.ift605.mesh.common;

import java.nio.file.Path;

/**
 * Constantes partagées par tous les modules (ports, périodes, seuils, dossiers).
 * Les seuils d'analyse correspondent à la configuration minimale de l'énoncé ;
 * ils peuvent être surchargés par les arguments du gestionnaire autonome.
 */
public final class MeshConfig {

    private MeshConfig() {
    }

    /** Hôte local : tout le maillage tourne sur la même machine. */
    public static final String HOTE = "localhost";

    /** Port TCP du canal IPC sondes → Monitor. */
    public static final int PORT_IPC = 9090;

    /** Port gRPC du registre exposé par le gestionnaire autonome. */
    public static final int PORT_REGISTRE = 50050;

    /** Décalage de port des instances de secours (calc-backup = 50051 + 100). */
    public static final int DECALAGE_SECOURS = 100;

    /** Décalage de port des répliques créées par mise à l'échelle. */
    public static final int DECALAGE_REPLIQUE = 200;

    /** Période des sondes (ms). */
    public static final long PERIODE_SONDE_MS = 5_000;

    /** Délai maximal d'un HealthCheck avant de considérer le service DOWN (ms). */
    public static final long DELAI_HEALTHCHECK_MS = 2_000;

    /** Période de la boucle MAPE-K (s). */
    public static final long PERIODE_MAPEK_S = 10;

    /** Seuil de dégradation : latence (ms). */
    public static final long SEUIL_LATENCE_MS = 500;

    /** Seuil de surcharge : charge CPU (0..1). */
    public static final double SEUIL_CPU = 0.90;

    /** Une mesure plus vieille que cette durée est considérée périmée (ms). */
    public static final long PEREMPTION_MESURE_MS = 20_000;

    /** Dossier des fichiers PID écrits par les services (lus par le ChaosMonkey). */
    public static final Path DOSSIER_PIDS = Path.of("run", "pids");

    /** Dossier des journaux des processus enfants. */
    public static final Path DOSSIER_LOGS = Path.of("logs");

    /** Dossier des résultats CSV (événements MAPE-K, chaos, overhead). */
    public static final Path DOSSIER_RESULTATS = Path.of("results");

    /** Journal CSV des événements MAPE-K (écrit par le gestionnaire, lu par le ChaosMonkey). */
    public static final Path JOURNAL_EVENEMENTS = DOSSIER_RESULTATS.resolve("mapek-events.csv");
}
