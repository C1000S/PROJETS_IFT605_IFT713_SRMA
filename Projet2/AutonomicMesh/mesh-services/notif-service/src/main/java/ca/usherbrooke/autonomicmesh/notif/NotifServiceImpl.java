package ca.usherbrooke.autonomicmesh.notif;

import ca.usherbrooke.ift605.mesh.proto.MeshProto;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

public class NotifServiceImpl extends NotifServiceGrpc.NotifServiceImplBase {

    private final long startTime = System.currentTimeMillis();
    private final AtomicLong nombreNotifications = new AtomicLong(0);

    @Override
    public void notify(
            MeshProto.NotifRequest request,
            StreamObserver<MeshProto.NotifResponse> responseObserver) {

        if (request.getEvent().isBlank()) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription("Le nom de l'événement est obligatoire.")
                            .asRuntimeException());
            return;
        }

        nombreNotifications.incrementAndGet();

        System.out.println("Nouvel événement reçu : " + request.getEvent());
        System.out.println("Contenu : " + request.getPayload());

        MeshProto.NotifResponse reponse =
                MeshProto.NotifResponse.newBuilder()
                        .setAccepted(true)
                        .build();

        responseObserver.onNext(reponse);
        responseObserver.onCompleted();
    }

    @Override
    public void healthCheck(
            MeshProto.HealthReq request,
            StreamObserver<MeshProto.HealthResp> responseObserver) {

        long uptime = System.currentTimeMillis() - startTime;
        double cpuLoad = 0.0;

        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();

        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            cpuLoad = ((com.sun.management.OperatingSystemMXBean) bean)
                    .getCpuLoad();

            if (cpuLoad < 0) {
                cpuLoad = 0.0;
            }
        }

        String status = cpuLoad > 0.90 ? "DEGRADED" : "SERVING";

        MeshProto.HealthResp reponse =
                MeshProto.HealthResp.newBuilder()
                        .setStatus(status)
                        .setUptimeMs(uptime)
                        .setCpuLoad(cpuLoad)
                        .build();

        responseObserver.onNext(reponse);
        responseObserver.onCompleted();
    }
}
