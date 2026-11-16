package ca.usherbrooke.ift605.mapek.infra;

import ca.usherbrooke.ift605.mapek.KnowledgeBase;
import ca.usherbrooke.ift605.mapek.model.InstanceInfo;
import ca.usherbrooke.ift605.mapek.model.MesureSante;
import ca.usherbrooke.ift605.mapek.model.Role;
import ca.usherbrooke.ift605.mesh.proto.InstanceDescriptor;
import ca.usherbrooke.ift605.mesh.proto.ListInstancesRequest;
import ca.usherbrooke.ift605.mesh.proto.ListInstancesResponse;
import ca.usherbrooke.ift605.mesh.proto.RegistryServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.ResolveRequest;
import ca.usherbrooke.ift605.mesh.proto.ResolveResponse;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;

/**
 * Registre gRPC exposé par le gestionnaire autonome. C'est par lui que l'effet d'un
 * réacheminement ou d'une mise à l'échelle devient observable : les clients résolvent
 * le service avant chaque appel et suivent la table de routage de la KnowledgeBase.
 * <p>
 * Résolution : candidats = cible du routage + répliques. Les candidats SERVING sont
 * préférés (tourniquet entre eux) ; à défaut, les candidats utilisables (non DOWN) ;
 * à défaut, la cible du routage telle quelle.
 */
public class RegistryServiceImpl extends RegistryServiceGrpc.RegistryServiceImplBase {

    private final KnowledgeBase kb;
    private final long peremptionMs;

    /**
     * @param kb           base de connaissances (routage, instances, mesures)
     * @param peremptionMs âge maximal d'une mesure pour considérer une instance utilisable
     */
    public RegistryServiceImpl(KnowledgeBase kb, long peremptionMs) {
        this.kb = kb;
        this.peremptionMs = peremptionMs;
    }

    @Override
    public void resolve(ResolveRequest req, StreamObserver<ResolveResponse> resp) {
        String service = req.getService();
        List<InstanceInfo> candidats = new ArrayList<>();
        String cible = kb.routage(service);
        if (cible != null) {
            kb.instance(cible).ifPresent(candidats::add);
        }
        candidats.addAll(kb.instances(service, Role.REPLIQUE));

        List<InstanceInfo> saines = candidats.stream().filter(i -> {
            MesureSante m = kb.derniereMesure(i.instanceId());
            return m != null && "SERVING".equals(m.statut) && kb.estUtilisable(i.instanceId(), peremptionMs);
        }).toList();
        List<InstanceInfo> utilisables = candidats.stream()
                .filter(i -> kb.estUtilisable(i.instanceId(), peremptionMs)).toList();

        List<InstanceInfo> choix = !saines.isEmpty() ? saines : !utilisables.isEmpty() ? utilisables : candidats;
        ResolveResponse.Builder b = ResolveResponse.newBuilder();
        if (choix.isEmpty()) {
            b.setFound(false);
        } else {
            InstanceInfo i = choix.get(kb.tourSuivant(service, choix.size()));
            b.setFound(true).setInstanceId(i.instanceId()).setHost(i.hote()).setPort(i.port());
        }
        resp.onNext(b.build());
        resp.onCompleted();
    }

    @Override
    public void listInstances(ListInstancesRequest req, StreamObserver<ListInstancesResponse> resp) {
        ListInstancesResponse.Builder b = ListInstancesResponse.newBuilder();
        for (InstanceInfo i : kb.instances()) {
            b.addInstances(InstanceDescriptor.newBuilder()
                    .setInstanceId(i.instanceId()).setService(i.service())
                    .setHost(i.hote()).setPort(i.port()).setRole(i.role().name()));
        }
        resp.onNext(b.build());
        resp.onCompleted();
    }
}
