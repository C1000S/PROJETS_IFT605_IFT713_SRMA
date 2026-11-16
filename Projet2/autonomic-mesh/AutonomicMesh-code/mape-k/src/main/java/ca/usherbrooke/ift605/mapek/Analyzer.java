package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mapek.model.InstanceInfo;
import ca.usherbrooke.ift605.mapek.model.MesureSante;
import ca.usherbrooke.ift605.mapek.model.Symptome;
import ca.usherbrooke.ift605.mapek.model.TypeSymptome;

import java.util.ArrayList;
import java.util.List;

/**
 * A de MAPE-K : transforme l'état de la KnowledgeBase en symptômes.
 * <ul>
 *   <li>PANNE_TOTALE : statut DOWN, ou aucune mesure récente (instance injoignable) ;</li>
 *   <li>DEGRADATION : latence &gt; seuil (500 ms par défaut) ;</li>
 *   <li>SURCHARGE : cpuLoad &gt; seuil (0,90 par défaut).</li>
 * </ul>
 * Avec {@code confirmations = N > 1}, un symptôme n'est émis que si les N dernières mesures
 * sont toutes anormales du même type : cela réduit les faux positifs sur pics transitoires,
 * au prix d'une détection plus lente (compromis à discuter dans le rapport).
 */
public class Analyzer {

    private final KnowledgeBase kb;
    private final Configuration conf;

    /**
     * @param kb   base de connaissances
     * @param conf seuils et nombre de confirmations
     */
    public Analyzer(KnowledgeBase kb, Configuration conf) {
        this.kb = kb;
        this.conf = conf;
    }

    /**
     * Classe une mesure selon les règles de l'énoncé (ordre de priorité DOWN, latence, CPU).
     *
     * @param m mesure
     * @return symptôme correspondant, ou null si la mesure est normale
     */
    TypeSymptome classer(MesureSante m) {
        if ("DOWN".equals(m.statut)) return TypeSymptome.PANNE_TOTALE;
        if (m.latenceMs > conf.seuilLatenceMs()) return TypeSymptome.DEGRADATION;
        if (m.cpuLoad > conf.seuilCpu()) return TypeSymptome.SURCHARGE;
        return null;
    }

    /** @return liste des symptômes détectés sur toutes les instances connues */
    public List<Symptome> analyser() {
        List<Symptome> symptomes = new ArrayList<>();
        long maintenant = System.currentTimeMillis();
        for (InstanceInfo inst : kb.instances()) {
            MesureSante m = kb.derniereMesure(inst.instanceId());
            if (m == null) {
                // Aucune mesure : panne seulement si le délai de grâce au démarrage est écoulé
                if (kb.ageEnregistrementMs(inst.instanceId()) > conf.peremptionMs()) {
                    symptomes.add(new Symptome(inst.instanceId(), inst.service(), TypeSymptome.PANNE_TOTALE, null));
                }
                continue;
            }
            if (maintenant - m.timestamp > conf.peremptionMs()) {
                symptomes.add(new Symptome(inst.instanceId(), inst.service(), TypeSymptome.PANNE_TOTALE, m));
                continue;
            }
            TypeSymptome type = classer(m);
            if (type != null && confirme(inst.instanceId(), type)) {
                symptomes.add(new Symptome(inst.instanceId(), inst.service(), type, m));
            }
        }
        return symptomes;
    }

    /** Vérifie que les N dernières mesures présentent toutes le même symptôme. */
    private boolean confirme(String instanceId, TypeSymptome type) {
        if (conf.confirmations() <= 1) {
            return true;
        }
        List<MesureSante> recentes = kb.dernieresMesures(instanceId, conf.confirmations());
        return recentes.size() >= conf.confirmations()
                && recentes.stream().allMatch(m -> classer(m) == type);
    }
}
