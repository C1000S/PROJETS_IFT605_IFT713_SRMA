package ca.usherbrooke.autonomicmesh.storage;

import ca.usherbrooke.ift605.mesh.proto.MeshProto;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class StorageServiceImpl extends StorageServiceGrpc.StorageServiceImplBase {

    private final ConcurrentMap<String, String> donnees =
            new ConcurrentHashMap<>();

    private final long startTime = System.currentTimeMillis();

    @Override
    public void store(
            MeshProto.StoreRequest request,
            StreamObserver<MeshProto.StoreResponse> responseObserver) {

        String key = request.getKey();

        if (key == null || key.isBlank()) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription("La clé ne peut pas être vide.")
                            .asRuntimeException());
            return;
        }

        donnees.put(key, request.getValue());

        MeshProto.StoreResponse reponse =
                MeshProto.StoreResponse.newBuilder()
                        .setStored(true)
                        .build();

        responseObserver.onNext(reponse);
        responseObserver.onCompleted();
    }

    @Override
    public void retrieve(
            MeshProto.RetrieveRequest request,
            StreamObserver<MeshProto.RetrieveResponse> responseObserver) {

        String value = donnees.get(request.getKey());
        boolean found = value != null;

        MeshProto.RetrieveResponse reponse =
                MeshProto.RetrieveResponse.newBuilder()
                        .setFound(found)
                        .setValue(found ? value : "")
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
