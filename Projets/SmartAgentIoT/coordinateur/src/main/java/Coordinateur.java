import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/**
 * Coordinateur du bâtiment intelligent.
 * Il compare les températures des pièces voisines
 * et ajuste le thermostat de la pièce la plus chaude.
 */
public class Coordinateur {

    private static final String HOTE_RMI = "localhost";
    private static final int PORT_RMI = 2199;
    private static final double ECART_MAX = 4.0;
    private static final long INTERVALLE_MS = 5000;

    private Registry registre;

    public Coordinateur() throws Exception {
        registre = LocateRegistry.getRegistry(HOTE_RMI, PORT_RMI);

        // Vérifie que le registre RMI est accessible.
        registre.list();

        System.out.println("Coordinateur connecté au registre RMI.");
    }

    /**
     * Récupère un agent à partir de son nom dans le registre.
     */
    private IAgentPiece obtenirAgent(String nom)
            throws Exception {

        return (IAgentPiece) registre.lookup("agent-" + nom);
    }

    /**
     * Compare deux pièces voisines.
     * Si l'écart dépasse le seuil, ajuste la pièce la plus chaude.
     */
    private void comparerPieces(String piece1, String piece2) {

        try {
            IAgentPiece agent1 = obtenirAgent(piece1);
            IAgentPiece agent2 = obtenirAgent(piece2);

            double temperature1 = agent1.getTemperature();
            double temperature2 = agent2.getTemperature();

            double ecart = Math.abs(temperature1 - temperature2);

            System.out.printf(
                    "%nComparaison %s (%.1f °C) - %s (%.1f °C)%n",
                    piece1, temperature1, piece2, temperature2
            );

            if (ecart > ECART_MAX) {

                IAgentPiece agentChaud;
                String pieceChaude;
                double temperatureChaude;
                double temperatureCible;

                if (temperature1 > temperature2) {
                    agentChaud = agent1;
                    pieceChaude = piece1;
                    temperatureChaude = temperature1;
                    temperatureCible = (temperature1 + temperature2) / 2.0;
                } else {
                    agentChaud = agent2;
                    pieceChaude = piece2;
                    temperatureChaude = temperature2;
                    temperatureCible = (temperature1 + temperature2) / 2.0;
                }

                System.out.printf(
                        "Écart de %.1f °C supérieur à %.1f °C.%n",
                        ecart, ECART_MAX
                );

                System.out.printf(
                        "Décision : ajuster le thermostat de %s "
                                + "(température actuelle : %.1f °C).%n",
                        pieceChaude, temperatureChaude
                );

                agentChaud.setConsigneTemperature(temperatureCible);

                System.out.printf(
                        "Consigne cible envoyée : %.1f °C%n",
                        temperatureCible
                );

            } else {
                System.out.printf(
                        "Aucun ajustement nécessaire : écart de %.1f °C.%n",
                        ecart
                );
            }

        } catch (RemoteException e) {
            System.err.printf(
                    "Agent indisponible pour la comparaison %s - %s : %s%n",
                    piece1, piece2, e.getMessage()
            );

        } catch (Exception e) {
            System.err.printf(
                    "Erreur pour la comparaison %s - %s : %s%n",
                    piece1, piece2, e.getMessage()
            );
        }
    }

    /**
     * Vérifie continuellement les deux paires de pièces voisines.
     */
    public void demarrer() {

        System.out.println("Coordinateur démarré.");

        while (!Thread.currentThread().isInterrupted()) {

            comparerPieces("salleA", "salleB");
            comparerPieces("salleB", "cuisine");

            try {
                Thread.sleep(INTERVALLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) {

        try {
            Coordinateur coordinateur = new Coordinateur();
            coordinateur.demarrer();

        } catch (Exception e) {
            System.err.println(
                    "Impossible de démarrer le coordinateur : "
                            + e.getMessage()
            );
            System.err.println(
                    "Vérifie que le registre RMI est lancé sur le port 2199."
            );
        }
    }
}
