package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.CalcResponse;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.HealthResp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Service de calcul : exécute une opération arithmétique et renvoie le résultat
 * avec la latence de traitement mesurée côté serveur.
 */
public class CalcServiceImpl extends CalcServiceGrpc.CalcServiceImplBase {

    private final String instanceId;
    private final FaultState fautes;
    private final HealthReporter sante;

    /**
     * @param instanceId identifiant de l'instance
     * @param fautes     état des fautes injectées (partagé avec ChaosControl)
     */
    public CalcServiceImpl(String instanceId, FaultState fautes) {
        this.instanceId = instanceId;
        this.fautes = fautes;
        this.sante = new HealthReporter(instanceId, fautes);
    }

    @Override
    public void compute(CalcRequest req, StreamObserver<CalcResponse> resp) {
        if (fautes.indisponible()) {
            resp.onError(Status.UNAVAILABLE.withDescription(instanceId + " indisponible").asRuntimeException());
            return;
        }
        long t0 = System.currentTimeMillis();
        fautes.attendreLatenceInjectee();
        double resultat;
        switch (req.getOp()) {
            case "+" -> resultat = req.getA() + req.getB();
            case "-" -> resultat = req.getA() - req.getB();
            case "*" -> resultat = req.getA() * req.getB();
            case "/" -> resultat = req.getB() != 0 ? req.getA() / req.getB() : Double.NaN;
            default -> {
                // StatusException est « checked » : on passe par onError au lieu de throw
                resp.onError(Status.INVALID_ARGUMENT
                        .withDescription("Opération inconnue : '" + req.getOp() + "' (attendu + - * /)")
                        .asRuntimeException());
                return;
            }
        }
        resp.onNext(CalcResponse.newBuilder()
                .setResult(resultat)
                .setLatencyMs(System.currentTimeMillis() - t0)
                .setInstanceId(instanceId)
                .build());
        resp.onCompleted();
    }

    @Override
    public void healthCheck(HealthReq req, StreamObserver<HealthResp> resp) {
        resp.onNext(sante.construire());
        resp.onCompleted();
    }
}
