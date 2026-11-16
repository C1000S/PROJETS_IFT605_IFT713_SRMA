package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.common.MeshConfig;

/**
 * Paramètres du gestionnaire autonome, lus depuis la ligne de commande.
 *
 * @param modeManuel        vrai : intervention humaine simulée (aucune action automatique immédiate)
 * @param delaiOperateurS   délai de réaction de l'opérateur humain simulé (s)
 * @param seuilLatenceMs    seuil de DEGRADATION
 * @param seuilCpu          seuil de SURCHARGE
 * @param confirmations     nombre de mesures consécutives anormales exigées avant symptôme
 * @param maxRepliques      nombre maximal de répliques par service
 * @param periodeCycleS     période de la boucle MAPE-K
 * @param peremptionMs      âge au-delà duquel une mesure est périmée
 * @param lancerServices    vrai : le gestionnaire démarre lui-même les services
 * @param lancerSondes      vrai : le gestionnaire démarre le processus des sondes
 */
public record Configuration(boolean modeManuel, int delaiOperateurS, long seuilLatenceMs, double seuilCpu,
                            int confirmations, int maxRepliques, long periodeCycleS, long peremptionMs,
                            boolean lancerServices, boolean lancerSondes) {

    /**
     * Lit les options. Exemple :
     * {@code --mode manuel --delai-operateur 60 --confirmations 2 --seuil-latence 500 --seuil-cpu 0.9}
     *
     * @param args arguments de la ligne de commande
     * @return configuration
     * @throws IllegalArgumentException si une option est inconnue ou mal formée
     */
    public static Configuration depuisArguments(String[] args) {
        boolean manuel = false;
        int delai = 60;
        long latence = MeshConfig.SEUIL_LATENCE_MS;
        double cpu = MeshConfig.SEUIL_CPU;
        int confirmations = 1;
        int maxRepliques = 1;
        long cycle = MeshConfig.PERIODE_MAPEK_S;
        boolean services = true;
        boolean sondes = true;
        for (int i = 0; i < args.length; i++) {
            String opt = args[i];
            switch (opt) {
                case "--mode" -> {
                    String v = valeur(args, ++i, opt);
                    if (!v.equals("auto") && !v.equals("manuel")) {
                        throw new IllegalArgumentException("--mode attend auto ou manuel");
                    }
                    manuel = v.equals("manuel");
                }
                case "--delai-operateur" -> delai = Integer.parseInt(valeur(args, ++i, opt));
                case "--seuil-latence" -> latence = Long.parseLong(valeur(args, ++i, opt));
                case "--seuil-cpu" -> cpu = Double.parseDouble(valeur(args, ++i, opt));
                case "--confirmations" -> confirmations = Math.max(1, Integer.parseInt(valeur(args, ++i, opt)));
                case "--max-repliques" -> maxRepliques = Math.max(0, Integer.parseInt(valeur(args, ++i, opt)));
                case "--cycle" -> cycle = Math.max(1, Long.parseLong(valeur(args, ++i, opt)));
                case "--sans-services" -> services = false;
                case "--sans-sondes" -> sondes = false;
                default -> throw new IllegalArgumentException("Option inconnue : " + opt);
            }
        }
        return new Configuration(manuel, delai, latence, cpu, confirmations, maxRepliques, cycle,
                MeshConfig.PEREMPTION_MESURE_MS, services, sondes);
    }

    private static String valeur(String[] args, int i, String opt) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Valeur manquante pour " + opt);
        }
        return args[i];
    }

    @Override
    public String toString() {
        return String.format("mode=%s%s, seuils(latence>%dms, cpu>%.2f), confirmations=%d, maxRepliques=%d, cycle=%ds",
                modeManuel ? "MANUEL" : "AUTO", modeManuel ? " (opérateur " + delaiOperateurS + "s)" : "",
                seuilLatenceMs, seuilCpu, confirmations, maxRepliques, periodeCycleS);
    }
}
