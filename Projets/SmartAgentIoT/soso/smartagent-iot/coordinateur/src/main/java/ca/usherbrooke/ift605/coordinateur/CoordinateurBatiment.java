package ca.usherbrooke.ift605.coordinateur;

import ca.usherbrooke.ift605.agent.Constantes;
import ca.usherbrooke.ift605.agent.IAgentPiece;
import java.rmi.RemoteException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Coordinateur central : applique la politique d'équilibrage thermique entre pièces adjacentes.
 * <p>
 * <b>Politique</b> : pour chaque paire adjacente (salleA–salleB et salleB–cuisine), si l'écart absolu
 * de température dépasse {@value #SEUIL_ECART} °C, la consigne cible est la <i>moyenne</i> des deux
 * températures, envoyée (via RMI) à la pièce la <i>plus chaude</i>.
 * <p>
 * <b>Tolérance aux pannes</b> :
 * <ul>
 *   <li>chaque agent est lu dans son propre {@code try/catch RemoteException} : un agent arrêté
 *       n'interrompt pas le cycle, les autres paires sont traitées ;</li>
 *   <li>un agent en timeout (capteur muet depuis 30 s) est traité avec sa <i>dernière valeur connue</i>,
 *       avec un avertissement dans le journal ;</li>
 *   <li>l'état de chaque agent (OK / TIMEOUT / HORS_LIGNE) est publié dans openHAB.</li>
 * </ul>
 */
public class CoordinateurBatiment {

    /** Écart de température (°C) au-delà duquel on rééquilibre. */
    public static final double SEUIL_ECART = 4.0;

    /** Paires de pièces adjacentes (énoncé : au minimum ces deux-là). */
    static final List<List<String>> PAIRES = List.of(
            List.of("salleA", "salleB"),
            List.of("salleB", "cuisine"));

    static final String ITEM_ETAT_AGENTS = "Batiment_EtatAgents";
    static final String ITEM_DERNIER_EQUILIBRAGE = "Batiment_DernierEquilibrage";

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Statut d'un agent vu par le coordinateur. */
    public enum Statut { OK, TIMEOUT, HORS_LIGNE }

    /** Photo d'une pièce au début d'un cycle (une seule série d'appels RMI par agent et par cycle). */
    record Releve(String piece, double temperature, Statut statut) { }

    private final FournisseurAgents agents;
    private final NotificateurOpenHab openhab;
    private final Consumer<String> journal;

    public CoordinateurBatiment(FournisseurAgents agents, NotificateurOpenHab openhab, Consumer<String> journal) {
        this.agents = agents;
        this.openhab = openhab;
        this.journal = journal;
    }

    /** Un cycle complet : lecture de tous les agents, équilibrage des paires, publication de l'état. */
    public void equilibrerTemperatures() {
        Map<String, Releve> releves = lireReleves();
        for (List<String> paire : PAIRES) {
            traiterPaire(paire.get(0), paire.get(1), releves);
        }
        openhab.publierEtat(ITEM_ETAT_AGENTS, resume(releves));
    }

    /** Consigne cible = moyenne des deux températures, arrondie au dixième. */
    static double calculerConsigne(double tChaude, double tFroide) {
        return Math.round((tChaude + tFroide) / 2.0 * 10) / 10.0;
    }

    private Map<String, Releve> lireReleves() {
        Map<String, Releve> releves = new LinkedHashMap<>();
        for (String piece : Constantes.PIECES) {
            releves.put(piece, lireReleve(piece));
        }
        return releves;
    }

    private Releve lireReleve(String piece) {
        IAgentPiece agent = agents.obtenir(piece);
        if (agent == null) {
            return new Releve(piece, Double.NaN, Statut.HORS_LIGNE);
        }
        try {
            double temperature = agent.getTemperature();
            Statut statut = agent.isEnTimeout() ? Statut.TIMEOUT : Statut.OK;
            return new Releve(piece, temperature, statut);
        } catch (RemoteException e) {
            log("ERROR", "RemoteException avec " + piece + " : " + premiereLigne(e));
            agents.oublier(piece); // le stub est périmé : nouveau lookup au prochain cycle
            return new Releve(piece, Double.NaN, Statut.HORS_LIGNE);
        }
    }

    private void traiterPaire(String p1, String p2, Map<String, Releve> releves) {
        Releve a = releves.get(p1);
        Releve b = releves.get(p2);
        String paire = p1 + "-" + p2;

        if (a.statut() == Statut.HORS_LIGNE || b.statut() == Statut.HORS_LIGNE) {
            log("WARN", paire + " : agent hors ligne, paire ignorée pour ce cycle");
            return;
        }
        if (Double.isNaN(a.temperature()) || Double.isNaN(b.temperature())) {
            log("WARN", paire + " : aucune mesure reçue pour l'instant, paire ignorée");
            return;
        }
        if (a.statut() == Statut.TIMEOUT || b.statut() == Statut.TIMEOUT) {
            log("WARN", paire + " : capteur en timeout, dernière valeur connue utilisée");
        }

        double ecart = Math.abs(a.temperature() - b.temperature());
        if (ecart <= SEUIL_ECART) {
            log("INFO", String.format(Locale.US, "%s | %.1f / %.1f | écart %.1f <= %.1f : équilibré",
                    paire, a.temperature(), b.temperature(), ecart, SEUIL_ECART));
            return;
        }

        Releve chaude = a.temperature() > b.temperature() ? a : b;
        Releve froide = (chaude == a) ? b : a;
        double consigne = calculerConsigne(chaude.temperature(), froide.temperature());

        IAgentPiece cible = agents.obtenir(chaude.piece());
        if (cible == null) {
            log("WARN", paire + " : agent " + chaude.piece() + " introuvable, commande non envoyée");
            return;
        }
        try {
            cible.setConsigneTemperature(consigne); // appel RMI -> l'agent publie sur MQTT
            String decision = String.format(Locale.US,
                    "%s | %s=%.1f °C, %s=%.1f °C | écart %.1f > %.1f -> consigne %.1f envoyée à %s",
                    paire, chaude.piece(), chaude.temperature(), froide.piece(), froide.temperature(),
                    ecart, SEUIL_ECART, consigne, chaude.piece());
            log("INFO", decision);
            openhab.publierEtat(ITEM_DERNIER_EQUILIBRAGE, decision);
        } catch (RemoteException e) {
            log("ERROR", "RemoteException en envoyant la consigne à " + chaude.piece() + " : " + premiereLigne(e));
            agents.oublier(chaude.piece());
        }
    }

    private static String resume(Map<String, Releve> releves) {
        return releves.values().stream()
                .map(r -> r.piece() + "=" + r.statut())
                .collect(Collectors.joining(", "));
    }

    /** Les messages de RemoteException sont multilignes : on ne garde que la première ligne. */
    private static String premiereLigne(Exception e) {
        String m = String.valueOf(e.getMessage());
        int fin = m.indexOf('\n');
        return fin < 0 ? m : m.substring(0, fin).trim();
    }

    private void log(String niveau, String message) {
        journal.accept(LocalTime.now().format(HEURE) + " [" + niveau + "] " + message);
    }

    public static void main(String[] args) {
        CoordinateurBatiment coordinateur = new CoordinateurBatiment(
                new FournisseurAgentsRmi(Constantes.HOTE_RMI, Constantes.PORT_RMI),
                OpenHabClient.depuisEnvironnement(),
                System.out::println);

        ScheduledExecutorService planificateur = Executors.newSingleThreadScheduledExecutor();
        // Piège classique : si la tâche lève une exception non attrapée, TOUTES les exécutions
        // suivantes sont annulées silencieusement. D'où le catch (Throwable).
        planificateur.scheduleWithFixedDelay(() -> {
            try {
                coordinateur.equilibrerTemperatures();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, 0, 5, TimeUnit.SECONDS);
        System.out.println("Coordinateur démarré : un cycle d'équilibrage toutes les 5 secondes.");
    }
}
