package ca.usherbrooke.autonomicmesh.calc;

import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.lang.management.ManagementFactory;

public class CalcServiceImpl extends CalcServiceGrpc.CalcServiceImplBase {

    private final long startTime = System.currentTimeMillis();

    @Override
    public void compute(
            MeshProto.CalcRequest request,
            StreamObserver<MeshProto.CalcResponse> responseObserver) {

        long debut = System.nanoTime();

        double a = request.getA();
        double b = request.getB();
        String operation = request.getOp();

        double resultat;

        try {
            switch (operation) {
                case "+":
                    resultat = a + b;
                    break;
                case "-":
                    resultat = a - b;
                    break;
                case "*":
                    resultat = a * b;
                    break;
                case "/":
                    if (b == 0) {
                        throw new IllegalArgumentException(
                                "Division par zéro impossible.");
                    }
                    resultat = a / b;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Opération non reconnue : " + operation);
            }

            long latenceMs = (System.nanoTime() - debut) / 1_000_000;

            MeshProto.CalcResponse reponse =
                    MeshProto.CalcResponse.newBuilder()
                            .setResult(resultat)
                            .setLatencyMs(latenceMs)
                            .build();

            responseObserver.onNext(reponse);
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription(e.getMessage())
                            .asRuntimeException());
        }
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
