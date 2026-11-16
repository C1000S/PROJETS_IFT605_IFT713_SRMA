package ca.usherbrooke.autonomicmesh.mapek.planner;

import java.util.List;

public class Planner {

    public String plan(List<String> problems) {

        if (problems == null || problems.isEmpty()) {
            return "NO_ACTION";
        }

        // Une panne totale nécessite un redémarrage
        if (problems.contains("PANNE_TOTALE")) {
            return "RESTART_SERVICE";
        }

        // Une surcharge nécessite un réacheminement
        if (problems.contains("SURCHARGE")) {
            return "REROUTE_TRAFFIC";
        }

        // Une dégradation nécessite également un réacheminement
        if (problems.contains("DEGRADATION")) {
            return "REROUTE_TRAFFIC";
        }

        return "NO_ACTION";
    }
}
