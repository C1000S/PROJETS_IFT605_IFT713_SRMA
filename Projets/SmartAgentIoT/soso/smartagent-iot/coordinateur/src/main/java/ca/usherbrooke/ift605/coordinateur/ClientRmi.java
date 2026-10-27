package ca.usherbrooke.ift605.coordinateur;

import ca.usherbrooke.ift605.agent.Constantes;
import ca.usherbrooke.ift605.agent.IAgentPiece;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/**
 * Petit client RMI de validation (semaine 2) : lit l'état d'une pièce et, optionnellement,
 * modifie sa consigne.
 * <pre>
 *   java -cp coordinateur-1.0.0-all.jar ca.usherbrooke.ift605.coordinateur.ClientRmi salleA
 *   java -cp coordinateur-1.0.0-all.jar ca.usherbrooke.ift605.coordinateur.ClientRmi salleA 19.5
 * </pre>
 */
public final class ClientRmi {

    private ClientRmi() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage : ClientRmi <piece> [nouvelleConsigne]");
            System.exit(1);
        }
        Registry registre = LocateRegistry.getRegistry(Constantes.HOTE_RMI, Constantes.PORT_RMI);
        IAgentPiece agent = (IAgentPiece) registre.lookup(Constantes.nomAgent(args[0]));

        System.out.println("Température : " + agent.getTemperature() + " °C");
        System.out.println("Luminosité  : " + agent.getLuminosite() + " lux");
        System.out.println("Occupée     : " + agent.isOccupee());
        System.out.println("En timeout  : " + agent.isEnTimeout());
        System.out.println("État JSON   : " + agent.getEtatJson());

        if (args.length > 1) {
            double consigne = Double.parseDouble(args[1]);
            agent.setConsigneTemperature(consigne);
            System.out.println("Consigne " + consigne + " °C envoyée (publiée sur MQTT par l'agent).");
        }
    }
}
