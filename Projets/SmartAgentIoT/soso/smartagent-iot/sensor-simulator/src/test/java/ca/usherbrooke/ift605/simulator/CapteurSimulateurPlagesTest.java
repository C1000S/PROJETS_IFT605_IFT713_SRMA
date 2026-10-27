package ca.usherbrooke.ift605.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Les plages documentées (15–30 °C) doivent être respectées quoi qu'on force. */
class CapteurSimulateurPlagesTest {

    @Test
    void laTemperatureEstBorneeAuxPlagesDocumentees() {
        assertEquals(15.0, CapteurSimulateur.borner(3.0), 1e-9);
        assertEquals(30.0, CapteurSimulateur.borner(99.0), 1e-9);
        assertEquals(22.5, CapteurSimulateur.borner(22.5), 1e-9);
    }
}
