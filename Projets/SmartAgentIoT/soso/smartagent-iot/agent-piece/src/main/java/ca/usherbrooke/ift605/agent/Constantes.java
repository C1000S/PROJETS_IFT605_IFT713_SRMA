package ca.usherbrooke.ift605.agent;

import java.util.List;

/**
 * Constantes partagées par les agents et le coordinateur.
 * <p>
 * Regrouper ces valeurs à un seul endroit évite les « chaînes magiques » dispersées
 * dans le code (nom du registre RMI, port, convention des topics MQTT...).
 */
public final class Constantes {

    /** Port du registre RMI imposé par l'énoncé. */
    public static final int PORT_RMI = 2199;

    /** Adresse utilisée pour exposer les objets RMI (tout tourne sur la même machine). */
    public static final String HOTE_RMI = "127.0.0.1";

    /** Préfixe du nom sous lequel chaque agent est enregistré : agent-salleA, agent-salleB... */
    public static final String PREFIXE_AGENT = "agent-";

    /** Les trois pièces simulées (énoncé : exactement salleA, salleB et cuisine). */
    public static final List<String> PIECES = List.of("salleA", "salleB", "cuisine");

    /** Un capteur est en « timeout » si aucun message n'est reçu depuis 30 s (énoncé). */
    public static final long TIMEOUT_MS = 30_000;

    private Constantes() {
        // classe utilitaire : pas d'instance
    }

    /** URI du broker MQTT ; modifiable avec -Dmqtt.broker=tcp://hote:1883. */
    public static String brokerMqtt() {
        return System.getProperty("mqtt.broker", "tcp://localhost:1883");
    }

    /** Nom RMI d'un agent, ex. {@code nomAgent("salleA")} donne {@code "agent-salleA"}. */
    public static String nomAgent(String piece) {
        return PREFIXE_AGENT + piece;
    }

    /** Début des topics d'une pièce : {@code batiment/salleA}. */
    public static String topicBase(String piece) {
        return "batiment/" + piece;
    }
}
