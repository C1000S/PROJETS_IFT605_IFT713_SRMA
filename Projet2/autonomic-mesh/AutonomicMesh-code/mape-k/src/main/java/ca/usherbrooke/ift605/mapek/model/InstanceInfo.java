package ca.usherbrooke.ift605.mapek.model;

/**
 * Description d'une instance gérée par le gestionnaire autonome.
 *
 * @param instanceId identifiant unique (calc-1, calc-backup, calc-2...)
 * @param service    nom logique du service
 * @param hote       hôte gRPC
 * @param port       port gRPC
 * @param role       rôle dans le maillage
 */
public record InstanceInfo(String instanceId, String service, String hote, int port, Role role) {
}
