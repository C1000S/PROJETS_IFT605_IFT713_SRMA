package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.FaultRequest;
import ca.usherbrooke.ift605.mesh.proto.FaultType;

/**
 * État des fautes injectées dans une instance de service (thread-safe).
 * <p>
 * Une faute peut être temporaire ({@code durationSec > 0}) ou permanente jusqu'à
 * {@code CLEAR} ou au redémarrage du processus. Les trois effets sont cumulables :
 * latence ajoutée, charge CPU rapportée, indisponibilité.
 */
public final class FaultState {

    private long latenceAjouteeMs;
    private double cpuForce = -1;      // < 0 : pas de surcharge simulée
    private boolean indisponible;
    private long expirationMs;         // 0 : pas d'expiration

    /**
     * Applique une requête d'injection de faute.
     *
     * @param req requête reçue par ChaosControl
     * @return description lisible de l'état résultant
     */
    public synchronized String appliquer(FaultRequest req) {
        FaultType type = req.getType();
        switch (type) {
            case LATENCY -> latenceAjouteeMs = Math.max(0, req.getLatencyMs());
            case CPU_OVERLOAD -> cpuForce = Math.min(1.0, Math.max(0.0, req.getCpuLoad()));
            case UNAVAILABLE -> indisponible = true;
            case CLEAR -> effacer();
            default -> {
                return "type de faute ignoré : " + type;
            }
        }
        if (type != FaultType.CLEAR) {
            expirationMs = req.getDurationSec() > 0
                    ? System.currentTimeMillis() + req.getDurationSec() * 1000L
                    : 0;
        }
        return decrire();
    }

    private void effacer() {
        latenceAjouteeMs = 0;
        cpuForce = -1;
        indisponible = false;
        expirationMs = 0;
    }

    private void expirerSiNecessaire() {
        if (expirationMs > 0 && System.currentTimeMillis() >= expirationMs) {
            effacer();
        }
    }

    /** @return latence artificielle à ajouter à chaque appel (ms) */
    public synchronized long latenceAjouteeMs() {
        expirerSiNecessaire();
        return latenceAjouteeMs;
    }

    /** @return charge CPU forcée, ou une valeur négative si aucune surcharge n'est simulée */
    public synchronized double cpuForce() {
        expirerSiNecessaire();
        return cpuForce;
    }

    /** @return vrai si le service doit refuser de servir */
    public synchronized boolean indisponible() {
        expirerSiNecessaire();
        return indisponible;
    }

    /** @return résumé de l'état courant */
    public synchronized String decrire() {
        expirerSiNecessaire();
        return String.format("latence+%dms, cpu=%s, indisponible=%s%s", latenceAjouteeMs,
                cpuForce < 0 ? "réel" : String.format("%.2f", cpuForce), indisponible,
                expirationMs > 0 ? ", expire dans " + (expirationMs - System.currentTimeMillis()) / 1000 + "s" : "");
    }

    /**
     * Applique la latence artificielle courante en bloquant le thread appelant.
     */
    public void attendreLatenceInjectee() {
        long delai = latenceAjouteeMs();
        if (delai > 0) {
            try {
                Thread.sleep(delai);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
