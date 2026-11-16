package ca.usherbrooke.ift605.mapek.sonde;

import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.MeshConfig;
import ca.usherbrooke.ift605.mesh.proto.InstanceDescriptor;
import ca.usherbrooke.ift605.mesh.proto.ListInstancesRequest;
import ca.usherbrooke.ift605.mesh.proto.RegistryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Processus des sondes : découvre les instances auprès du registre du gestionnaire
 * (gRPC ListInstances) et lance une {@link Sonde} — donc un thread — par instance.
 * Les nouvelles instances (répliques créées par mise à l'échelle) sont prises en compte
 * automatiquement.
 * <p>
 * Usage : {@code java -cp mape-k-all.jar ca.usherbrooke.ift605.mapek.sonde.ProbeAgent [portRegistre] [portIpc]}
 */
public final class ProbeAgent {

    private final int portRegistre;
    private final int portIpc;
    private final Map<String, Sonde> sondes = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newCachedThreadPool();

    /**
     * @param portRegistre port gRPC du registre
     * @param portIpc      port IPC du Monitor
     */
    public ProbeAgent(int portRegistre, int portIpc) {
        this.portRegistre = portRegistre;
        this.portIpc = portIpc;
    }

    /**
     * Boucle de découverte (bloquante).
     *
     * @throws InterruptedException si le thread est interrompu
     */
    public void executer() throws InterruptedException {
        ManagedChannel registre = ManagedChannelBuilder.forAddress(MeshConfig.HOTE, portRegistre)
                .usePlaintext().build();
        Log.info("SONDES", "Agent de sondes démarré (registre " + portRegistre + ", IPC " + portIpc + ")");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    synchroniser(RegistryServiceGrpc.newBlockingStub(registre)
                            .withDeadlineAfter(2, TimeUnit.SECONDS)
                            .listInstances(ListInstancesRequest.getDefaultInstance())
                            .getInstancesList());
                } catch (StatusRuntimeException e) {
                    Log.warn("SONDES", "Registre injoignable : " + e.getStatus().getCode() + ", nouvel essai");
                    registre.resetConnectBackoff();
                }
                Thread.sleep(MeshConfig.PERIODE_SONDE_MS);
            }
        } finally {
            sondes.values().forEach(Sonde::arreter);
            threads.shutdownNow();
            registre.shutdownNow();
        }
    }

    private void synchroniser(java.util.List<InstanceDescriptor> instances) {
        Set<String> vues = new HashSet<>();
        for (InstanceDescriptor d : instances) {
            vues.add(d.getInstanceId());
            sondes.computeIfAbsent(d.getInstanceId(), id -> {
                Sonde s = new Sonde(d.getService(), id, d.getHost(), d.getPort(), portIpc);
                threads.submit(s);
                return s;
            });
        }
        for (String id : Set.copyOf(sondes.keySet())) {
            if (!vues.contains(id)) {
                sondes.remove(id).arreter();
            }
        }
    }

    /**
     * @param args [portRegistre] [portIpc]
     * @throws InterruptedException si interrompu
     */
    public static void main(String[] args) throws InterruptedException {
        int registre = args.length > 0 ? Integer.parseInt(args[0]) : MeshConfig.PORT_REGISTRE;
        int ipc = args.length > 1 ? Integer.parseInt(args[1]) : MeshConfig.PORT_IPC;
        new ProbeAgent(registre, ipc).executer();
    }
}
