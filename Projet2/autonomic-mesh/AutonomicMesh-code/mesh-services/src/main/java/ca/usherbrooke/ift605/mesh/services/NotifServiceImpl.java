package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.proto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.NotifRequest;
import ca.usherbrooke.ift605.mesh.proto.NotifResponse;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Service de notification : reçoit un événement, le journalise et confirme sa prise en charge.
 */
public class NotifServiceImpl extends NotifServiceGrpc.NotifServiceImplBase {

    private final String instanceId;
    private final FaultState fautes;
    private final HealthReporter sante;
    private final AtomicLong compteur = new AtomicLong();

    /**
     * @param instanceId identifiant de l'instance
     * @param fautes     état des fautes injectées
     */
    public NotifServiceImpl(String instanceId, FaultState fautes) {
        this.instanceId = instanceId;
        this.fautes = fautes;
        this.sante = new HealthReporter(instanceId, fautes);
    }

    @Override
    public void notify(NotifRequest req, StreamObserver<NotifResponse> resp) {
        if (fautes.indisponible()) {
            resp.onError(Status.UNAVAILABLE.withDescription(instanceId + " indisponible").asRuntimeException());
            return;
        }
        if (req.getEvent().isBlank()) {
            resp.onError(Status.INVALID_ARGUMENT.withDescription("L'événement est obligatoire").asRuntimeException());
            return;
        }
        fautes.attendreLatenceInjectee();
        long n = compteur.incrementAndGet();
        Log.info("NOTIF", String.format("#%d événement=%s payload=%s", n, req.getEvent(), req.getPayload()));
        resp.onNext(NotifResponse.newBuilder().setAccepted(true).setInstanceId(instanceId).build());
        resp.onCompleted();
    }

    @Override
    public void healthCheck(HealthReq req, StreamObserver<HealthResp> resp) {
        resp.onNext(sante.construire());
        resp.onCompleted();
    }
}
