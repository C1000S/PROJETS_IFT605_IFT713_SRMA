package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.GetRequest;
import ca.usherbrooke.ift605.mesh.proto.GetResponse;
import ca.usherbrooke.ift605.mesh.proto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.PutRequest;
import ca.usherbrooke.ift605.mesh.proto.PutResponse;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Service de stockage clé/valeur en mémoire (Put / Get).
 * <p>
 * Limite assumée : les données sont propres à chaque instance et perdues au
 * redémarrage (pas de réplication). À discuter dans l'analyse des limites.
 */
public class StorageServiceImpl extends StorageServiceGrpc.StorageServiceImplBase {

    private final String instanceId;
    private final FaultState fautes;
    private final HealthReporter sante;
    private final ConcurrentHashMap<String, String> donnees = new ConcurrentHashMap<>();

    /**
     * @param instanceId identifiant de l'instance
     * @param fautes     état des fautes injectées
     */
    public StorageServiceImpl(String instanceId, FaultState fautes) {
        this.instanceId = instanceId;
        this.fautes = fautes;
        this.sante = new HealthReporter(instanceId, fautes);
    }

    @Override
    public void put(PutRequest req, StreamObserver<PutResponse> resp) {
        if (fautes.indisponible()) {
            resp.onError(Status.UNAVAILABLE.withDescription(instanceId + " indisponible").asRuntimeException());
            return;
        }
        if (req.getKey().isBlank()) {
            resp.onError(Status.INVALID_ARGUMENT.withDescription("La clé ne peut pas être vide").asRuntimeException());
            return;
        }
        fautes.attendreLatenceInjectee();
        donnees.put(req.getKey(), req.getValue());
        resp.onNext(PutResponse.newBuilder().setStored(true).setInstanceId(instanceId).build());
        resp.onCompleted();
    }

    @Override
    public void get(GetRequest req, StreamObserver<GetResponse> resp) {
        if (fautes.indisponible()) {
            resp.onError(Status.UNAVAILABLE.withDescription(instanceId + " indisponible").asRuntimeException());
            return;
        }
        fautes.attendreLatenceInjectee();
        String valeur = donnees.get(req.getKey());
        resp.onNext(GetResponse.newBuilder()
                .setFound(valeur != null)
                .setValue(valeur == null ? "" : valeur)
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
