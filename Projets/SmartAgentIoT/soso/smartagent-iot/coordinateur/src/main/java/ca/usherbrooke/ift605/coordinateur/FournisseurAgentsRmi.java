package ca.usherbrooke.ift605.coordinateur;

import ca.usherbrooke.ift605.agent.Constantes;
import ca.usherbrooke.ift605.agent.IAgentPiece;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation réelle : retrouve les agents dans le registre RMI (port 2199).
 * <p>
 * Le lookup est <b>paresseux</b> (fait au premier besoin, pas au démarrage) : le coordinateur peut
 * donc démarrer avant les agents, et retrouver un agent relancé après une panne.
 */
public class FournisseurAgentsRmi implements FournisseurAgents {

    private final String hote;
    private final int port;
    private final Map<String, IAgentPiece> cache = new ConcurrentHashMap<>();

    public FournisseurAgentsRmi(String hote, int port) {
        this.hote = hote;
        this.port = port;
    }

    @Override
    public IAgentPiece obtenir(String piece) {
        IAgentPiece agent = cache.get(piece);
        if (agent != null) {
            return agent;
        }
        try {
            Registry registre = LocateRegistry.getRegistry(hote, port);
            agent = (IAgentPiece) registre.lookup(Constantes.nomAgent(piece));
            cache.put(piece, agent);
            return agent;
        } catch (RemoteException | NotBoundException e) {
            return null; // registre injoignable ou agent non enregistré
        }
    }

    @Override
    public void oublier(String piece) {
        cache.remove(piece);
    }
}
