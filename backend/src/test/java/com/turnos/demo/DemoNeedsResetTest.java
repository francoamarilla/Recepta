package com.turnos.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// The startup decision alone: regenerate when the demo is missing or a day old.
class DemoNeedsResetTest {

    private static final Instant NOW = Instant.parse("2026-11-24T20:00:00Z");
    private static final Duration DAY = Duration.ofHours(24);

    @Test
    void missingDemoIsSeeded() {
        assertTrue(DemoDataService.needsReset(Optional.empty(), NOW, DAY));
    }

    @Test
    void dataUnderADayOldIsKept() {
        assertFalse(DemoDataService.needsReset(Optional.of(NOW.minus(Duration.ofHours(23))), NOW, DAY));
        assertFalse(DemoDataService.needsReset(Optional.of(NOW.minusSeconds(1)), NOW, DAY));
    }

    @Test
    void dataADayOldOrMoreIsRegenerated() {
        assertTrue(DemoDataService.needsReset(Optional.of(NOW.minus(DAY)), NOW, DAY));
        assertTrue(DemoDataService.needsReset(Optional.of(NOW.minus(Duration.ofHours(25))), NOW, DAY));
    }

    @Test
    void theSeederUsesADay() {
        assertTrue(DemoDataSeeder.MAX_AGE.equals(DAY));
    }
}
