package ca.usherbrooke.autonomicmesh.mapek.monitor;

import ca.usherbrooke.ift605.mesh.proto.MeshProto;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.autonomicmesh.mapek.knowledge.Measurement;

import com.google.gson.Gson;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Probe {

    // Informations du microservice surveillé
    private final String serviceName;
    private final int servicePort;

    // Informations du serveur Monitor
    private final String monitorHost;
    private final int monitorPort;

    // Canal de communication gRPC
    private final ManagedChannel channel;

    // Planificateur de surveillance
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> scheduledTask;

    // Fréquence de surveillance par défaut
    private volatile int intervalSeconds = 5;

    // Dernier état connu du microservice
    private volatile String lastStatus = "UNKNOWN";

    // Convertisseur JSON
    private final Gson gson = new Gson();

    public Probe(String serviceName, int servicePort,
                 String monitorHost, int monitorPort) {

        this.serviceName = serviceName;
        this.servicePort = servicePort;
        this.monitorHost = monitorHost;
        this.monitorPort = monitorPort;

        this.channel = ManagedChannelBuilder
                .forAddress("localhost", servicePort)
                .usePlaintext()
                .build();
    }

    // Démarrer la surveillance
    public synchronized void start() {

        if (scheduler.isShutdown()) {
            System.err.println("[Probe] Impossible de démarrer "
                    + serviceName + " : sonde arrêtée.");
            return;
        }

        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            return;
        }

        System.out.println("[Probe] Surveillance de " + serviceName
                + " sur le port " + servicePort);

        scheduledTask = scheduler.scheduleAtFixedRate(
                this::checkService,
                0,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    // Vérifier l'état du microservice
    private void checkService() {

        long debut = System.nanoTime();

        String status = "DOWN";
        long uptimeMs = 0;
        double cpuLoad = 0.0;

        try {

            MeshProto.HealthReq request =
                    MeshProto.HealthReq.newBuilder()
                            .setServiceName(serviceName)
                            .build();

            MeshProto.HealthResp response;

            switch (serviceName) {

                case "CalcService" ->
                        response = CalcServiceGrpc.newBlockingStub(channel)
                                .withDeadlineAfter(3, TimeUnit.SECONDS)
                                .healthCheck(request);

                case "StorageService" ->
                        response = StorageServiceGrpc.newBlockingStub(channel)
                                .withDeadlineAfter(3, TimeUnit.SECONDS)
                                .healthCheck(request);

                case "NotifService" ->
                        response = NotifServiceGrpc.newBlockingStub(channel)
                                .withDeadlineAfter(3, TimeUnit.SECONDS)
                                .healthCheck(request);

                default ->
                        throw new IllegalArgumentException(
                                "Service inconnu : " + serviceName);
            }

            status = response.getStatus();
            uptimeMs = response.getUptimeMs();
            cpuLoad = response.getCpuLoad();

        } catch (Exception e) {

            status = "DOWN";

            System.err.println("[Probe] " + serviceName
                    + " ne répond pas : " + e.getMessage());
        }

        // Actualiser le dernier état connu
        lastStatus = status;

        // Calcul de la latence
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - debut
        );

        // Création de la mesure
        Measurement measurement = new Measurement(
                serviceName,
                System.currentTimeMillis(),
                status,
                latencyMs,
                cpuLoad,
                uptimeMs
        );

        System.out.println("[Probe] " + serviceName
                + " | État : " + status
                + " | Latence : " + latencyMs + " ms"
                + " | CPU : " + cpuLoad);

        // Transmission au Monitor
        sendMeasurement(measurement);
    }

    // Envoyer la mesure au Monitor via TCP
    private void sendMeasurement(Measurement measurement) {

        try (Socket socket = new Socket(monitorHost, monitorPort);
             PrintWriter writer = new PrintWriter(
                     socket.getOutputStream(), true)) {

            String json = gson.toJson(measurement);

            writer.println(json);

        } catch (Exception e) {

            System.err.println("[Probe] Impossible d'envoyer la mesure de "
                    + serviceName + " : " + e.getMessage());
        }
    }

    // Retourner le dernier état connu
    public String getLastStatus() {
        return lastStatus;
    }

    // Vérifier si le microservice est opérationnel
    public boolean isHealthy() {
        return "SERVING".equalsIgnoreCase(lastStatus);
    }

    // Modifier dynamiquement la fréquence de surveillance
    public synchronized void setIntervalSeconds(int seconds) {

        if (seconds < 1) {
            throw new IllegalArgumentException(
                    "L'intervalle doit être supérieur à zéro.");
        }

        intervalSeconds = seconds;

        if (scheduledTask != null && !scheduler.isShutdown()) {

            scheduledTask.cancel(false);

            scheduledTask = scheduler.scheduleAtFixedRate(
                    this::checkService,
                    0,
                    intervalSeconds,
                    TimeUnit.SECONDS
            );
        }

        System.out.println("[Probe] " + serviceName
                + " : nouvel intervalle = "
                + intervalSeconds + " secondes.");
    }

    // Arrêter proprement la sonde
    public synchronized void stop() {

        if (scheduledTask != null) {
            scheduledTask.cancel(true);
        }

        scheduler.shutdownNow();
        channel.shutdownNow();

        System.out.println("[Probe] Surveillance arrêtée pour "
                + serviceName);
    }
}
