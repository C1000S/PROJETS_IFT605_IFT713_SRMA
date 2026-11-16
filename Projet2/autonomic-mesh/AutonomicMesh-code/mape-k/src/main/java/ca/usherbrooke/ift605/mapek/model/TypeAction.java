package ca.usherbrooke.ift605.mapek.model;

/** Actions correctives que le Planner peut produire et que l'Executor réalise. */
public enum TypeAction {
    /** Relancer le processus gRPC de l'instance (ProcessBuilder). */
    REDEMARRER,
    /** Faire pointer le routage du service vers son instance de secours. */
    REACHEMINEMENT,
    /** Démarrer une réplique supplémentaire du service. */
    MISE_A_ECHELLE,
    /** Remettre le routage sur l'instance primaire une fois rétablie. */
    RETABLIR_ROUTAGE
}
