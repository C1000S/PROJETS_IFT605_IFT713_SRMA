import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/**
 * Agent représentant une pièce du bâtiment intelligent.
 * Il reçoit les mesures MQTT et expose son état via Java RMI.
 */
public class AgentPiece extends UnicastRemoteObject implements IAgentPiece {

    private static final String BROKER = "tcp://localhost:1883";
    private static final int PORT_RMI = 2199;
    private static final long DELAI_MAX_MS = 30_000;

    private final String nomPiece;
    private final MqttClient clientMqtt;

    private volatile double temperature = Double.NaN;
    private volatile int luminosite = -1;
    private volatile boolean occupee = false;

    private final AtomicLong derniereReception = new AtomicLong(0);

    public AgentPiece(String nomPiece) throws RemoteException, MqttException {
        super();
        this.nomPiece = nomPiece;

        String identifiant = "agent-" + nomPiece;
        clientMqtt = new MqttClient(
                BROKER,
                identifiant + "-" + System.currentTimeMillis()
        );

        clientMqtt.connect();

        // L'agent écoute uniquement les capteurs de sa propre pièce.
        String sujet = "batiment/" + nomPiece + "/capteur/#";

        clientMqtt.subscribe(sujet, (topic, message) -> {
            traiterMessage(topic, message);
        });

        System.out.println("Agent " + nomPiece + " connecté à MQTT.");
        System.out.println("Abonnement : " + sujet);
    }

    private void traiterMessage(String sujet, MqttMessage message) {
        try {
            String valeur = new String(
                    message.getPayload(),
                    StandardCharsets.UTF_8
            ).trim();

            if (sujet.endsWith("/temperature")) {
                temperature = Double.parseDouble(valeur);
            } else if (sujet.endsWith("/luminosite")) {
                luminosite = Integer.parseInt(valeur);
            } else if (sujet.endsWith("/occupation")) {
                occupee = Boolean.parseBoolean(valeur);
            } else {
                return;
            }

            derniereReception.set(System.currentTimeMillis());

        } catch (NumberFormatException e) {
            System.err.println(
                    "Valeur MQTT invalide pour " + nomPiece + " : " + sujet
            );
        }
    }

    private boolean donneesRecentes() {
        long derniere = derniereReception.get();

        return derniere != 0
                && System.currentTimeMillis() - derniere <= DELAI_MAX_MS;
    }

    @Override
    public double getTemperature() throws RemoteException {
        verifierDisponibilite();
        return temperature;
    }

    @Override
    public int getLuminosite() throws RemoteException {
        verifierDisponibilite();
        return luminosite;
    }

    @Override
    public boolean isOccupee() throws RemoteException {
        verifierDisponibilite();
        return occupee;
    }

    private void verifierDisponibilite() throws RemoteException {
        if (!donneesRecentes()) {
            throw new RemoteException(
                    "Données absentes ou trop anciennes pour " + nomPiece
            );
        }
    }

    @Override
    public void setConsigneTemperature(double nouvelleConsigne)
            throws RemoteException {

        if (!Double.isFinite(nouvelleConsigne)
                || nouvelleConsigne < 5
                || nouvelleConsigne > 35) {
            throw new RemoteException("Consigne de température invalide.");
        }

        try {
            String sujet = "batiment/" + nomPiece + "/actuateur/thermostat";
            String valeur = Double.toString(nouvelleConsigne);

            MqttMessage message = new MqttMessage(
                    valeur.getBytes(StandardCharsets.UTF_8)
            );
            message.setQos(1);

            clientMqtt.publish(sujet, message);

            System.out.printf(
                    "[%s] Consigne thermostat publiée : %.1f °C%n",
                    nomPiece,
                    nouvelleConsigne
            );

        } catch (MqttException e) {
            throw new RemoteException(
                    "Impossible de publier la consigne MQTT.", e
            );
        }
    }

    @Override
    public String getEtatJson() throws RemoteException {
        verifierDisponibilite();

        return String.format(
                java.util.Locale.US,
                "{\"piece\":\"%s\",\"temperature\":%.1f,"
                        + "\"luminosite\":%d,\"occupee\":%b}",
                nomPiece,
                temperature,
                luminosite,
                occupee
        );
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println(
                    "Utilisation : AgentPiece <salleA|salleB|cuisine>"
            );
            System.exit(1);
        }

        String nom = args[0];

        if (!nom.equals("salleA")
                && !nom.equals("salleB")
                && !nom.equals("cuisine")) {
            throw new IllegalArgumentException("Nom de pièce inconnu : " + nom);
        }

        Registry registre;

        try {
            registre = LocateRegistry.createRegistry(PORT_RMI);
            System.out.println("Registre RMI créé sur le port " + PORT_RMI);
        } catch (java.rmi.server.ExportException e) {
            registre = LocateRegistry.getRegistry("localhost", PORT_RMI);
            registre.list();
            System.out.println("Registre RMI existant réutilisé.");
        }

        AgentPiece agent = new AgentPiece(nom);
        registre.rebind("agent-" + nom, agent);

        System.out.println(
                "Agent enregistré sous le nom agent-" + nom
        );
    }
}
