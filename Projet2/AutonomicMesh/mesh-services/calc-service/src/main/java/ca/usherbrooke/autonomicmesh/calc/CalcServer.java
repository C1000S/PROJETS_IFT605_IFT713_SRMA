package ca.usherbrooke.autonomicmesh.calc;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class CalcServer {

    public static void main(String[] args) throws Exception {

        int port = args.length > 0
                ? Integer.parseInt(args[0])
                : 50051;

        Server serveur = ServerBuilder
                .forPort(port)
                .addService(new CalcServiceImpl())
                .build()
                .start();

        System.out.println("==================================");
        System.out.println("       CALC SERVICE DEMARRE");
        System.out.println("       Port : " + port);
        System.out.println("==================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Arrêt de CalcService...");
            serveur.shutdown();
        }));

        serveur.awaitTermination();
    }
}
