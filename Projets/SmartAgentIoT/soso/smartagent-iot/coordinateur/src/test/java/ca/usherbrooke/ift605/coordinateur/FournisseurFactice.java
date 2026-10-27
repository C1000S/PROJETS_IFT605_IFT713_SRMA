package ca.usherbrooke.ift605.coordinateur;

import ca.usherbrooke.ift605.agent.IAgentPiece;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Faux registre : associe un nom de pièce à un agent factice (ou à rien = introuvable). */
class FournisseurFactice implements FournisseurAgents {

    final Map<String, IAgentPiece> agents = new HashMap<>();
    final Set<String> oublies = new HashSet<>();

    @Override
    public IAgentPiece obtenir(String piece) {
        return agents.get(piece);
    }

    @Override
    public void oublier(String piece) {
        oublies.add(piece);
    }
}
