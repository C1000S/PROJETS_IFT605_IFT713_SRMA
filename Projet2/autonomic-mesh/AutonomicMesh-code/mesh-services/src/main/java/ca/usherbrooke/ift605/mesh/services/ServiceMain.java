package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.common.Log;
import ca.usherbrooke.ift605.mesh.common.PidFiles;
import ca.usherbrooke.ift605.mesh.common.ServiceType;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Point d'entrée d'une instance de micro-service, lancée comme processus Java indépendant.
 * <p>
 * Usage : {@code java -jar mesh-services-all.jar <calc|storage|notif> [port] [instanceId]}
 * <br>Exemple : {@code java -jar mesh-services/target/mesh-services-all.jar calc 50051 calc-1}
 */
public final class ServiceMain {

    private ServiceMain() {
    }

    /**
     * @param args type de service, port optionnel, identifiant d'instance optionnel
     * @throws InterruptedException si le thread principal est interrompu
     */
    public static void main(String[] args) throws InterruptedException {
        if (args.length < 1) {
            System.err.println("Usage : ServiceMain <calc|storage|notif> [port] [instanceId]");
            System.exit(2);
        }
        ServiceType type;
        int port;
        try {
            type = ServiceType.depuisNom(args[0]);
            port = args.length > 1 ? Integer.parseInt(args[1]) : type.portParDefaut();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        String instanceId = args.length > 2 ? args[2] : type.nom() + "-1";
        String etiquette = instanceId.toUpperCase();

        FaultState fautes = new FaultState();
        BindableService metier = switch (type) {
            case CALC -> new CalcServiceImpl(instanceId, fautes);
            case STORAGE -> new StorageServiceImpl(instanceId, fautes);
            case NOTIF -> new NotifServiceImpl(instanceId, fautes);
        };

        Server serveur;
        try {
            serveur = ServerBuilder.forPort(port)
                    .addService(metier)
                    .addService(new ChaosControlImpl(instanceId, fautes))
                    .build()
                    .start();
        } catch (IOException e) {
            Log.error(etiquette, "Impossible d'ouvrir le port " + port + " (déjà utilisé ?)", e);
            System.exit(1);
            return;
        }

        PidFiles.ecrire(instanceId);
        Log.info(etiquette, String.format("Service %s démarré sur le port %d (pid %d)",
                type.nom(), port, ProcessHandle.current().pid()));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info(etiquette, "Arrêt demandé, fermeture du serveur gRPC");
            serveur.shutdown();
            try {
                if (!serveur.awaitTermination(3, TimeUnit.SECONDS)) {
                    serveur.shutdownNow();
                }
            } catch (InterruptedException e) {
                serveur.shutdownNow();
                Thread.currentThread().interrupt();
            }
            PidFiles.supprimerSiCourant(instanceId);
        }, "arret-" + instanceId));

        serveur.awaitTermination();
    }
}
