package ca.usherbrooke.ift605.mapek.model;

/** Symptômes détectés par l'Analyzer. */
public enum TypeSymptome {
    /** Statut DOWN ou mesure périmée (instance injoignable). */
    PANNE_TOTALE,
    /** Latence supérieure au seuil (500 ms par défaut). */
    DEGRADATION,
    /** Charge CPU supérieure au seuil (0,90 par défaut). */
    SURCHARGE
}
