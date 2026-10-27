package ca.usherbrooke.ift605.agent;

import java.nio.charset.StandardCharsets;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Agent d'<b>une seule pièce</b> : serveur RMI + abonné MQTT.
 * <ul>
 *   <li>Côté MQTT : s'abonne uniquement à {@code batiment/{nom}/capteur/#} et mémorise la dernière
 *       valeur reçue de chaque capteur dans un {@link EtatPiece}.</li>
 *   <li>Côté RMI : expose ces valeurs (et la commande de thermostat) via {@link IAgentPiece}.</li>
 *   <li>Entre agents : {@link #consulterVoisin(String)} permet à un agent d'interroger un autre
 *       agent par RMI (partage d'état), sans passer par le coordinateur.</li>
 * </ul>
 * {@code extends UnicastRemoteObject} : le constructeur parent « exporte » l'objet, c'est-à-dire
 * ouvre un port TCP pour recevoir les appels distants.
 */
public final class AgentPiece extends UnicastRemoteObject implements IAgentPiece {

    private static final long serialVersionUID = 1L;

    private final String nom;
    private final transient MqttClient client;
    private final transient EtatPiece etat = new EtatPiece();

    /**
     * @param nom       nom de la pièce (salleA, salleB, cuisine)
     * @param brokerUri adresse du broker MQTT, ex. {@code tcp://localhost:1883}
     */
    public AgentPiece(String nom, String brokerUri) throws RemoteException, MqttException {
        super();
        this.nom = nom;
        this.client = new MqttClient(brokerUri, "agent-" + nom, new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true); // reprise automatique si le broker redémarre
        options.setCleanSession(true);
        options.setConnectionTimeout(5);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverUri) {
                // Avec cleanSession=true, le broker oublie nos abonnements à la coupure :
                // il faut donc se réabonner après chaque reconnexion automatique.
                if (reconnect) {
                    log("reconnecté au broker, réabonnement");
                    abonner();
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log("connexion MQTT perdue : " + cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // inutilisé : le traitement se fait dans le listener passé à subscribe()
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // inutilisé
            }
        });

        client.connect(options);
        abonner();
    }

    private void abonner() {
        try {
            client.subscribe(Constantes.topicBase(nom) + "/capteur/#",
                    (topic, msg) -> traiter(topic, new String(msg.getPayload(), StandardCharsets.UTF_8)));
        } catch (MqttException e) {
            log("abonnement impossible : " + e.getMessage());
        }
    }

    /** Appelée par le thread MQTT à chaque message : met à jour l'état partagé. */
    void traiter(String topic, String payload) {
        String type = topic.substring(topic.lastIndexOf('/') + 1);
        if (!etat.appliquer(type, payload)) {
            log("message ignoré sur " + topic + " : « " + payload + " »");
        }
    }

    @Override
    public double getTemperature() {
        return etat.getTemperature();
    }

    @Override
    public int getLuminosite() {
        return etat.getLuminosite();
    }

    @Override
    public boolean isOccupee() {
        return etat.isOccupee();
    }

    @Override
    public boolean isEnTimeout() {
        return etat.enTimeout();
    }

    @Override
    public String getEtatJson() {
        return etat.toJson(nom);
    }

    /**
     * Interroge l'agent d'une autre pièce par RMI (appel d'agent à agent).
     * <p>
     * Déroulement : 1) on retrouve le voisin dans le registre RMI (port 2199) sous le nom
     * {@code agent-<piece>} ; 2) on appelle sa méthode {@link IAgentPiece#getEtatJson()} ; 3) on
     * renvoie sa réponse à l'appelant. Si le voisin est arrêté ou absent du registre, l'appelant
     * reçoit une {@link RemoteException} : la panne d'un voisin n'arrête jamais cet agent.
     */
    @Override
    public String consulterVoisin(String piece) throws RemoteException {
        if (nom.equals(piece)) {
            return getEtatJson(); // se consulter soi-même : inutile de passer par le réseau
        }
        try {
            Registry registre = LocateRegistry.getRegistry(Constantes.HOTE_RMI, Constantes.PORT_RMI);
            IAgentPiece voisin = (IAgentPiece) registre.lookup(Constantes.nomAgent(piece));
            String etatVoisin = voisin.getEtatJson(); // appel RMI d'agent à agent
            log("consultation de " + piece + " : " + etatVoisin);
            return etatVoisin;
        } catch (NotBoundException e) {
            throw new RemoteException("Agent introuvable dans le registre : " + piece, e);
        } catch (RemoteException e) {
            throw new RemoteException("Voisin injoignable : " + piece, e);
        }
    }

    /**
     * Publie la consigne sur {@code batiment/{nom}/actuateur/thermostat} (QoS 1 : livraison au moins
     * une fois, adapté à une commande). Une erreur MQTT est convertie en {@link RemoteException}
     * pour que l'appelant distant soit prévenu.
     */
    @Override
    public void setConsigneTemperature(double t) throws RemoteException {
        if (t < 5 || t > 35) {
            throw new IllegalArgumentException("Consigne hors plage [5, 35] : " + t);
        }
        try {
            client.publish(Constantes.topicBase(nom) + "/actuateur/thermostat",
                    String.valueOf(t).getBytes(StandardCharsets.UTF_8), 1, false);
            log("consigne publiée : " + t);
        } catch (MqttException e) {
            throw new RemoteException("Publication MQTT impossible pour " + nom, e);
        }
    }

    private void log(String message) {
        System.out.println("[agent-" + nom + "] " + message);
    }
}