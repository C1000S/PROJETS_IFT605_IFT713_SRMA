import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

/**
 * Simule les capteurs d'une pièce et publie leurs mesures via MQTT.
 */
public class CapteurSimulateur implements Runnable {

    private static final String BROKER = "tcp://localhost:1883";
    private static final int INTERVALLE_MS = 5000;

    private final String piece;
    private final MqttClient client;
    private final Random hasard = new Random();

    public CapteurSimulateur(String piece) throws MqttException {
        this.piece = piece;

        String identifiant = "capteur-" + piece + "-" +
                UUID.randomUUID().toString().substring(0, 8);

        client = new MqttClient(BROKER, identifiant);
        client.connect();

        System.out.println("Capteurs connectés pour : " + piece);
    }

    private void publier(String type, String valeur)
            throws MqttException {

        String sujet = "batiment/" + piece + "/capteur/" + type;

        MqttMessage message = new MqttMessage(
                valeur.getBytes(StandardCharsets.UTF_8));

        message.setQos(1);
        client.publish(sujet, message);

        System.out.printf("[%s] %s = %s%n", piece, type, valeur);
    }

    private void publierTemperature() throws MqttException {
        // Température simulée entre 16 et 30 °C.
        double temperature = 16 + hasard.nextDouble() * 14;
        publier("temperature", String.format(
                java.util.Locale.US, "%.1f", temperature));
    }

    private void publierLuminosite() throws MqttException {
        // Luminosité simulée entre 0 et 1000 lux.
        int luminosite = hasard.nextInt(1001);
        publier("luminosite", Integer.toString(luminosite));
    }

    private void publierOccupation() throws MqttException {
        // Environ 60 % de chances que la pièce soit occupée.
        boolean occupee = hasard.nextDouble() < 0.6;
        publier("occupation", Boolean.toString(occupee));
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                publierTemperature();
                publierLuminosite();
                publierOccupation();

                Thread.sleep(INTERVALLE_MS);
            }
        } catch (MqttException e) {
            System.err.println(
                    "Erreur MQTT pour " + piece + " : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (MqttException e) {
                System.err.println(
                        "Erreur de fermeture pour " + piece);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String[] pieces = {"salleA", "salleB", "cuisine"};

        for (String piece : pieces) {
            CapteurSimulateur simulateur =
                    new CapteurSimulateur(piece);

            Thread thread = new Thread(simulateur, "capteur-" + piece);
            thread.start();
        }
    }
}
