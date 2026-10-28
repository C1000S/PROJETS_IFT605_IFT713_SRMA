package ca.udes.ift605.smartagent.sensor;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

/**
 * Simule les trois capteurs d'une pièce (température, luminosité, occupation)
 * et publie leurs mesures sur le broker MQTT toutes les 5 secondes.
 *
 * Convention de topics (imposée par l'énoncé) :
 *   batiment/{piece}/capteur/temperature
 *   batiment/{piece}/capteur/luminosite
 *   batiment/{piece}/capteur/occupation
 *
 * Plages de valeurs réalistes documentées (à reprendre dans le rapport, section
 * "choix de conception") :
 *   - température : 17.0 à 26.0 °C, évolution par petits pas (+-0.5°C) pour
 *     rester crédible d'une mesure à l'autre plutôt que de sauter au hasard.
 *   - luminosité   : 0 à 800 lux (pièce sombre -> pièce très éclairée).
 *   - occupation   : booléen tiré avec 40% de chance d'être "occupée".
 *
 * BOUCLE FERMÉE (pour une démonstration pilotable, intégrée depuis la variante
 * de l'équipe). En plus de PUBLIER ses mesures, le simulateur S'ABONNE à :
 *   - batiment/{piece}/actuateur/thermostat : la consigne envoyée par l'agent
 *     (via le coordinateur). La température simulée DÉRIVE alors vers cette
 *     consigne (~25% de l'écart par cycle), ce qui rend visible l'effet de la
 *     décision du coordinateur — sans ça, la température resterait une simple
 *     marche aléatoire indépendante des commandes.
 *   - batiment/{piece}/simulateur/temperature <v> : force la température courante
 *     (utile pour provoquer un écart > seuil pendant la démo).
 *   - batiment/{piece}/simulateur/occupation <true|false> : force l'occupation
 *     (déclenche les règles openHAB confort/économie d'énergie).
 *   - batiment/{piece}/simulateur/panne <true|false> : true arrête les
 *     publications (panne capteur -> timeout côté agent), false les reprend.
 *
 * Concurrence : les champs pilotés par les messages de contrôle (consigne,
 * occupationForcee, enPanne, derniereTemperature) sont écrits par le thread de
 * callback MQTT et lus par le thread run() — d'où 'volatile'.
 */
public class CapteurSimulateur implements Runnable {

    private static final String BROKER_URL = "tcp://localhost:1883";
    private static final long PERIODE_MS = 5000;

    private static final double TEMP_MIN = 17.0;
    private static final double TEMP_MAX = 26.0;
    // Bornes physiques (plus larges que la plage naturelle) : elles n'entrent en
    // jeu que lorsqu'une consigne ou un forçage pousse volontairement la
    // température hors de la plage de confort.
    private static final double TEMP_PHYS_MIN = 10.0;
    private static final double TEMP_PHYS_MAX = 35.0;
    private static final int LUX_MIN = 0;
    private static final int LUX_MAX = 800;
    private static final double PROBABILITE_OCCUPATION = 0.4;
    // Part de l'écart vers la consigne rattrapée à chaque cycle (inertie thermique).
    private static final double FACTEUR_DERIVE = 0.25;

    private final String piece;
    private final MqttClient client;
    private final Random rnd = new Random();

    // On garde la dernière température publiée pour faire une "marche aléatoire"
    // bornée : un vrai capteur ne saute jamais de 18°C à 25°C en 5 secondes.
    private volatile double derniereTemperature;

    // Consigne reçue de l'actuateur thermostat (null tant qu'aucune n'est reçue).
    private volatile Double consigne = null;
    // Occupation forcée via simulateur/occupation (null = comportement aléatoire normal).
    private volatile Boolean occupationForcee = null;
    // Panne simulée : quand true, le simulateur cesse de publier.
    private volatile boolean enPanne = false;

    public CapteurSimulateur(String piece) throws MqttException {
        this.piece = piece;

        // clientId doit être unique par connexion MQTT : on suffixe avec le nom
        // de la pièce + un timestamp pour éviter toute collision si on relance
        // rapidement le processus (Mosquitto refuse deux clients avec le même id).
        String clientId = "capteur-" + piece + "-" + System.currentTimeMillis();
        this.client = new MqttClient(BROKER_URL, clientId, new MemoryPersistence());
        this.derniereTemperature = TEMP_MIN + rnd.nextDouble() * (TEMP_MAX - TEMP_MIN);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true); // survit à un redémarrage du broker

