package ca.usherbrooke.autonomicmesh.mapek.analyzer;

import ca.usherbrooke.autonomicmesh.mapek.knowledge.Measurement;
import java.util.ArrayList;
import java.util.List;

public class Analyzer {

    public List<String> analyze(Measurement measurement) {

        List<String> problems = new ArrayList<>();

        if ("DOWN".equalsIgnoreCase(measurement.status())) {
            problems.add("PANNE_TOTALE");

            System.out.println("[Analyzer] "
                    + measurement.service() + " : PANNE_TOTALE");

            return problems;
        }

        if (measurement.latencyMs() > 500) {
            problems.add("DEGRADATION");
        }

        if (measurement.cpuLoad() > 0.90) {
            problems.add("SURCHARGE");
        }

        if (!problems.isEmpty()) {
            System.out.println("[Analyzer] "
                    + measurement.service() + " : " + problems);
        }

        return problems;
    }
}
