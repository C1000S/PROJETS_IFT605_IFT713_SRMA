package ca.usherbrooke.ift605.mapek.sonde;

import ca.usherbrooke.ift605.mapek.model.MesureSante;
import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.common.ServiceType;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import com.google.gson.Gson;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Sonde d'une instance : toutes les 5 s, appelle le HealthCheck gRPC, mesure la latence
 * aller-retour et transmet une {@link MesureSante} JSON au Monitor par socket TCP (IPC).
 * <p>
 * Si l'appel gRPC échoue (processus tué, délai dépassé), la sonde produit une mesure
 * DOWN au lieu de s'arrêter. Le canal gRPC est recréé après un échec pour éviter
 * l'attente exponentielle de reconnexion de gRPC, qui fausserait le MTTR.
 */
public class Sonde implements Runnable {

    private final String service;
    private final String instanceId;
    private final String hote;
    private final int grpcPort;  // port du service à surveiller
    private final int ipcPort;   // port de rapport vers le gestionnaire
    private final Gson gson = new Gson();
    private volatile boolean active = true;
    private ManagedChannel canal;

    /**
     * @param service    nom logique (calc, storage, notif)
     * @param instanceId instance surveillée
     * @param hote       hôte gRPC
     * @param grpcPort   port gRPC de l'instance
     * @param ipcPort    port IPC du Monitor
     */
    public Sonde(String service, String instanceId, String hote, int grpcPort, int ipcPort) {
        this.service = service;
        this.instanceId = instanceId;
        this.hote = hote;
        this.grpcPort = grpcPort;
        this.ipcPort = ipcPort;
    }

    @Override
    public void run() {
        Log.info("SONDE", "Démarrage de la sonde " + instanceId + " (port " + grpcPort + ")");
        while (active) {
            MesureSante m = pinger();
            envoyerIPC(m);
            try {
                Thread.sleep(MeshConfig.PERIODE_SONDE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fermerCanal();
        Log.info("SONDE", "Sonde " + instanceId + " arrêtée");
    }

    /** Demande l'arrêt de la sonde à la fin de l'itération en cours. */
    public void arreter() {
        active = false;
    }

    /**
     * Effectue un HealthCheck gRPC et construit la mesure correspondante.
     *
     * @return mesure de santé (DOWN si l'appel échoue)
     */
    MesureSante pinger() {
        if (canal == null) {
            canal = ManagedChannelBuilder.forAddress(hote, grpcPort).usePlaintext().build();
        }
        HealthReq req = HealthReq.newBuilder().setServiceName(service).build();
        long t0 = System.nanoTime();
        try {
            HealthResp r = appeler(req);
            long latence = (System.nanoTime() - t0) / 1_000_000;
            String statut = switch (r.getStatus()) {
                case SERVING -> "SERVING";
                case DEGRADED -> "DEGRADED";
                default -> "DOWN";
            };
            return new MesureSante(service, instanceId, grpcPort, System.currentTimeMillis(), statut,
                    latence, Math.max(0, Math.min(1, r.getCpuLoad())), r.getUptimeMs());
        } catch (StatusRuntimeException e) {
            long latence = (System.nanoTime() - t0) / 1_000_000;
            fermerCanal(); // prochaine tentative avec un canal neuf
            return new MesureSante(service, instanceId, grpcPort, System.currentTimeMillis(), "DOWN",
                    latence, 0.0, 0);
        }
    }

    private HealthResp appeler(HealthReq req) {
        long delai = MeshConfig.DELAI_HEALTHCHECK_MS;
        return switch (ServiceType.depuisNom(service)) {
            case CALC -> CalcServiceGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(delai, TimeUnit.MILLISECONDS).healthCheck(req);
            case STORAGE -> StorageServiceGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(delai, TimeUnit.MILLISECONDS).healthCheck(req);
            case NOTIF -> NotifServiceGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(delai, TimeUnit.MILLISECONDS).healthCheck(req);
        };
    }

    /**
     * Envoie la mesure au Monitor : une connexion TCP courte, une ligne JSON.
     *
     * @param m mesure à transmettre
     */
    void envoyerIPC(MesureSante m) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(MeshConfig.HOTE, ipcPort), 1_000);
            Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            w.write(gson.toJson(m));
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            Log.warn("SONDE", "Monitor injoignable sur le port " + ipcPort + " (" + e.getMessage()
                    + "), mesure de " + instanceId + " perdue");
        }
    }

    private void fermerCanal() {
        if (canal != null) {
            canal.shutdownNow();
            canal = null;
        }
    }
}
