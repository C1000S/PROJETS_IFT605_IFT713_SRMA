package ca.usherbrooke.ift605.mesh.client;

import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.common.ServiceType;
import ca.usherbrooke.ift605.mesh.proto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.CalcResponse;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.GetRequest;
import ca.usherbrooke.ift605.mesh.proto.GetResponse;
import ca.usherbrooke.ift605.mesh.proto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.NotifRequest;
import ca.usherbrooke.ift605.mesh.proto.NotifResponse;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.PutRequest;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.TimeUnit;

/**
 * Client de validation de la semaine 1 : appelle l'opération métier et le HealthCheck
 * de chacun des trois services sur leurs ports par défaut.
 * <p>
 * Usage : {@code java -cp mesh-services-all.jar ca.usherbrooke.ift605.mesh.client.MeshTestClient}
 */
public final class MeshTestClient {

    private MeshTestClient() {
    }

    private static ManagedChannel canal(ServiceType t) {
        return ManagedChannelBuilder.forAddress(MeshConfig.HOTE, t.portParDefaut()).usePlaintext().build();
    }

    private static String sante(HealthResp h) {
        return String.format("statut=%s uptime=%dms cpu=%.2f", h.getStatus(), h.getUptimeMs(), h.getCpuLoad());
    }

    /**
     * @param args non utilisés
     * @throws InterruptedException si l'arrêt des canaux est interrompu
     */
    public static void main(String[] args) throws InterruptedException {
        int echecs = 0;

        ManagedChannel c1 = canal(ServiceType.CALC);
        try {
            var stub = CalcServiceGrpc.newBlockingStub(c1).withDeadlineAfter(3, TimeUnit.SECONDS);
            CalcResponse r = stub.compute(CalcRequest.newBuilder().setA(6).setB(7).setOp("*").build());
            System.out.printf("[CALC]    6 * 7 = %.1f (latence serveur %d ms, instance %s)%n",
                    r.getResult(), r.getLatencyMs(), r.getInstanceId());
            System.out.println("[CALC]    HealthCheck : " + sante(
                    CalcServiceGrpc.newBlockingStub(c1).withDeadlineAfter(3, TimeUnit.SECONDS)
                            .healthCheck(HealthReq.newBuilder().setServiceName("calc").build())));
        } catch (StatusRuntimeException e) {
            echecs++;
            System.out.println("[CALC]    ÉCHEC : " + e.getStatus());
        } finally {
            c1.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }

        ManagedChannel c2 = canal(ServiceType.STORAGE);
        try {
            var stub = StorageServiceGrpc.newBlockingStub(c2).withDeadlineAfter(3, TimeUnit.SECONDS);
            stub.put(PutRequest.newBuilder().setKey("cours").setValue("IFT605").build());
            GetResponse g = StorageServiceGrpc.newBlockingStub(c2).withDeadlineAfter(3, TimeUnit.SECONDS)
                    .get(GetRequest.newBuilder().setKey("cours").build());
            System.out.printf("[STORAGE] put(cours=IFT605) puis get(cours) → trouvé=%s valeur=%s (instance %s)%n",
                    g.getFound(), g.getValue(), g.getInstanceId());
            System.out.println("[STORAGE] HealthCheck : " + sante(
                    StorageServiceGrpc.newBlockingStub(c2).withDeadlineAfter(3, TimeUnit.SECONDS)
                            .healthCheck(HealthReq.newBuilder().setServiceName("storage").build())));
        } catch (StatusRuntimeException e) {
            echecs++;
            System.out.println("[STORAGE] ÉCHEC : " + e.getStatus());
        } finally {
            c2.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }

        ManagedChannel c3 = canal(ServiceType.NOTIF);
        try {
            var stub = NotifServiceGrpc.newBlockingStub(c3).withDeadlineAfter(3, TimeUnit.SECONDS);
            NotifResponse n = stub.notify(NotifRequest.newBuilder().setEvent("TEST").setPayload("bonjour").build());
            System.out.printf("[NOTIF]   notify(TEST) → acceptée=%s (instance %s)%n", n.getAccepted(), n.getInstanceId());
            System.out.println("[NOTIF]   HealthCheck : " + sante(
                    NotifServiceGrpc.newBlockingStub(c3).withDeadlineAfter(3, TimeUnit.SECONDS)
                            .healthCheck(HealthReq.newBuilder().setServiceName("notif").build())));
        } catch (StatusRuntimeException e) {
            echecs++;
            System.out.println("[NOTIF]   ÉCHEC : " + e.getStatus());
        } finally {
            c3.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }

        System.out.println(echecs == 0 ? "Validation OK : les 3 services répondent."
                : echecs + " service(s) en échec.");
        System.exit(echecs == 0 ? 0 : 1);
    }
}
