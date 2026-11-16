package ca.usherbrooke.ift605.mapek.model;

/**
 * Action corrective planifiée par le Planner.
 *
 * @param type       nature de l'action
 * @param service    service logique concerné
 * @param instanceId instance à l'origine de l'action (celle qui présente le symptôme)
 * @param cible      instance visée par l'action (instance à relancer, secours vers lequel router...)
 * @param raison     symptôme ayant motivé l'action (null pour RETABLIR_ROUTAGE)
 */
public record ActionCorrective(TypeAction type, String service, String instanceId, String cible,
                               TypeSymptome raison) {

    @Override
    public String toString() {
        return type + " " + service + " (" + instanceId + (cible.equals(instanceId) ? "" : " → " + cible) + ")";
    }
}
