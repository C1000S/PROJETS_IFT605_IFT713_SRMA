package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mapek.model.MesureSante;
import ca.usherbrooke.ift605.mesh.common.Log;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M de MAPE-K : reçoit les mesures des sondes par socket TCP (une mesure JSON par ligne),
 * les valide et met à jour la KnowledgeBase.
 * <p>
 * Le Monitor ne décide d'aucune action. Chaque connexion est traitée dans un thread
 * du pool, ce qui permet à plusieurs sondes d'émettre en même temps.
 */
public class Monitor {

    private final KnowledgeBase kb;
    private final Gson gson = new Gson();
    private final ExecutorService connexions = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "monitor-connexion");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong recues = new AtomicLong();
    private final AtomicLong rejetees = new AtomicLong();
    private volatile ServerSocket server;
    private volatile boolean actif;

    /** @param kb base de connaissances à alimenter */
    public Monitor(KnowledgeBase kb) {
        this.kb = kb;
    }

    /**
     * Ouvre le port IPC et accepte les connexions jusqu'à {@link #arreter()}.
     * Méthode bloquante : à appeler dans un thread dédié.
     *
     * @param port port TCP d'écoute (9090)
     * @throws IOException si le port ne peut pas être ouvert
     */
    public void demarrer(int port) throws IOException {
        server = new ServerSocket(port);
        actif = true;
        Log.info("MONITOR", "En écoute des sondes sur le port IPC " + port);
        while (actif) {
            try {
                Socket s = server.accept();
                connexions.submit(() -> traiter(s));
            } catch (SocketException e) {
                if (actif) {
                    Log.warn("MONITOR", "Erreur d'acceptation : " + e.getMessage());
                }
            }
        }
    }

    private void traiter(Socket s) {
        try (s; BufferedReader in = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = in.readLine()) != null) {
                if (!ligne.isBlank()) {
                    recevoir(ligne);
                }
            }
        } catch (IOException e) {
            Log.warn("MONITOR", "Connexion sonde interrompue : " + e.getMessage());
        }
    }

    /**
     * Valide et stocke une mesure JSON. Visible pour les tests.
     *
     * @param json ligne JSON reçue
     * @return vrai si la mesure a été acceptée
     */
    boolean recevoir(String json) {
        MesureSante m;
        try {
            m = gson.fromJson(json, MesureSante.class);
        } catch (JsonParseException e) {
            rejetees.incrementAndGet();
            Log.warn("MONITOR", "JSON invalide rejeté : " + e.getMessage());
            return false;
        }
        String erreur = m == null ? "mesure vide" : m.erreurValidation();
        if (erreur != null) {
            rejetees.incrementAndGet();
            Log.warn("MONITOR", "Mesure rejetée (" + erreur + ") : " + json);
            return false;
        }
        recues.incrementAndGet();
        MesureSante precedente = kb.enregistrerMesure(m);
        if (precedente == null) {
            Log.info("MONITOR", "Première mesure de " + m);
        } else if (!precedente.statut.equals(m.statut)) {
            Log.info("MONITOR", String.format("Changement d'état %s : %s → %s (%d ms)",
                    m.instanceId, precedente.statut, m.statut, m.latenceMs));
        }
        return true;
    }

    /** @return nombre de mesures acceptées depuis le démarrage */
    public long nbRecues() {
        return recues.get();
    }

    /** @return nombre de mesures rejetées depuis le démarrage */
    public long nbRejetees() {
        return rejetees.get();
    }

    /** Ferme le port IPC et les connexions en cours. */
    public void arreter() {
        actif = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // arrêt
        }
        connexions.shutdownNow();
    }
}
