package ca.usherbrooke.ift605.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Tests de l'état d'une pièce : aucun réseau nécessaire, l'horloge est simulée. */
class EtatPieceTest {

    private final AtomicLong maintenant = new AtomicLong(1_000);
    private final EtatPiece etat = new EtatPiece(maintenant::get, 30_000);

    @Test
    void appliqueLesTroisTypesDeMesures() {
        assertTrue(etat.appliquer("temperature", "22.4"));
        assertTrue(etat.appliquer("luminosite", "340"));
        assertTrue(etat.appliquer("occupation", "true"));

        assertEquals(22.4, etat.getTemperature(), 1e-9);
        assertEquals(340, etat.getLuminosite());
        assertTrue(etat.isOccupee());
    }

    @Test
    void rejetteLesValeursInvalidesEtGardeLAncienneValeur() {
        etat.appliquer("temperature", "21.0");

        assertFalse(etat.appliquer("temperature", "abc"));
        assertFalse(etat.appliquer("occupation", "peut-etre"));
        assertFalse(etat.appliquer("inconnu", "1"));

        assertEquals(21.0, etat.getTemperature(), 1e-9);
    }

    @Test
    void sansAucunMessageLaPieceEstEnTimeout() {
        assertTrue(etat.enTimeout());
        assertTrue(Double.isNaN(etat.getTemperature()));
    }

    @Test
    void timeoutApresPlusDe30Secondes() {
        etat.appliquer("temperature", "20.0");
        assertFalse(etat.enTimeout());

        maintenant.addAndGet(30_000); // exactement 30 s : pas encore en timeout
        assertFalse(etat.enTimeout());

        maintenant.addAndGet(1); // 30,001 s
        assertTrue(etat.enTimeout());

        etat.appliquer("temperature", "20.5"); // un nouveau message remet à zéro
        assertFalse(etat.enTimeout());
    }

    @Test
    void jsonContientToutesLesInformations() {
        etat.appliquer("temperature", "19.8");
        etat.appliquer("luminosite", "120");
        etat.appliquer("occupation", "false");

        assertEquals(
            "{\"piece\":\"salleB\",\"temperature\":19.8,\"luminosite\":120,\"occupee\":false,\"timeout\":false}",
            etat.toJson("salleB"));
    }
}
