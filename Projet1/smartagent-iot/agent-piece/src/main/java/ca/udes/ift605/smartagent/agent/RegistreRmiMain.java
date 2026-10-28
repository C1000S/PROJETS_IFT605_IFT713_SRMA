package ca.udes.ift605.smartagent.agent;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/**
 * Lance UNIQUEMENT le registre RMI (port 2199) dans son propre processus JVM.
 *
 * Pourquoi un processus dédié ? Le registre vit dans la JVM qui le crée.
 * {@link AgentPieceMain} sait déjà « créer sinon se connecter » au registre,
 * ce qui suffit pour un démarrage simple ; mais cela signifie que c'est le
 * PREMIER agent lancé qui héberge le registre. Si, pendant la démonstration de
 * tolérance aux pannes (Tâche 3.1), on arrête justement cet agent-là, le
 * registre disparaît pour tout le monde (les autres agents restent joignables
 * par leur stub déjà résolu, mais plus aucun nouveau lookup n'est possible).
 *
 * En hébergeant le registre dans ce processus séparé, on peut arrêter
 * n'importe quel agent sans effet de bord sur l'annuaire : c'est le montage
 * utilisé par {@code scripts/start-all.sh}. {@link AgentPieceMain} reste
 * compatible : s'il trouve le registre déjà actif, il s'y connecte au lieu de
 * le créer.
 *
 * Usage : java -cp agent-piece.jar ca.udes.ift605.smartagent.agent.RegistreRmiMain
 */
public final class RegistreRmiMain {

    private static final int PORT_REGISTRE = 2199;

    // Référence forte conservée pour empêcher le ramasse-miettes de collecter le
    // registre (et donc de le fermer) tant que ce processus vit.
    private static Registry registre;

    private RegistreRmiMain() {
    }

    public static void main(String[] args) throws Exception {
        registre = LocateRegistry.createRegistry(PORT_REGISTRE);
        System.out.println("Registre RMI démarré sur le port " + PORT_REGISTRE + " (Ctrl+C pour arrêter)");
        // On bloque le thread principal : le registre reste disponible tant que
        // ce processus n'est pas arrêté.
        Thread.currentThread().join();
    }
}
