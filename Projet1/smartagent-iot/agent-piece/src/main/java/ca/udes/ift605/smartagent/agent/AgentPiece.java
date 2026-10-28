package ca.udes.ift605.smartagent.agent;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Un AgentPiece représente UNE SEULE pièce : il s'abonne uniquement aux
 * topics de cette pièce et garde la dernière valeur connue de chaque capteur.
 *
 * Double casquette :
 *  - Côté MQTT : simple abonné (subscriber), reçoit les mesures publiées par
 *    CapteurSimulateur et met à jour son état interne.
 *  - Côté RMI  : serveur distant (UnicastRemoteObject), expose IAgentPiece à
 *    d'autres JVM (le CoordinateurBatiment, ou un client RMI de test).
 *
 * Point de concurrence important : les champs temperature/luminosite/
 * occupation sont ÉCRITS par le thread de callback MQTT (interne à Paho) et
 * LUS par les threads RMI qui exécutent les appels distants (chaque appel
 * RMI entrant tourne sur son propre thread côté serveur). Deux threads
 * différents => risque de "visibilité mémoire" : sans 'volatile', un thread
 * RMI pourrait lire une valeur mise en cache dans son propre cœur de CPU au
 * lieu de la dernière valeur écrite par le thread MQTT. 'volatile' force
 * chaque lecture/écriture à passer par la mémoire principale.
 */
public class AgentPiece extends UnicastRemoteObject implements IAgentPiece {

    private static final String BROKER_URL = "tcp://localhost:1883";

    // Tâche 3.1 : un capteur est considéré en timeout après 30s sans message.
    private static final long TIMEOUT_MS = 30_000;
    // Fréquence de vérification du watchdog : suffisamment fine pour détecter
    // le timeout sans retard perceptible, sans pour autant boucler à vide.
    private static final long PERIODE_SURVEILLANCE_MS = 5_000;

    // S2 — consultation pair-à-pair : port du registre RMI partagé, cadence de
    // consultation des voisins et petit délai initial laissant le temps aux
    // autres agents de s'enregistrer avant la première interrogation.
    private static final int PORT_REGISTRE = 2199;
    private static final long PERIODE_CONSULTATION_MS = 10_000;
    private static final long DELAI_AVANT_CONSULTATION_MS = 3_000;

    private final String nom;
    private final MqttClient mqttClient;
    /** Noms des pièces voisines que cet agent consulte directement via RMI (S2). */
    private final List<String> voisins;
    /**
     * Dernière température connue de chaque voisin, obtenue par appel RMI direct
     * (partage d'état agent↔agent). ConcurrentHashMap car écrite par le thread
     * de consultation et lue par les threads RMI (getEtatJson).
     */
    private final Map<String, Double> dernieresTempVoisins = new ConcurrentHashMap<>();

    private volatile double temperature = Double.NaN;
    private volatile int luminosite = -1;
    private volatile boolean occupation = false;
    // Horodatage de la dernière mesure reçue, quel que soit le capteur.
    private volatile long dernierMessageMs = 0L;
    // Mémorise si on a DÉJÀ signalé le timeout en cours, pour ne logguer
    // qu'UNE FOIS la transition "panne détectée" et UNE FOIS la "reprise",
    // plutôt que de spammer un message toutes les 5 secondes.
    private volatile boolean timeoutDejaSignale = false;

    /**
     * Constructeur sans voisin : l'agent fonctionne seul (démo mono-pièce,
     * validation manuelle). Il n'effectue alors aucune consultation RMI de pair.
     */
    public AgentPiece(String nom) throws RemoteException {
        this(nom, List.of());
    }

