package com.turnos.demo;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Regenerates the demo data at boot when it is missing or a day old, so the
// shared link always shows a barbershop whose past 60 days and next 14 are
// relative to today rather than to whenever it was last deployed. Younger
// data is kept: Render's free instance boots on every wake (the reseed took
// ~45 s of each cold start) and a deploy can land while a visitor is using it.
//
// A failure here must not take the service down: an empty demo is better
// than a demo that will not start, and /api/demo/reset can retry it.
@Component
@Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    // ponytail: fixed; make it a property if a demo ever needs another cadence.
    static final Duration MAX_AGE = Duration.ofHours(24);

    private final DemoDataService demoDataService;
    private final boolean seedOnStartup;

    public DemoDataSeeder(
            DemoDataService demoDataService, @Value("${app.demo.seed-on-startup:true}") boolean seedOnStartup) {
        this.demoDataService = demoDataService;
        this.seedOnStartup = seedOnStartup;
    }

    @Override
    public void run(String... args) {
        if (!seedOnStartup) {
            log.info("app.demo.seed-on-startup=false - leaving the existing demo data alone");
            return;
        }
        try {
            demoDataService.resetIfOlderThan(MAX_AGE);
        } catch (RuntimeException e) {
            log.error("Demo seed failed; the app keeps starting. Retry with POST /api/demo/reset", e);
        }
    }
}
