package ca.usherbrooke.ift605.mapek.model;

/**
 * Symptôme produit par l'Analyzer.
 *
 * @param instanceId instance concernée
 * @param service    service logique de l'instance
 * @param type       nature du symptôme
 * @param preuve     mesure qui a déclenché le symptôme (peut être null si mesure périmée)
 */
public record Symptome(String instanceId, String service, TypeSymptome type, MesureSante preuve) {

    @Override
    public String toString() {
        return instanceId + " " + type + (preuve == null ? " (aucune mesure récente)" : " [" + preuve + "]");
    }
}
