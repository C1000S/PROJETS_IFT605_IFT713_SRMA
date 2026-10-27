package ca.usherbrooke.ift605.agent;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Dernières mesures connues d'une pièce, partagées entre deux threads :
 * <ul>
 *   <li>le thread du callback MQTT, qui <b>écrit</b> (méthode {@link #appliquer}) ;</li>
 *   <li>les threads RMI, qui <b>lisent</b> (getters, {@link #toJson}).</li>
 * </ul>
 * <p>
 * <b>Thread-safety</b> : chaque champ est {@code volatile}. Une écriture faite par un thread
 * est donc immédiatement visible par les autres (sans cela, un thread pourrait lire indéfiniment
 * une valeur périmée gardée en cache CPU). Chaque champ est indépendant des autres : il n'y a pas
 * d'invariant entre eux, donc {@code volatile} suffit. Limite connue : {@link #toJson} peut
 * mélanger des valeurs de deux messages successifs (acceptable ici).
 * <p>
 * Cette classe est séparée d'{@link AgentPiece} pour pouvoir être testée en JUnit sans broker
 * MQTT ni RMI, et pour que l'horloge soit injectable (tester le timeout sans attendre 30 s).
 */
public final class EtatPiece {

    private final LongSupplier horloge;
    private final long timeoutMs;

    private volatile double temperature = Double.NaN;
    private volatile int luminosite;
    private volatile boolean occupee;
    private volatile long derniereMaj = -1; // -1 = aucun message reçu

    public EtatPiece() {
        this(System::currentTimeMillis, Constantes.TIMEOUT_MS);
    }

    /**
     * @param horloge   source de temps en millisecondes (injectable pour les tests)
     * @param timeoutMs délai sans message au-delà duquel la pièce est en timeout
     */
    public EtatPiece(LongSupplier horloge, long timeoutMs) {
        this.horloge = horloge;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Applique une mesure reçue sur {@code batiment/{piece}/capteur/{typeCapteur}}.
     *
     * @param typeCapteur dernier segment du topic : temperature, luminosite ou occupation
     * @param valeur      charge utile du message MQTT
     * @return {@code true} si la mesure est valide et a été prise en compte
     */
    public boolean appliquer(String typeCapteur, String valeur) {
        String v = valeur.trim();
        try {
            switch (typeCapteur) {
                case "temperature" -> temperature = Double.parseDouble(v);
                case "luminosite" -> luminosite = Integer.parseInt(v);
                case "occupation" -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                        return false;
                    }
                    occupee = Boolean.parseBoolean(v);
                }
                default -> {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false; // message corrompu : on garde l'ancienne valeur
        }
        derniereMaj = horloge.getAsLong();
        return true;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getLuminosite() {
        return luminosite;
    }

    public boolean isOccupee() {
        return occupee;
    }

    /** Vrai si aucun message n'est arrivé depuis {@code timeoutMs} (ou jamais). */
    public boolean enTimeout() {
        long derniere = derniereMaj;
        return derniere < 0 || horloge.getAsLong() - derniere > timeoutMs;
    }

    /** Résumé JSON de l'état (construit à la main : évite une dépendance à une bibliothèque JSON). */
    public String toJson(String piece) {
        double t = temperature;
        String temp = Double.isNaN(t) ? "null" : String.format(Locale.US, "%.1f", t);
        return String.format(Locale.US,
                "{\"piece\":\"%s\",\"temperature\":%s,\"luminosite\":%d,\"occupee\":%b,\"timeout\":%b}",
                piece, temp, luminosite, occupee, enTimeout());
    }
}
