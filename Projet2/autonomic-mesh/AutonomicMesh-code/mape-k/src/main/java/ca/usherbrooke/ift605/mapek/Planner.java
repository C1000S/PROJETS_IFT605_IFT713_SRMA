package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mapek.model.ActionCorrective;
import ca.usherbrooke.ift605.mapek.model.InstanceInfo;
import ca.usherbrooke.ift605.mapek.model.MesureSante;
import ca.usherbrooke.ift605.mapek.model.Role;
import ca.usherbrooke.ift605.mapek.model.Symptome;
import ca.usherbrooke.ift605.mapek.model.TypeAction;
import ca.usherbrooke.ift605.mesh.common.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * P de MAPE-K : transforme les symptômes en actions correctives, sans les exécuter.
 * <p>Politiques :
 * <ul>
 *   <li>PANNE_TOTALE → REACHEMINEMENT vers le secours (si l'instance reçoit le trafic
 *       et que le secours est sain), puis REDEMARRER l'instance ;</li>
 *   <li>DEGRADATION → REACHEMINEMENT vers le secours si l'instance reçoit le trafic ;</li>
 *   <li>SURCHARGE → MISE_A_ECHELLE (une réplique de plus, dans la limite configurée) ;</li>
 *   <li>primaire de nouveau sain alors que le trafic est sur le secours → RETABLIR_ROUTAGE.</li>
 * </ul>
 * Les actions encore en refroidissement dans la KB ne sont pas replanifiées.
 */
public class Planner {

    /** Nombre de mesures saines consécutives exigées avant de rétablir le routage. */
    static final int MESURES_SAINES_POUR_RETOUR = 2;

    private final KnowledgeBase kb;
    private final Configuration conf;

    /**
     * @param kb   base de connaissances (lecture seule ici)
     * @param conf configuration (seuils, limite de répliques)
     */
    public Planner(KnowledgeBase kb, Configuration conf) {
        this.kb = kb;
        this.conf = conf;
    }

    /**
     * @param symptomes symptômes produits par l'Analyzer
     * @return actions à exécuter, dans l'ordre
     */
    public List<ActionCorrective> planifier(List<Symptome> symptomes) {
        List<ActionCorrective> actions = new ArrayList<>();
        Set<String> enDefaut = new HashSet<>();
        for (Symptome s : symptomes) {
            enDefaut.add(s.instanceId());
            switch (s.type()) {
                case PANNE_TOTALE -> {
                    reacheminerSiNecessaire(s).ifPresent(actions::add);
                    if (!kb.actionEnCours(s.instanceId())) {
                        actions.add(new ActionCorrective(TypeAction.REDEMARRER, s.service(), s.instanceId(),
                                s.instanceId(), s.type()));
                    }
                }
                case DEGRADATION -> reacheminerSiNecessaire(s).ifPresentOrElse(actions::add,
                        () -> Log.info("PLAN", s.instanceId() + " dégradé : trafic déjà ailleurs ou aucun secours sain"));
                case SURCHARGE -> {
                    String cle = "scale:" + s.service();
                    int nb = kb.instances(s.service(), Role.REPLIQUE).size();
                    if (!kb.actionEnCours(cle) && nb < conf.maxRepliques()) {
                        actions.add(new ActionCorrective(TypeAction.MISE_A_ECHELLE, s.service(), s.instanceId(),
                                s.service() + "-" + "nouvelle", s.type()));
                    } else if (nb >= conf.maxRepliques()) {
                        Log.info("PLAN", s.service() + " surchargé mais limite de répliques atteinte (" + nb + ")");
                    }
                }
            }
        }
        actions.addAll(retoursDeRoutage(enDefaut));
        return actions;
    }

    /** REACHEMINEMENT si l'instance en défaut reçoit le trafic et que le secours est sain. */
    private Optional<ActionCorrective> reacheminerSiNecessaire(Symptome s) {
        if (!s.instanceId().equals(kb.routage(s.service()))) {
            return Optional.empty();
        }
        Optional<InstanceInfo> secours = kb.secours(s.service());
        if (secours.isEmpty() || secours.get().instanceId().equals(s.instanceId())) {
            return Optional.empty();
        }
        MesureSante m = kb.derniereMesure(secours.get().instanceId());
        boolean secoursSain = m != null && kb.estUtilisable(m.instanceId, conf.peremptionMs())
                && m.estSaine(conf.seuilLatenceMs(), conf.seuilCpu());
        if (!secoursSain) {
            Log.info("PLAN", "Secours " + secours.get().instanceId() + " pas sain : réacheminement impossible");
            return Optional.empty();
        }
        return Optional.of(new ActionCorrective(TypeAction.REACHEMINEMENT, s.service(), s.instanceId(),
                secours.get().instanceId(), s.type()));
    }

    /** RETABLIR_ROUTAGE pour chaque service routé hors de son primaire redevenu sain. */
    private List<ActionCorrective> retoursDeRoutage(Set<String> enDefaut) {
        List<ActionCorrective> retours = new ArrayList<>();
        for (InstanceInfo prim : kb.instances().stream().filter(i -> i.role() == Role.PRIMAIRE).toList()) {
            String cible = kb.routage(prim.service());
            if (cible == null || cible.equals(prim.instanceId()) || enDefaut.contains(prim.instanceId())) {
                continue;
            }
            List<MesureSante> recentes = kb.dernieresMesures(prim.instanceId(), MESURES_SAINES_POUR_RETOUR);
            boolean stable = recentes.size() == MESURES_SAINES_POUR_RETOUR
                    && recentes.stream().allMatch(m -> m.estSaine(conf.seuilLatenceMs(), conf.seuilCpu()))
                    && kb.estUtilisable(prim.instanceId(), conf.peremptionMs());
            if (stable) {
                retours.add(new ActionCorrective(TypeAction.RETABLIR_ROUTAGE, prim.service(), cible,
                        prim.instanceId(), null));
            }
        }
        return retours;
    }

    /**
     * Version « opérateur humain » : un seul geste possible, redémarrer l'instance en défaut.
     *
     * @param symptomes symptômes détectés
     * @return une action REDEMARRER par instance en défaut non déjà prise en charge
     */
    public List<ActionCorrective> planifierManuel(List<Symptome> symptomes) {
        List<ActionCorrective> actions = new ArrayList<>();
        for (Symptome s : symptomes) {
            if (!kb.actionEnCours(s.instanceId())) {
                actions.add(new ActionCorrective(TypeAction.REDEMARRER, s.service(), s.instanceId(),
                        s.instanceId(), s.type()));
            }
        }
        return actions;
    }
}
