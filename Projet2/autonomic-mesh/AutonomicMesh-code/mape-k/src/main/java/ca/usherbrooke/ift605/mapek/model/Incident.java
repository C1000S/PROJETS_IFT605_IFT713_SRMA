package ca.usherbrooke.ift605.mapek.model;

/**
 * Incident ouvert par le gestionnaire lorsqu'un symptôme apparaît, fermé lorsque le
 * service est de nouveau rendu. Sert à mesurer le délai de détection, le MTTR et le
 * nombre de cycles MAPE-K avant récupération.
 * <p>
 * Les champs modifiés après création sont volatils : ils sont écrits par l'Executor
 * (éventuellement depuis un autre thread) et lus par la boucle principale.
 */
public final class Incident {

    private final String instanceId;
    private final String service;
    private final TypeSymptome symptome;
    private final long tDetectionMs;
    private final long cycleDetection;
    private volatile TypeAction action;
    private volatile long tActionMs;

    /**
     * @param instanceId     instance en défaut
     * @param service        service logique
     * @param symptome       symptôme détecté
     * @param tDetectionMs   instant de détection (epoch ms)
     * @param cycleDetection numéro du cycle MAPE-K de détection
     */
    public Incident(String instanceId, String service, TypeSymptome symptome, long tDetectionMs, long cycleDetection) {
        this.instanceId = instanceId;
        this.service = service;
        this.symptome = symptome;
        this.tDetectionMs = tDetectionMs;
        this.cycleDetection = cycleDetection;
    }

    /**
     * Enregistre la première action corrective appliquée pour cet incident.
     *
     * @param type     action réalisée
     * @param instantMs instant de l'action (epoch ms)
     */
    public void enregistrerAction(TypeAction type, long instantMs) {
        if (action == null) {
            action = type;
            tActionMs = instantMs;
        }
    }

    /** @return instance en défaut */
    public String instanceId() { return instanceId; }

    /** @return service logique */
    public String service() { return service; }

    /** @return symptôme détecté */
    public TypeSymptome symptome() { return symptome; }

    /** @return instant de détection */
    public long tDetectionMs() { return tDetectionMs; }

    /** @return cycle de détection */
    public long cycleDetection() { return cycleDetection; }

    /** @return première action réalisée, ou null */
    public TypeAction action() { return action; }

    /** @return instant de la première action, ou 0 */
    public long tActionMs() { return tActionMs; }
}
