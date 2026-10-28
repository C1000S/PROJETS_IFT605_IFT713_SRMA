package ca.udes.ift605.smartagent.coordinateur;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Petit client de l'API REST d'openHAB (Semaine 3 — « intégrer le coordinateur
 * RMI avec les règles openHAB via l'API REST »).
 *
 * Rôle : en plus de la voie RMI→MQTT que suit déjà une consigne de thermostat,
 * le coordinateur pousse DIRECTEMENT un résumé de sa décision dans openHAB en
 * envoyant une commande sur un Item (ici Batiment_Coordination). Côté openHAB,
 * une règle DSL écoute cet Item et réagit : c'est exactement le « pont »
 * coordinateur ↔ règles openHAB demandé par l'énoncé, réalisé par HTTP REST
 * plutôt que par le broker MQTT.
 *
 * Pourquoi HttpClient du JDK et aucune dépendance Maven : java.net.http
 * (JEP 110, standard depuis Java 11) couvre tout le besoin ; ajouter une
 * librairie HTTP tierce pour un unique POST texte serait superflu.
 *
 * Contrat REST openHAB utilisé :
 *   POST /rest/items/{item}   Content-Type: text/plain   corps = la commande
 *   -> envoie une COMMANDE à l'Item (déclenche les règles « received command »
 *      et « changed »), à la différence de PUT .../state qui ne ferait que
 *      forcer l'état sans déclencher la sémantique de commande.
 */
public class OpenHabRestClient {

    private final String baseUrl;
    private final HttpClient http;

    /**
     * @param baseUrl racine de l'instance openHAB, p. ex. "http://localhost:8080"
     *                (sans slash final).
     */
    public OpenHabRestClient(String baseUrl) {
        // On retire un éventuel '/' final pour pouvoir concaténer proprement.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // Timeout de connexion court : si openHAB est absent, on veut échouer
        // vite et continuer le cycle d'équilibrage, pas bloquer le coordinateur.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Envoie une commande à un Item openHAB. Entièrement <b>best-effort</b> :
     * si openHAB est injoignable ou répond en erreur, on journalise et on
     * renvoie {@code false} sans jamais propager d'exception — la tolérance aux
     * pannes de la Tâche 3.1 vaut aussi pour cette intégration optionnelle.
     *
     * @param item     nom de l'Item openHAB (p. ex. "Batiment_Coordination")
     * @param commande valeur de la commande (texte brut)
     * @return true si openHAB a accepté la commande (HTTP 2xx), false sinon
     */
    public boolean envoyerCommande(String item, String commande) {
        try {
            HttpRequest requete = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rest/items/" + item))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "text/plain")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(commande))
                    .build();

            HttpResponse<String> reponse = http.send(requete, HttpResponse.BodyHandlers.ofString());
            int code = reponse.statusCode();
            if (code >= 200 && code < 300) {
                System.out.printf("[Coordinateur->openHAB REST] %s <- \"%s\" (HTTP %d)%n", item, commande, code);
                return true;
            }
            System.err.printf("[Coordinateur->openHAB REST] %s refusé (HTTP %d) : %s%n", item, code, reponse.body());
            return false;
        } catch (Exception e) {
            // IOException (openHAB down), InterruptedException, etc. : on continue.
            System.err.println("[Coordinateur->openHAB REST] openHAB injoignable pour " + item + " : " + e.getMessage());
            return false;
        }
    }
}
