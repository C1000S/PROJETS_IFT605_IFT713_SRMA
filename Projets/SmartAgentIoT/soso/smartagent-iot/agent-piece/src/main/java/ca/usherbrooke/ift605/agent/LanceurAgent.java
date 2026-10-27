package ca.usherbrooke.ift605.agent;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;

/**
 * Point d'entrée d'un agent : {@code java -jar agent-piece-1.0.0-all.jar salleA}.
 * <p>
 * Un agent = une JVM. Ainsi, arrêter un agent (panne) n'affecte pas les autres.
 */
public final class LanceurAgent {

    /** Référence forte : empêche le ramasse-miettes de collecter l'objet distant. */
    private static AgentPiece agent;

    private LanceurAgent() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage : java -jar agent-piece-1.0.0-all.jar <piece> [uriBrokerMqtt]");
            System.exit(1);
        }
        String piece = args[0];
        String broker = args.length > 1 ? args[1] : Constantes.brokerMqtt();

        // Sans cela, le stub RMI peut contenir une adresse IP inatteignable (ex. IP Docker/VPN).
        System.setProperty("java.rmi.server.hostname", Constantes.HOTE_RMI);

        Registry registre = obtenirRegistre();
        agent = new AgentPiece(piece, broker);
        registre.rebind(Constantes.nomAgent(piece), agent); // rebind : écrase une ancienne inscription
        System.out.println("[agent-" + piece + "] enregistré dans le registre RMI sous le nom "
                + Constantes.nomAgent(piece) + " (port " + Constantes.PORT_RMI + ")");
    }

    /** Réutilise le registre existant (cas normal) ou le crée s'il n'existe pas encore. */
    private static Registry obtenirRegistre() throws RemoteException {
        try {
            return LocateRegistry.createRegistry(Constantes.PORT_RMI);
        } catch (ExportException dejaCree) {
            return LocateRegistry.getRegistry(Constantes.HOTE_RMI, Constantes.PORT_RMI);
        }
    }
}
