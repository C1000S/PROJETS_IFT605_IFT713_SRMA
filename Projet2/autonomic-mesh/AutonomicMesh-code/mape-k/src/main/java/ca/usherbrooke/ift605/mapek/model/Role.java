package ca.usherbrooke.ift605.mapek.model;

/** Rôle d'une instance dans le maillage. */
public enum Role {
    /** Instance principale du service, cible du routage par défaut. */
    PRIMAIRE,
    /** Instance de secours démarrée à chaud, utilisée lors d'un réacheminement. */
    SECOURS,
    /** Instance supplémentaire créée par mise à l'échelle. */
    REPLIQUE
}
