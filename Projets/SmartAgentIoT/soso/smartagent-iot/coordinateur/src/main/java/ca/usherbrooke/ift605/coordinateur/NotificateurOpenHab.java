package ca.usherbrooke.ift605.coordinateur;

/** Envoie des informations du coordinateur vers openHAB (implémentation réelle : REST). */
@FunctionalInterface
public interface NotificateurOpenHab {

    /**
     * Met à jour l'état d'un Item openHAB.
     * Ne doit <b>jamais</b> lever d'exception : une panne d'openHAB ne doit pas arrêter le coordinateur.
     */
    void publierEtat(String item, String valeur);
}
