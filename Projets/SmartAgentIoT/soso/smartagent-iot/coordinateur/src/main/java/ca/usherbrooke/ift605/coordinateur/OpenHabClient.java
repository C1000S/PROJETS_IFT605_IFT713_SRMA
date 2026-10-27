package ca.usherbrooke.ift605.coordinateur;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client minimal de l'API REST d'openHAB.
 * <p>
 * {@code PUT /rest/items/{item}/state} avec le texte brut en corps : met à jour l'état d'un Item.
 * Les règles DSL peuvent ensuite réagir à cette mise à jour ({@code received update}).
 * <p>
 * Configuration par variables d'environnement :
 * <ul>
 *   <li>{@code OPENHAB_URL} (défaut {@code http://localhost:8080}) ;</li>
 *   <li>{@code OPENHAB_TOKEN} (optionnel) : jeton d'API, à créer dans le profil openHAB si
 *       l'API répond 401.</li>
 * </ul>
 */
public class OpenHabClient implements NotificateurOpenHab {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String baseUrl;
    private final String token;
    private volatile boolean disponible = true; // sert à ne journaliser que les changements

    public OpenHabClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    public static OpenHabClient depuisEnvironnement() {
        String url = System.getenv().getOrDefault("OPENHAB_URL", "http://localhost:8080");
        return new OpenHabClient(url, System.getenv("OPENHAB_TOKEN"));
    }

    @Override
    public void publierEtat(String item, String valeur) {
        HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create(baseUrl + "/rest/items/" + item + "/state"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "text/plain; charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(valeur, StandardCharsets.UTF_8));
        if (token != null && !token.isBlank()) {
            requete.header("X-OPENHAB-TOKEN", token);
        }
        try {
            HttpResponse<Void> reponse = http.send(requete.build(), HttpResponse.BodyHandlers.discarding());
            signaler(reponse.statusCode() / 100 == 2, "HTTP " + reponse.statusCode() + " sur " + item);
        } catch (IOException e) {
            signaler(false, e.getClass().getSimpleName() + " : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** N'écrit dans le journal que quand l'état « openHAB joignable » change (évite le bruit). */
    private void signaler(boolean ok, String detail) {
        if (ok != disponible) {
            disponible = ok;
            System.out.println(ok ? "[openHAB] de nouveau joignable"
                                  : "[openHAB] indisponible, on continue sans lui (" + detail + ")");
        }
    }
}
