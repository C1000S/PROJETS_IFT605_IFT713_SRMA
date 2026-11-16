package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.proto.ChaosControlGrpc;
import ca.usherbrooke.ift605.mesh.proto.FaultRequest;
import ca.usherbrooke.ift605.mesh.proto.FaultResponse;
import io.grpc.stub.StreamObserver;

/**
 * Point d'injection de fautes exposé par chaque instance, utilisé par le ChaosMonkey
 * pour simuler une dégradation (latence), une surcharge (CPU) ou une indisponibilité.
 */
public class ChaosControlImpl extends ChaosControlGrpc.ChaosControlImplBase {

    private final String instanceId;
    private final FaultState fautes;

    /**
     * @param instanceId identifiant de l'instance
     * @param fautes     état des fautes partagé avec le service métier
     */
    public ChaosControlImpl(String instanceId, FaultState fautes) {
        this.instanceId = instanceId;
        this.fautes = fautes;
    }

    @Override
    public void injectFault(FaultRequest req, StreamObserver<FaultResponse> resp) {
        String etat = fautes.appliquer(req);
        Log.info("CHAOS-CTRL", instanceId + " ← " + req.getType() + " : " + etat);
        resp.onNext(FaultResponse.newBuilder().setApplied(true).setDetail(etat).build());
        resp.onCompleted();
    }
}
