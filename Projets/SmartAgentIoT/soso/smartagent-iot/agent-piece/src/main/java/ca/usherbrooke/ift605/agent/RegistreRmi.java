package ca.usherbrooke.ift605.agent;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/**
 * Lance uniquement le registre RMI (port 2199) dans sa propre JVM.
 * <p>
 * Pourquoi un processus dédié ? Le registre vit dans la JVM qui le crée. S'il était hébergé par le
 * premier agent, arrêter cet agent (panne simulée en démo) ferait disparaître le registre pour tout
 * le monde. Avec un processus séparé, on peut arrêter n'importe quel agent sans effet de bord.
 */
public final class RegistreRmi {

    private static Registry registre; // référence forte

    private RegistreRmi() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.rmi.server.hostname", Constantes.HOTE_RMI);
        registre = LocateRegistry.createRegistry(Constantes.PORT_RMI);
        System.out.println("Registre RMI démarré sur le port " + Constantes.PORT_RMI);
        Thread.currentThread().join(); // reste en vie jusqu'à Ctrl+C
    }
}
