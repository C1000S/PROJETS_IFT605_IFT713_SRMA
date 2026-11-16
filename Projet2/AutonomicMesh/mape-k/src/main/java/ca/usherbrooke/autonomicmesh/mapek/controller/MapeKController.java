package ca.usherbrooke.autonomicmesh.mapek.controller;

import ca.usherbrooke.autonomicmesh.mapek.knowledge.KnowledgeBase;
import ca.usherbrooke.autonomicmesh.mapek.knowledge.Measurement;
import ca.usherbrooke.autonomicmesh.mapek.analyzer.Analyzer;
import ca.usherbrooke.autonomicmesh.mapek.planner.Planner;
import ca.usherbrooke.autonomicmesh.mapek.executor.Executor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MapeKController {

    private static final long RESTART_RETRY_INTERVAL_MS = 15_000;

    private final KnowledgeBase knowledgeBase;
    private final Analyzer analyzer;
    private final Planner planner;
    private final Executor executor;

    private final Map<String, String> lastActions =
            new ConcurrentHashMap<>();

    private final Map<String, Long> lastExecutionTimes =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    public MapeKController(
            KnowledgeBase knowledgeBase,
            Analyzer analyzer,
            Planner planner,
            Executor executor) {

        this.knowledgeBase = knowledgeBase;
        this.analyzer = analyzer;
        this.planner = planner;
        this.executor = executor;
    }

    public void start() {
        System.out.println("[MAPE-K] Cycle automatique démarré.");

        scheduler.scheduleAtFixedRate(
                this::runCycle,
                10,
                10,
                TimeUnit.SECONDS
        );
    }

    private void runCycle() {

        System.out.println("\n========== CYCLE MAPE-K ==========");

        for (Measurement measurement :
                knowledgeBase.getAllStates().values()) {

            try {
                String service = measurement.service();
                String status = measurement.status();

                System.out.println(
                        "[MAPE-K] Analyse du service : " + service
                        + " | État reçu : " + status
                );

                // Réinitialiser l'historique après récupération.
                if (!"DOWN".equalsIgnoreCase(status)) {
                    String previousAction = lastActions.remove(service);
                    lastExecutionTimes.remove(service);

                    if ("RESTART_SERVICE".equals(previousAction)) {
                        System.out.println(
                                "[MAPE-K] " + service
                                + " est de nouveau disponible. "
                                + "Historique de redémarrage réinitialisé."
                        );
                    }
                }

                // ANALYSE
                var problems = analyzer.analyze(measurement);

                // PLANIFICATION
                String action = planner.plan(problems);

                System.out.println(
                        "[MAPE-K] Action planifiée pour "
                                + service + " : " + action
                );

                String previousAction = lastActions.get(service);
                long now = System.currentTimeMillis();

                Long lastExecution = lastExecutionTimes.get(service);

                boolean actionChanged = !action.equals(previousAction);

                boolean firstRestartAttempt =
                        "RESTART_SERVICE".equals(action)
                        && lastExecution == null;

                boolean restartRetryDue =
                        "RESTART_SERVICE".equals(action)
                        && lastExecution != null
                        && now - lastExecution >= RESTART_RETRY_INTERVAL_MS;

                boolean shouldExecute =
                        actionChanged
                        || firstRestartAttempt
                        || restartRetryDue;

                // EXÉCUTION
                if (shouldExecute) {

                    if (restartRetryDue && !actionChanged) {
                        System.out.println(
                                "[MAPE-K] Nouvelle tentative de redémarrage "
                                        + "pour " + service
                        );
                    } else {
                        System.out.println(
                                "[MAPE-K] Déclenchement de l'Executor..."
                        );
                    }

                    executor.execute(service, action);

                    lastActions.put(service, action);
                    lastExecutionTimes.put(service, now);

                    System.out.println(
                            "[MAPE-K] Exécution terminée pour " + service
                    );

                } else {
                    System.out.println(
                            "[MAPE-K] Action inchangée, aucune nouvelle "
                                    + "exécution pour " + service
                    );
                }

            } catch (Exception e) {

                System.err.println(
                        "[MAPE-K] Erreur pendant le traitement de "
                                + measurement.service() + " : "
                                + e.getMessage()
                );

                e.printStackTrace();
            }
        }

        System.out.println("======== FIN DU CYCLE MAPE-K ========\n");
    }

    public void stop() {
        scheduler.shutdownNow();
        System.out.println("[MAPE-K] Contrôleur arrêté.");
    }
}
