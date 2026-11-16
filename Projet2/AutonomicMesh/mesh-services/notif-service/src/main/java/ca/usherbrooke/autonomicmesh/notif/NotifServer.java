package ca.usherbrooke.autonomicmesh.notif;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class NotifServer {

    public static void main(String[] args) throws Exception {

        int port = args.length > 0
                ? Integer.parseInt(args[0])
                : 50053;

        Server serveur = ServerBuilder
                .forPort(port)
                .addService(new NotifServiceImpl())
                .build()
                .start();

        System.out.println("==================================");
        System.out.println("       NOTIF SERVICE DEMARRE");
        System.out.println("       Port : " + port);
        System.out.println("==================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Arrêt de NotifService...");
            serveur.shutdown();
        }));

        serveur.awaitTermination();
    }
}
