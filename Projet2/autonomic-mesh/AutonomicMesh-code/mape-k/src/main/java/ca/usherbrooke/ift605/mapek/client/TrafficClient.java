package ca.usherbrooke.ift605.mapek.client;

import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.proto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.NotifRequest;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.PutRequest;
import ca.usherbrooke.ift605.mesh.proto.RegistryServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.ResolveRequest;
import ca.usherbrooke.ift605.mesh.proto.ResolveResponse;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Générateur de trafic pour la démonstration : à chaque seconde, résout chaque service
 * auprès du registre puis l'appelle. Les logs montrent quelle instance a servi la
 * requête, ce qui rend visibles les réacheminements et mises à l'échelle.
 * <p>
 * Usage : {@code java -cp mape-k/target/mape-k-all.jar ca.usherbrooke.ift605.mapek.client.TrafficClient [periodeMs]}
 */
public final class TrafficClient {

    private final Map<String, ManagedChannel> canaux = new HashMap<>();

    private ManagedChannel canal(String hote, int port) {
        return canaux.computeIfAbsent(hote + ":" + port,
                k -> ManagedChannelBuilder.forAddress(hote, port).usePlaintext().build());
    }

    private void oublier(String hote, int port) {
        ManagedChannel c = canaux.remove(hote + ":" + port);
        if (c != null) c.shutdownNow();
    }

    private void appeler(ManagedChannel registre, String service, long i) {
        ResolveResponse r;
        try {
            r = RegistryServiceGrpc.newBlockingStub(registre).withDeadlineAfter(1, TimeUnit.SECONDS)
                    .resolve(ResolveRequest.newBuilder().setService(service).build());
        } catch (StatusRuntimeException e) {
            Log.warn("CLIENT", "Registre injoignable : " + e.getStatus().getCode());
            registre.resetConnectBackoff();
            return;
        }
        if (!r.getFound()) {
            Log.warn("CLIENT", service + " : aucune instance dans le registre");
            return;
        }
        ManagedChannel c = canal(r.getHost(), r.getPort());
        long t0 = System.nanoTime();
        try {
            String res = switch (service) {
                case "calc" -> {
                    var rep = CalcServiceGrpc.newBlockingStub(c).withDeadlineAfter(2, TimeUnit.SECONDS)
                            .compute(CalcRequest.newBuilder().setA(i).setB(2).setOp("*").build());
                    yield i + "*2=" + rep.getResult();
                }
                case "storage" -> {
                    StorageServiceGrpc.newBlockingStub(c).withDeadlineAfter(2, TimeUnit.SECONDS)
                            .put(PutRequest.newBuilder().setKey("k" + i).setValue("v" + i).build());
                    yield "put(k" + i + ")";
                }
                default -> {
                    NotifServiceGrpc.newBlockingStub(c).withDeadlineAfter(2, TimeUnit.SECONDS)
                            .notify(NotifRequest.newBuilder().setEvent("TICK").setPayload("#" + i).build());
                    yield "notify(TICK #" + i + ")";
                }
            };
            Log.info("CLIENT", String.format("%-7s → %-15s OK  %-18s %4d ms", service, r.getInstanceId(), res,
                    (System.nanoTime() - t0) / 1_000_000));
        } catch (StatusRuntimeException e) {
            Log.warn("CLIENT", String.format("%-7s → %-15s ÉCHEC %s", service, r.getInstanceId(),
                    e.getStatus().getCode()));
            oublier(r.getHost(), r.getPort());
        }
    }

    /**
     * @param args [periodeMs] (1000 par défaut)
     * @throws InterruptedException si interrompu
     */
    public static void main(String[] args) throws InterruptedException {
        long periode = args.length > 0 ? Long.parseLong(args[0]) : 1000;
        TrafficClient client = new TrafficClient();
        ManagedChannel registre = ManagedChannelBuilder.forAddress(MeshConfig.HOTE, MeshConfig.PORT_REGISTRE)
                .usePlaintext().build();
        for (long i = 1; ; i++) {
            for (String s : new String[]{"calc", "storage", "notif"}) {
                client.appeler(registre, s, i);
            }
            Thread.sleep(periode);
        }
    }
}