        // Avec cleanSession=true, le broker oublie nos abonnements à la coupure :
        // on se réabonne donc après chaque reconnexion automatique.
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverUri) {
                if (reconnect) {
                    System.out.println("[" + piece + "] reconnecté, réabonnement aux topics de contrôle");
                    abonnerControle();
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                System.err.println("[" + piece + "] connexion MQTT perdue : " + cause);
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
        abonnerControle();
        System.out.println("[" + piece + "] connecté au broker " + BROKER_URL);
    }

    /** S'abonne aux topics de contrôle (actuateur + pilotage de démo). */
    private void abonnerControle() {
        try {
            String base = "batiment/" + piece;
            client.subscribe(base + "/actuateur/thermostat",
                    (t, m) -> onControle(t, new String(m.getPayload(), StandardCharsets.UTF_8)));
            client.subscribe(base + "/simulateur/#",
                    (t, m) -> onControle(t, new String(m.getPayload(), StandardCharsets.UTF_8)));
        } catch (MqttException e) {
            System.err.println("[" + piece + "] abonnement aux topics de contrôle impossible : " + e.getMessage());
        }
    }

    /** Traite un message de contrôle ; le dernier segment du topic identifie la commande. */
    void onControle(String topic, String payload) {
        String commande = topic.substring(topic.lastIndexOf('/') + 1);
        String v = payload.trim();
        try {
            switch (commande) {
                case "thermostat" -> consigne = Double.parseDouble(v);
                case "temperature" -> derniereTemperature = clamp(Double.parseDouble(v), TEMP_PHYS_MIN, TEMP_PHYS_MAX);
                case "occupation" -> occupationForcee = Boolean.parseBoolean(v);
                case "panne" -> {
                    enPanne = Boolean.parseBoolean(v);
                    System.out.println("[" + piece + "] " + (enPanne
                            ? "PANNE simulée : publications arrêtées"
                            : "reprise des publications"));
                }
                default -> { /* topic de contrôle inconnu : ignoré */ }
            }
        } catch (NumberFormatException e) {
            System.err.println("[" + piece + "] valeur de contrôle invalide sur " + topic + " : " + payload);
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                // Le catch(MqttException) est ICI, À L'INTÉRIEUR de la boucle —
                // pas autour d'elle. Bug réel rencontré en pratique : une simple
                // coupure réseau transitoire (ex. mise en veille de la machine)
                // fait échouer un seul publish() ; si l'exception s'échappe de la
                // boucle, le thread se termine et le "capteur" reste mort pour
                // toujours, même après le rétablissement du réseau. En capturant
                // ici, on perd seulement UN cycle de mesure et on retente au
                // suivant — options.setAutomaticReconnect(true) a le temps de
                // reconnecter le client MQTT en arrière-plan entre-temps.
                try {
                    if (!enPanne) {
                        publishTemp();
                        publishLuminosite();
                        publishOccupation();
                    }
                } catch (MqttException e) {
                    System.err.println("[" + piece + "] échec de publication (" + e.getMessage() +
                            "), nouvelle tentative dans " + (PERIODE_MS / 1000) + "s");
                }
                Thread.sleep(PERIODE_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[" + piece + "] simulateur arrêté");
        }
    }

    private void publishTemp() throws MqttException {
        Double c = consigne; // copie locale : la valeur peut changer entre deux lectures
        if (c != null) {
            // Boucle fermée : on se rapproche de la consigne (inertie thermique),
            // plus un léger bruit de mesure. Clamp sur les bornes physiques.
            double bruit = (rnd.nextDouble() - 0.5) * 0.2;
            derniereTemperature = clamp(
                    derniereTemperature + (c - derniereTemperature) * FACTEUR_DERIVE + bruit,
                    TEMP_PHYS_MIN, TEMP_PHYS_MAX);
        } else {
            // Comportement par défaut documenté : marche aléatoire dans la plage de confort.
            double variation = (rnd.nextDouble() - 0.5); // entre -0.5 et +0.5 °C
            derniereTemperature = clamp(derniereTemperature + variation, TEMP_MIN, TEMP_MAX);
        }
        publier("temperature", String.format(Locale.US, "%.1f", derniereTemperature));
    }

    private void publishLuminosite() throws MqttException {
        int lux = LUX_MIN + rnd.nextInt(LUX_MAX - LUX_MIN + 1);
        publier("luminosite", String.valueOf(lux));
    }

    private void publishOccupation() throws MqttException {
        Boolean forcee = occupationForcee;
        boolean occupee = (forcee != null) ? forcee : (rnd.nextDouble() < PROBABILITE_OCCUPATION);
        publier("occupation", String.valueOf(occupee));
    }

    private void publier(String typeCapteur, String valeur) throws MqttException {
        String topic = "batiment/" + piece + "/capteur/" + typeCapteur;
        MqttMessage message = new MqttMessage(valeur.getBytes(StandardCharsets.UTF_8));
        message.setQos(0);       // "au plus une fois" : suffisant pour des mesures qui se
                                  // répètent toutes les 5s, perdre un message occasionnel n'est pas grave.
        message.setRetained(true); // le broker garde la dernière valeur : un nouvel abonné
                                    // (ex: openHAB qui démarre après le simulateur) reçoit
                                    // immédiatement le dernier état connu au lieu d'attendre 5s.
        client.publish(topic, message);
        System.out.printf("[%s] -> %s = %s%n", piece, topic, valeur);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
