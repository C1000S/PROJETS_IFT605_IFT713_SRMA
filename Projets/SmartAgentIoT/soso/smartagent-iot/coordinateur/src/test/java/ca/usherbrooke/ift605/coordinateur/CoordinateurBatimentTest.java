package ca.usherbrooke.ift605.coordinateur;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoordinateurBatimentTest {

    private final FournisseurFactice fournisseur = new FournisseurFactice();
    private final List<String> journal = new ArrayList<>();
    private final Map<String, String> publicationsOpenHab = new HashMap<>();
    private CoordinateurBatiment coordinateur;

    private AgentFactice salleA;
    private AgentFactice salleB;
    private AgentFactice cuisine;

    @BeforeEach
    void preparer() {
        salleA = new AgentFactice(22.0);
        salleB = new AgentFactice(22.0);
        cuisine = new AgentFactice(22.0);
        fournisseur.agents.put("salleA", salleA);
        fournisseur.agents.put("salleB", salleB);
        fournisseur.agents.put("cuisine", cuisine);
        coordinateur = new CoordinateurBatiment(fournisseur, publicationsOpenHab::put, journal::add);
    }

    private String journalComplet() {
        return String.join("\n", journal);
    }

    @Test
    void calculerConsigne_estLaMoyenneArrondieAuDixieme() {
        assertEquals(23.0, CoordinateurBatiment.calculerConsigne(27.0, 19.0), 1e-9);
        assertEquals(23.5, CoordinateurBatiment.calculerConsigne(27.0, 20.0), 1e-9);
    }

    @Test
    void ecartSuperieurA4_envoieLaMoyenneALaPieceLaPlusChaude() {
        salleA.temperature = 27.0;
        salleB.temperature = 21.0; // écart 6 > 4 ; salleB-cuisine : 21 / 22 -> équilibré

        coordinateur.equilibrerTemperatures();

        assertEquals(List.of(24.0), salleA.consignesRecues);
        assertTrue(salleB.consignesRecues.isEmpty());
        assertTrue(cuisine.consignesRecues.isEmpty());
        // le journal contient la paire, les deux températures, la décision et la consigne
        assertTrue(journalComplet().contains("salleA-salleB"));
        assertTrue(journalComplet().contains("27.0"));
        assertTrue(journalComplet().contains("consigne 24.0 envoyée à salleA"));
        // et la décision est transmise à openHAB
        assertTrue(publicationsOpenHab.get(CoordinateurBatiment.ITEM_DERNIER_EQUILIBRAGE).contains("salleA"));
    }

    @Test
    void laPieceLaPlusFroideNeRecoitRien_memeSiElleEstPremiereDeLaPaire() {
        salleA.temperature = 18.0;
        salleB.temperature = 25.0; // salleB plus chaude ; salleB-cuisine : 25 / 22 -> équilibré

        coordinateur.equilibrerTemperatures();

        assertTrue(salleA.consignesRecues.isEmpty());
        assertEquals(List.of(21.5), salleB.consignesRecues);
    }

    @Test
    void ecartExactementEgalA4_neDeclenchePasDeCommande() {
        salleA.temperature = 22.0;
        salleB.temperature = 26.0; // écart = 4,0 : le seuil est strict (> 4)
        cuisine.temperature = 24.0;

        coordinateur.equilibrerTemperatures();

        assertTrue(salleA.consignesRecues.isEmpty());
        assertTrue(salleB.consignesRecues.isEmpty());
        assertTrue(cuisine.consignesRecues.isEmpty());
    }

    @Test
    void pannePourUneSalle_lesAutresPairesContinuent() {
        salleA.enPanne = true;   // RemoteException sur salleA
        salleB.temperature = 28.0;
        cuisine.temperature = 20.0; // écart 8 sur salleB-cuisine

        assertDoesNotThrow(() -> coordinateur.equilibrerTemperatures());

        assertEquals(List.of(24.0), salleB.consignesRecues);
        assertTrue(fournisseur.oublies.contains("salleA"), "le stub périmé doit être oublié");
        assertTrue(journalComplet().contains("RemoteException"));
        assertTrue(publicationsOpenHab.get(CoordinateurBatiment.ITEM_ETAT_AGENTS).contains("salleA=HORS_LIGNE"));
    }

    @Test
    void agentIntrouvableDansLeRegistre_neFaitPasPlanter() {
        fournisseur.agents.remove("cuisine");
        salleA.temperature = 27.0;
        salleB.temperature = 20.0;

        assertDoesNotThrow(() -> coordinateur.equilibrerTemperatures());

        assertEquals(List.of(23.5), salleA.consignesRecues); // la paire salleA-salleB est traitée
        assertTrue(publicationsOpenHab.get(CoordinateurBatiment.ITEM_ETAT_AGENTS).contains("cuisine=HORS_LIGNE"));
    }

    @Test
    void capteurEnTimeout_utiliseLaDerniereValeurConnueEtAvertit() {
        salleA.temperature = 27.0;
        salleA.timeout = true; // plus de messages depuis 30 s, mais dernière valeur = 27
        salleB.temperature = 20.0;

        coordinateur.equilibrerTemperatures();

        assertEquals(List.of(23.5), salleA.consignesRecues);
        assertTrue(journalComplet().contains("timeout"));
        assertTrue(publicationsOpenHab.get(CoordinateurBatiment.ITEM_ETAT_AGENTS).contains("salleA=TIMEOUT"));
    }

    @Test
    void agentSansAucuneMesure_estIgnoreSansCommande() {
        salleA.temperature = Double.NaN; // jamais de mesure reçue
        salleA.timeout = true;
        salleB.temperature = 20.0;

        coordinateur.equilibrerTemperatures();

        assertTrue(salleA.consignesRecues.isEmpty());
        assertTrue(salleB.consignesRecues.isEmpty());
    }

    @Test
    void retourALaNormale_apresLaPanne() {
        salleA.enPanne = true;
        coordinateur.equilibrerTemperatures();
        assertTrue(publicationsOpenHab.get(CoordinateurBatiment.ITEM_ETAT_AGENTS).contains("salleA=HORS_LIGNE"));

        salleA.enPanne = false; // l'agent est relancé
        coordinateur.equilibrerTemperatures();
        assertTrue(publicationsOpenHab.get(CoordinateurBatiment.ITEM_ETAT_AGENTS).contains("salleA=OK"));
    }
}
