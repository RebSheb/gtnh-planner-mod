package com.gtnhplanner.logistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A pipe hatch is an item bus or a fluid hatch; energy and the like are left out. */
class HatchKindTest {

    @Test
    void fluidInputIsAPipeHatch() {
        assertTrue(HatchKind.matches("MTEHatchInput", true, false));
        assertFalse(HatchKind.matches("MTEHatchInput", true, true));
        assertFalse(HatchKind.matches("MTEHatchInput", false, false));
    }

    @Test
    void itemOutputIsABus() {
        assertTrue(HatchKind.matches("MTEHatchOutputBus", false, true));
        assertFalse(HatchKind.matches("MTEHatchOutputBus", true, true));
    }

    @Test
    void energyAndBatteryAreNotPipes() {
        assertFalse(HatchKind.matches("MTEHatchEnergy", true, false));
        assertFalse(HatchKind.matches("MTEHatchInputBattery", true, false));
        assertFalse(HatchKind.matches("MTEHatchMaintenance", false, false));
    }

    @Test
    void tooltipNamesPickAPipeHatch() {
        assertEquals(1, HatchKind.kindsIn("GT5U.MBTT.InputHatch"));
        assertEquals(2, HatchKind.kindsIn("Output Hatch"));
        assertEquals(4, HatchKind.kindsIn("Input Bus"));
        assertEquals(0, HatchKind.kindsIn("Energy Hatch"));
        assertEquals(0, HatchKind.kindsIn("Heating Coil"));
        assertEquals(1 | 2 | 4, HatchKind.kindsIn("of type Input Hatch or Output Hatch or Input Bus"));
    }
}
