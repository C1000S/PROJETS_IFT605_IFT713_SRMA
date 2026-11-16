
package ca.usherbrooke.autonomicmesh.mapek.executor;

import ca.usherbrooke.autonomicmesh.mapek.monitor.Probe;
import ca.usherbrooke.autonomicmesh.mapek.process.ServiceProcessManager;
import ca.usherbrooke.autonomicmesh.mapek.knowledge.KnowledgeBase;

import java.util.Map;

public class Executor {

    private final Map<String, Probe> probes;
    private final ServiceProcessManager processManager;
    private final KnowledgeBase knowledgeBase;

    public Executor(
            Map<String, Probe> probes,
            ServiceProcessManager processManager,
            KnowledgeBase knowledgeBase) {

        this.probes = probes;
        this.processManager = processManager;
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * Exécute l'action corrective choisie par le Planner.
     *
     * @param service Nom du microservice concerné
     * @param action Action corrective à exécuter
     */
    public void execute(String service, String action) {

        System.out.println(
                "[Executor] Action " + action + " pour " + service
        );

        switch (action) {

            case "ALERT" -> {
                System.err.println(
                        "[ALERTE] Dégradation détectée sur " + service
                );
            }

            case "REDUCE_LOAD" -> {
                Probe probe = probes.get(service);

                if (probe != null) {
                    probe.setIntervalSeconds(10);

                    System.out.println(
                            "[Executor] Fréquence de surveillance réduite "
                                    + "pour " + service
                    );
                } else {
                    System.err.println(
                            "[Executor] Aucune sonde trouvée pour " + service
                    );
                }
            }

            // Réacheminer le trafic vers le service de secours
            case "REROUTE_TRAFFIC" -> {

                System.out.println(
                        "[Executor] Demande de réacheminement pour "
                                + service
                );

                boolean success = knowledgeBase.reroute(service);

                if (success) {
                    System.out.println(
                            "[Executor] Réacheminement effectué. "
                                    + "Nouvelle destination : "
                                    + knowledgeBase.getRoute(service)
                    );
                } else {
                    System.err.println(
                            "[Executor] Réacheminement impossible pour "
                                    + service
                    );
                }
            }

            // Redémarrer le microservice en panne
            case "RESTART_SERVICE" -> {

                System.out.println(
                        "[Executor] Demande de redémarrage pour " + service
                );

                try {
                    processManager.restartService(service);

                    System.out.println(
                            "[Executor] Demande transmise au gestionnaire "
                                    + "de processus pour " + service
                    );

                } catch (Exception e) {
                    System.err.println(
                            "[Executor] Échec du redémarrage de "
                                    + service + " : " + e.getMessage()
                    );

                    e.printStackTrace();
                }
            }

            case "NO_ACTION" -> {
                System.out.println(
                        "[Executor] Aucun problème détecté sur " + service
                );
            }

            default -> {
                System.err.println(
                        "[Executor] Action inconnue : " + action
                );
            }
        }
    }
}
