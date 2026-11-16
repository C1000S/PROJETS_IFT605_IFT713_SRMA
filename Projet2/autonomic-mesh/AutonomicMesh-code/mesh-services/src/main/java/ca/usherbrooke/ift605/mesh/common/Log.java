package ca.usherbrooke.ift605.mesh.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Journalisation console minimale et thread-safe, au format
 * {@code HH:mm:ss.SSS [COMPOSANT] message}.
 * <p>
 * Un format uniforme permet de suivre la séquence Monitor → Analyze → Plan → Execute
 * dans les logs lors de la démonstration.
 */
public final class Log {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    /**
     * Écrit un message d'information.
     *
     * @param composant étiquette du composant (ex. MONITOR, ANALYZE)
     * @param message   texte à afficher
     */
    public static synchronized void info(String composant, String message) {
        System.out.printf("%s [%s] %s%n", LocalTime.now().format(FORMAT), composant, message);
    }

    /**
     * Écrit un avertissement sur la sortie d'erreur.
     *
     * @param composant étiquette du composant
     * @param message   texte à afficher
     */
    public static synchronized void warn(String composant, String message) {
        System.err.printf("%s [%s] ATTENTION : %s%n", LocalTime.now().format(FORMAT), composant, message);
    }

    /**
     * Écrit une erreur avec sa cause sur la sortie d'erreur.
     *
     * @param composant étiquette du composant
     * @param message   texte à afficher
     * @param cause     exception à l'origine de l'erreur (peut être null)
     */
    public static synchronized void error(String composant, String message, Throwable cause) {
        System.err.printf("%s [%s] ERREUR : %s%s%n", LocalTime.now().format(FORMAT), composant, message,
                cause == null ? "" : " — " + cause);
    }
}
