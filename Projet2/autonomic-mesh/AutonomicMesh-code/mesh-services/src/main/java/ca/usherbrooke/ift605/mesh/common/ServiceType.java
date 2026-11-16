package ca.usherbrooke.ift605.mesh.common;

import java.util.Locale;

/**
 * Les trois micro-services d'AutonomicMesh avec leur port gRPC par défaut
 * (instance primaire).
 */
public enum ServiceType {
    /** Service de calcul. */
    CALC("calc", 50051),
    /** Service de stockage clé/valeur. */
    STORAGE("storage", 50052),
    /** Service de notification. */
    NOTIF("notif", 50053);

    private final String nom;
    private final int portParDefaut;

    ServiceType(String nom, int portParDefaut) {
        this.nom = nom;
        this.portParDefaut = portParDefaut;
    }

    /** @return nom logique du service (calc, storage, notif) */
    public String nom() {
        return nom;
    }

    /** @return port gRPC de l'instance primaire */
    public int portParDefaut() {
        return portParDefaut;
    }

    /**
     * Retrouve un service par son nom logique (insensible à la casse).
     *
     * @param nom calc, storage ou notif
     * @return le type correspondant
     * @throws IllegalArgumentException si le nom est inconnu
     */
    public static ServiceType depuisNom(String nom) {
        for (ServiceType t : values()) {
            if (t.nom.equals(nom.toLowerCase(Locale.ROOT))) {
                return t;
            }
        }
        throw new IllegalArgumentException("Service inconnu : " + nom + " (attendu : calc, storage, notif)");
    }
}
