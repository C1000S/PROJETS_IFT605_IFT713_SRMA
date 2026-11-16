package ca.usherbrooke.autonomicmesh.tests;

import ca.usherbrooke.ift605.mesh.proto.MeshProto;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class MeshTestClient {

    private static final Path ROUTES_FILE = Path.of(
            "C:\\Users\\Mame Tabara\\Documents\\AutonomicMesh\\logs\\routes.properties"
    );

    // Lit le port actif d'un service
    private static int getServicePort(String service, int defaultPort) {

        Properties properties = new Properties();

        if (!Files.exists(ROUTES_FILE)) {
            System.out.println("[Client] Fichier de routes absent. "
                    + "Utilisation du port principal.");
            return defaultPort;
        }

        try (InputStream input = Files.newInputStream(ROUTES_FILE)) {
            properties.load(input);

            int port = Integer.parseInt(
                    properties.getProperty(service, String.valueOf(defaultPort))
            );

            System.out.println("[Client] " + service
                    + " utilise le port " + port);

            return port;

        } catch (Exception e) {
            System.err.println("[Client] Erreur de lecture des routes : "
                    + e.getMessage());
            return defaultPort;
        }
    }

    private static ManagedChannel createChannel(int port) {
        return ManagedChannelBuilder
                .forAddress("localhost", port)
                .usePlaintext()
                .build();
    }

    public static void main(String[] args) throws InterruptedException {

        ManagedChannel calcChannel = null;
        ManagedChannel storageChannel = null;
        ManagedChannel notifChannel = null;

        try {

            // ============================
            // TEST 1 : CALC SERVICE
            // ============================

            System.out.println("\n===== TEST CALC SERVICE =====");

            int calcPort = getServicePort("CalcService", 50051);
            calcChannel = createChannel(calcPort);

            CalcServiceGrpc.CalcServiceBlockingStub calcStub =
                    CalcServiceGrpc.newBlockingStub(calcChannel);

            MeshProto.CalcRequest calcRequest = MeshProto.CalcRequest.newBuilder()
                    .setA(10)
                    .setB(5)
                    .setOp("+")
                    .build();

            MeshProto.CalcResponse calcResponse =
                    calcStub.compute(calcRequest);

            System.out.println("10 + 5 = " + calcResponse.getResult());
            System.out.println("Latence : " + calcResponse.getLatencyMs() + " ms");

            // ============================
            // TEST 2 : STORAGE SERVICE
            // ============================

            System.out.println("\n===== TEST STORAGE SERVICE =====");

            int storagePort = getServicePort("StorageService", 50052);
            storageChannel = createChannel(storagePort);

            StorageServiceGrpc.StorageServiceBlockingStub storageStub =
                    StorageServiceGrpc.newBlockingStub(storageChannel);

            MeshProto.StoreRequest storeRequest = MeshProto.StoreRequest.newBuilder()
                    .setKey("nom")
                    .setValue("AutonomicMesh")
                    .build();

            MeshProto.StoreResponse storeResponse =
                    storageStub.store(storeRequest);

            System.out.println("Enregistrement réussi : "
                    + storeResponse.getStored());

            MeshProto.RetrieveRequest retrieveRequest =
                    MeshProto.RetrieveRequest.newBuilder()
                            .setKey("nom")
                            .build();

            MeshProto.RetrieveResponse retrieveResponse =
                    storageStub.retrieve(retrieveRequest);

            System.out.println("Donnée retrouvée : "
                    + retrieveResponse.getValue());

            // ============================
            // TEST 3 : NOTIF SERVICE
            // ============================

            System.out.println("\n===== TEST NOTIF SERVICE =====");

            int notifPort = getServicePort("NotifService", 50053);
            notifChannel = createChannel(notifPort);

            NotifServiceGrpc.NotifServiceBlockingStub notifStub =
                    NotifServiceGrpc.newBlockingStub(notifChannel);

            MeshProto.NotifRequest notifRequest = MeshProto.NotifRequest.newBuilder()
                    .setEvent("TEST")
                    .setPayload("Le système fonctionne correctement")
                    .build();

            MeshProto.NotifResponse notifResponse =
                    notifStub.notify(notifRequest);

            System.out.println("Notification acceptée : "
                    + notifResponse.getAccepted());

            // ============================
            // TEST 4 : HEALTH CHECK
            // ============================

            System.out.println("\n===== TEST HEALTH CHECK =====");

            MeshProto.HealthReq healthReq =
                    MeshProto.HealthReq.newBuilder().build();

            MeshProto.HealthResp calcHealth =
                    calcStub.healthCheck(healthReq);

            MeshProto.HealthResp storageHealth =
                    storageStub.healthCheck(healthReq);

            MeshProto.HealthResp notifHealth =
                    notifStub.healthCheck(healthReq);

            System.out.println("CalcService : " + calcHealth.getStatus());
            System.out.println("StorageService : " + storageHealth.getStatus());
            System.out.println("NotifService : " + notifHealth.getStatus());

            System.out.println("\n===== FIN DES TESTS =====");

        } finally {

            if (calcChannel != null) {
                calcChannel.shutdown();
            }

            if (storageChannel != null) {
                storageChannel.shutdown();
            }

            if (notifChannel != null) {
                notifChannel.shutdown();
            }
        }
    }
}
