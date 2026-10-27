package ca.usherbrooke.ift605.simulator;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Simule les capteurs d'<b>une pièce</b> et publie trois mesures toutes les 5 secondes :
 * <pre>
 *   batiment/{piece}/capteur/temperature   ex. 22.4
 *   batiment/{piece}/capteur/luminosite    ex. 340
 *   batiment/{piece}/capteur/occupation    ex. true
 * </pre>
 * <b>Plages réalistes (à citer dans le rapport)</b> : température 15 à 30 °C, luminosité 0 à 1000 lux,
 * occupation true/false.
 * <p>
 * Pour rendre la démonstration contrôlable, le simulateur écoute aussi :
 * <ul>
 *   <li>{@code batiment/{piece}/actuateur/thermostat} : la température dérive vers la consigne
 *       (simule un chauffage/climatiseur) ;</li>
 *   <li>{@code batiment/{piece}/simulateur/temperature} : force la température courante ;</li>
 *   <li>{@code batiment/{piece}/simulateur/occupation} : force l'occupation (true/false) ;</li>
 *   <li>{@code batiment/{piece}/simulateur/panne} : {@code true} arrête les publications
 *       (panne capteur), {@code false} les reprend.</li>
 * </ul>
 */
public final class CapteurSimulateur implements Runnable {

    static final double TEMP_MIN = 15.0;
    static final double TEMP_MAX = 30.0;
    static final int LUMINOSITE_MAX = 1000;
    static final long PERIODE_MS = 5000;

    private final String piece;
    private final MqttClient client;
    private final Random rnd = new Random();

    // volatile : écrits par le callback MQTT, lus par le thread run()
    private volatile double temperature;
    private volatile boolean occupee;
    private volatile Double consigne = null; // null tant qu'aucune consigne n'a été reçue
    private volatile boolean enPanne = false;

    public CapteurSimulateur(String piece, String brokerUri) throws MqttException {
        this.piece = piece;
        this.temperature = 18 + rnd.nextDouble() * 6; // départ entre 18 et 24 °C
        this.occupee = rnd.nextBoolean();

        client = new MqttClient(brokerUri, "sim-" + piece, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(5);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverUri) {
                if (reconnect) {
                    abonner(); // cleanSession=true : les abonnements sont perdus à la coupure
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log("connexion MQTT perdue : " + cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // inutilisé : le traitement est dans le listener de subscribe()
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
            String base = "batiment/" + piece;
            IMqttMessageListener ecouteur = (topic, msg) ->
                    onMessage(topic, new String(msg.getPayload(), StandardCharsets.UTF_8));
            client.subscribe(base + "/actuateur/thermostat", ecouteur);
            client.subscribe(base + "/simulateur/#", ecouteur);
        } catch (MqttException e) {
            log("abonnement impossible : " + e.getMessage());
        }
    }

    /** Traite les messages de contrôle (le dernier segment du topic identifie la commande). */
    void onMessage(String topic, String payload) {
        String commande = topic.substring(topic.lastIndexOf('/') + 1);
        String v = payload.trim();
        try {
            switch (commande) {
                case "thermostat" -> consigne = Double.parseDouble(v);
                case "temperature" -> temperature = borner(Double.parseDouble(v));
                case "occupation" -> occupee = Boolean.parseBoolean(v);
                case "panne" -> {
                    enPanne = Boolean.parseBoolean(v);
                    log(enPanne ? "PANNE simulée : publications arrêtées" : "reprise des publications");
                }
                default -> { /* topic inconnu : ignoré */ }
            }
        } catch (NumberFormatException e) {
            log("valeur invalide sur " + topic + " : « " + payload + " »");
        }
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!enPanne) {
                    evoluer();
                    int lux = rnd.nextInt(LUMINOSITE_MAX + 1);
                    publier("temperature", String.format(Locale.US, "%.1f", temperature));
                    publier("luminosite", String.valueOf(lux));
                    publier("occupation", String.valueOf(occupee));
                    log(String.format(Locale.US, "T=%.1f °C  L=%d lux  occupée=%b", temperature, lux, occupee));
                }
                Thread.sleep(PERIODE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // on sort proprement de la boucle
            } catch (MqttException e) {
                log("erreur de publication : " + e.getMessage()); // on réessaiera au prochain cycle
            }
        }
    }

    /** Marche aléatoire + dérive de 25 % vers la consigne à chaque cycle. */
    private void evoluer() {
        double t = temperature + rnd.nextGaussian() * 0.2;
        Double c = consigne; // copie locale : la valeur peut changer entre deux lectures
        if (c != null) {
            t += (c - t) * 0.25;
        }
        temperature = borner(t);
        if (rnd.nextDouble() < 0.05) {
            occupee = !occupee; // 5 % de chance de changer d'état à chaque cycle
        }
    }

    static double borner(double t) {
        return Math.max(TEMP_MIN, Math.min(TEMP_MAX, t));
    }

    private void publier(String type, String valeur) throws MqttException {
        client.publish("batiment/" + piece + "/capteur/" + type,
                valeur.getBytes(StandardCharsets.UTF_8), 0, false);
    }

    private void log(String message) {
        System.out.println("[sim-" + piece + "] " + message);
    }

    /** Sans argument : simule les trois pièces. Avec arguments : uniquement les pièces citées. */
    public static void main(String[] args) throws Exception {
        String broker = System.getProperty("mqtt.broker", "tcp://localhost:1883");
        List<String> pieces = args.length > 0 ? List.of(args) : List.of("salleA", "salleB", "cuisine");
        for (String p : pieces) {
            new Thread(new CapteurSimulateur(p, broker), "sim-" + p).start();
        }
    }
}