    /**
     * @param nom     nom de la pièce gérée (salleA, salleB ou cuisine)
     * @param voisins pièces adjacentes que cet agent consultera directement via
     *                RMI (S2 — « les agents se consultent mutuellement »). La
     *                liste peut être vide ; elle est copiée (immuable) pour que
     *                le thread de consultation itère sans risque.
     */
    public AgentPiece(String nom, List<String> voisins) throws RemoteException {
        super(); // UnicastRemoteObject : exporte cet objet sur un port RMI (anonyme, différent du port du registre)
        this.nom = nom;
        this.voisins = List.copyOf(voisins);
        try {
            String clientId = "agent-" + nom + "-" + System.currentTimeMillis();
            this.mqttClient = new MqttClient(BROKER_URL, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            mqttClient.connect(options);

            // Un seul abonnement avec le wildcard '#' capture les 3 sous-topics
            // (temperature, luminosite, occupation) de CETTE pièce uniquement —
            // l'agent ignore complètement les autres pièces au niveau MQTT.
            String filtre = "batiment/" + nom + "/capteur/#";
            mqttClient.subscribe(filtre, this::onMessageRecu);
            System.out.println("[agent-" + nom + "] abonné à " + filtre);
        } catch (MqttException e) {
            throw new RemoteException("Échec de connexion MQTT pour l'agent " + nom, e);
        }

        // Watchdog : thread daemon dédié qui vérifie périodiquement la
        // fraîcheur des données. Il tourne indépendamment des appels RMI et
        // des callbacks MQTT — la panne d'un capteur n'affecte donc jamais
        // la disponibilité de l'agent lui-même (getTemperature() continue de
        // répondre instantanément avec la dernière valeur connue).
        Thread watchdog = new Thread(this::surveillerTimeout, "watchdog-" + nom);
        watchdog.setDaemon(true);
        watchdog.start();

        // S2 — Consultation pair-à-pair : en plus du coordinateur central, chaque
        // agent interroge DIRECTEMENT ses voisins via RMI (sans intermédiaire) pour
        // partager l'état thermique. C'est un vrai échange agent↔agent, distinct de
        // la supervision centralisée du CoordinateurBatiment. Thread daemon dédié
        // afin de ne jamais bloquer les callbacks MQTT ni les appels RMI entrants.
        if (!voisins.isEmpty()) {
            Thread consultation = new Thread(this::consulterVoisinsEnBoucle, "consultation-" + nom);
            consultation.setDaemon(true);
            consultation.start();
        }
    }

    /**
     * Boucle de consultation des voisins via RMI (S2). À chaque cycle, l'agent
     * récupère un stub vers chaque voisin dans le registre et lit sa température
     * en direct. Entièrement best-effort : un voisin pas encore démarré
     * (NotBoundException) ou tombé en panne (RemoteException) est simplement
     * ignoré ce cycle-ci, sans jamais interrompre la consultation des autres —
     * même logique de tolérance aux pannes que le coordinateur (Tâche 3.1).
     */
    private void consulterVoisinsEnBoucle() {
        try {
            Thread.sleep(DELAI_AVANT_CONSULTATION_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        while (true) {
            for (String voisin : voisins) {
                consulterUnVoisin(voisin);
            }
            try {
                Thread.sleep(PERIODE_CONSULTATION_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void consulterUnVoisin(String voisin) {
        try {
            Registry registre = LocateRegistry.getRegistry("localhost", PORT_REGISTRE);
            IAgentPiece stubVoisin = (IAgentPiece) registre.lookup("agent-" + voisin);
            double tempVoisin = stubVoisin.getTemperature();
            dernieresTempVoisins.put(voisin, tempVoisin);

            double maTemp = this.temperature;
            // On ne log la comparaison que si les DEUX côtés ont déjà une mesure.
            if (!Double.isNaN(maTemp) && !Double.isNaN(tempVoisin)) {
                System.out.printf("[agent-%s] consultation RMI de %s : %.1f°C (ma temp %.1f°C, écart %.1f°C)%n",
                        nom, voisin, tempVoisin, maTemp, Math.abs(maTemp - tempVoisin));
            }
        } catch (NotBoundException e) {
            // Voisin pas encore enregistré dans le registre : situation normale au démarrage.
            dernieresTempVoisins.remove(voisin);
        } catch (RemoteException e) {
            // Voisin injoignable (sa JVM est tombée) : on oublie sa dernière valeur et on continue.
            dernieresTempVoisins.remove(voisin);
            System.err.println("[agent-" + nom + "] voisin " + voisin + " injoignable via RMI : " + e.getMessage());
        }
    }

    private void surveillerTimeout() {
        while (true) {
            try {
                Thread.sleep(PERIODE_SURVEILLANCE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean enTimeoutMaintenant = calculerEnTimeout();
            if (enTimeoutMaintenant && !timeoutDejaSignale) {
                timeoutDejaSignale = true;
                System.err.printf("[agent-%s] TIMEOUT détecté : aucun message MQTT depuis plus de %ds. " +
                        "Dernière valeur connue conservée (temperature=%.1f).%n", nom, TIMEOUT_MS / 1000, temperature);
            } else if (!enTimeoutMaintenant && timeoutDejaSignale) {
                timeoutDejaSignale = false;
                System.out.println("[agent-" + nom + "] Reprise : messages MQTT reçus normalement à nouveau.");
            }
        }
    }

    private boolean calculerEnTimeout() {
        return dernierMessageMs != 0 && (System.currentTimeMillis() - dernierMessageMs) > TIMEOUT_MS;
    }

    /** Callback Paho : appelé sur le thread interne du client MQTT à chaque message reçu. */
    private void onMessageRecu(String topic, MqttMessage message) {
        String valeur = new String(message.getPayload());
        String typeCapteur = topic.substring(topic.lastIndexOf('/') + 1);
        try {
            switch (typeCapteur) {
                case "temperature" -> temperature = Double.parseDouble(valeur);
                case "luminosite" -> luminosite = Integer.parseInt(valeur);
                case "occupation" -> occupation = Boolean.parseBoolean(valeur);
                default -> System.err.println("[agent-" + nom + "] topic inattendu: " + topic);
            }
            dernierMessageMs = System.currentTimeMillis();
        } catch (NumberFormatException e) {
            // On log et on ignore plutôt que de laisser l'exception remonter dans le
            // thread de callback MQTT (qui tuerait silencieusement les futurs messages).
            System.err.println("[agent-" + nom + "] valeur invalide reçue sur " + topic + ": " + valeur);
        }
    }

    @Override
    public double getTemperature() throws RemoteException {
        return temperature;
    }

    @Override
    public int getLuminosite() throws RemoteException {
        return luminosite;
    }

    @Override
    public boolean isOccupee() throws RemoteException {
        return occupation;
    }

    @Override
    public void setConsigneTemperature(double t) throws RemoteException {
        String topic = "batiment/" + nom + "/actuateur/thermostat";
        try {
            MqttMessage message = new MqttMessage(String.valueOf(t).getBytes());
            message.setQos(1);        // "au moins une fois" : une commande d'actuateur ne doit pas se perdre silencieusement
            message.setRetained(true);
            mqttClient.publish(topic, message);
            System.out.printf("[agent-%s] nouvelle consigne thermostat = %.1f (topic %s)%n", nom, t, topic);
        } catch (MqttException e) {
            throw new RemoteException("Échec de publication de la consigne pour " + nom, e);
        }
    }

    @Override
    public boolean estEnTimeout() throws RemoteException {
        return calculerEnTimeout();
    }

    @Override
    public String getEtatJson() throws RemoteException {
        JSONObject json = new JSONObject();
        json.put("piece", nom);
        json.put("temperature", temperature);
        json.put("luminosite", luminosite);
        json.put("occupee", occupation);
        json.put("enTimeout", calculerEnTimeout());
        json.put("dernierMessage", dernierMessageMs == 0
                ? "aucun"
                : Instant.ofEpochMilli(dernierMessageMs).toString());
        // S2 — état partagé : dernières températures des voisins obtenues par RMI.
        json.put("voisins", new JSONObject(dernieresTempVoisins));
        return json.toString();
    }
}
