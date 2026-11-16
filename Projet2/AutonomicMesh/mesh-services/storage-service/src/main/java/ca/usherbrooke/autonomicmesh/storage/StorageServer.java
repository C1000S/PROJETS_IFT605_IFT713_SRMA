package ca.usherbrooke.autonomicmesh.storage;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class StorageServer {

    public static void main(String[] args) throws Exception {

        int port = args.length > 0
                ? Integer.parseInt(args[0])
                : 50052;

        Server serveur = ServerBuilder
                .forPort(port)
                .addService(new StorageServiceImpl())
                .build()
                .start();

        System.out.println("==================================");
        System.out.println("      STORAGE SERVICE DEMARRE");
        System.out.println("      Port : " + port);
        System.out.println("==================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Arrêt de StorageService...");
            serveur.shutdown();
        }));

        serveur.awaitTermination();
    }
}
