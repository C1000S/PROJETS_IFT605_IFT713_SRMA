package ca.usherbrooke.ift605.mapek.model;

import java.util.Set;

/**
 * Mesure de santé produite par une sonde et transmise au Monitor en JSON (une ligne par mesure).
 * <p>
 * Champs exigés par l'énoncé : service, timestamp, statut, latenceMs, cpuLoad, uptimeMs.
 * On ajoute instanceId et port pour distinguer primaire, secours et répliques.
 * <p>
 * Classe simple à champs publics pour la sérialisation Gson ; les instances sont
 * traitées comme immuables une fois reçues.
 */
public class MesureSante {

    /** Statuts acceptés. */
    public static final Set<String> STATUTS = Set.of("SERVING", "DEGRADED", "DOWN");

    /** Nom logique du service (calc, storage, notif). */
    public String service;
    /** Identifiant de l'instance mesurée (calc-1, calc-backup, calc-2...). */
    public String instanceId;
    /** Port gRPC de l'instance. */
    public int port;
    /** Horodatage de la mesure (epoch ms). */
    public long timestamp;
    /** SERVING, DEGRADED ou DOWN. */
    public String statut;
    /** Latence aller-retour du HealthCheck mesurée par la sonde (ms). */
    public long latenceMs;
    /** Charge CPU rapportée par le service (0..1). */
    public double cpuLoad;
    /** Temps depuis le démarrage de l'instance (ms). */
    public long uptimeMs;

    /** Constructeur vide requis par Gson. */
    public MesureSante() {
    }

    /**
     * @param service    nom logique
     * @param instanceId identifiant d'instance
     * @param port       port gRPC
     * @param timestamp  horodatage epoch ms
     * @param statut     SERVING / DEGRADED / DOWN
     * @param latenceMs  latence mesurée
     * @param cpuLoad    charge CPU
     * @param uptimeMs   uptime
     */
    public MesureSante(String service, String instanceId, int port, long timestamp, String statut,
                       long latenceMs, double cpuLoad, long uptimeMs) {
        this.service = service;
        this.instanceId = instanceId;
        this.port = port;
        this.timestamp = timestamp;
        this.statut = statut;
        this.latenceMs = latenceMs;
        this.cpuLoad = cpuLoad;
        this.uptimeMs = uptimeMs;
    }

    /**
     * Vérifie la cohérence d'une mesure reçue par le réseau.
     *
     * @return null si la mesure est valide, sinon la raison du rejet
     */
    public String erreurValidation() {
        if (service == null || service.isBlank()) return "service manquant";
        if (instanceId == null || instanceId.isBlank()) return "instanceId manquant";
        if (statut == null || !STATUTS.contains(statut)) return "statut invalide : " + statut;
        if (timestamp <= 0) return "timestamp invalide";
        if (latenceMs < 0) return "latence négative";
        if (cpuLoad < 0 || cpuLoad > 1.0) return "cpuLoad hors [0,1] : " + cpuLoad;
        return null;
    }

    /** @return vrai si l'instance est saine et rapide selon les seuils donnés */
    public boolean estSaine(long seuilLatenceMs, double seuilCpu) {
        return "SERVING".equals(statut) && latenceMs <= seuilLatenceMs && cpuLoad <= seuilCpu;
    }

    @Override
    public String toString() {
        return String.format("%s=%s(%dms,cpu %.2f)", instanceId, statut, latenceMs, cpuLoad);
    }
}
