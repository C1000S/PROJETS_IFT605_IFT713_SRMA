package ca.usherbrooke.ift605.coordinateur;

import ca.usherbrooke.ift605.agent.IAgentPiece;

/**
 * Donne accès aux agents de pièces. Interface introduite pour <b>découpler</b> la logique
 * d'équilibrage du réseau RMI : en production on utilise {@link FournisseurAgentsRmi},
 * en test JUnit on injecte de faux agents en mémoire.
 */
public interface FournisseurAgents {

    /** @return l'agent de la pièce, ou {@code null} s'il est introuvable dans le registre */
    IAgentPiece obtenir(String piece);

    /** Oublie le stub mis en cache (après une {@code RemoteException}) pour forcer un nouveau lookup. */
    void oublier(String piece);
}
