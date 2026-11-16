package ca.usherbrooke.autonomicmesh.mapek.monitor;

import ca.usherbrooke.autonomicmesh.mapek.knowledge.KnowledgeBase;
import ca.usherbrooke.autonomicmesh.mapek.knowledge.Measurement;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class Monitor {

    private final KnowledgeBase knowledgeBase;
    private final Gson gson = new Gson();
    private final int port;

    public Monitor(KnowledgeBase knowledgeBase, int port) {
        this.knowledgeBase = knowledgeBase;
        this.port = port;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {

            System.out.println("[Monitor] En écoute sur le port " + port);

            while (true) {
                Socket client = serverSocket.accept();

                try (client;
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream()))) {

                    String json = reader.readLine();

                    if (json == null || json.isBlank()) {
                        continue;
                    }

                    Measurement measurement =
                            gson.fromJson(json, Measurement.class);

                    if (measurement == null
                            || measurement.service() == null
                            || measurement.status() == null) {
                        System.err.println("[Monitor] Mesure invalide reçue.");
                        continue;
                    }

                    knowledgeBase.update(measurement);

                } catch (Exception e) {
                    System.err.println("[Monitor] Erreur de réception : "
                            + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("[Monitor] Impossible de démarrer : "
                    + e.getMessage());
        }
    }
}
