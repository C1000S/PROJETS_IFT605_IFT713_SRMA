package ca.usherbrooke.autonomicmesh.mapek.knowledge;

public record Measurement(
        String service,
        long timestamp,
        String status,
        long latencyMs,
        double cpuLoad,
        long uptimeMs
) {}
