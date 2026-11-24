package com.turnos.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turnos.AbstractIntegrationTest;
import com.turnos.tenancy.TenantSessionContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// Boot-time reseed against real Postgres with RLS (the app role, not a
// superuser): fresh data and a visitor's changes survive a boot, a day-old
// demo is rebuilt under the same ids, a missing one is created.
@ActiveProfiles("demo")
@TestPropertySource(properties = {
    "app.demo.admin-email=demo-age-test@example.com",
    "app.demo.admin-password=demo-password-123",
    "app.demo.seed-on-startup=false"
})
class DemoResetIfOlderThanTest extends AbstractIntegrationTest {

    private static final Duration DAY = Duration.ofHours(24);
    private static final String TENANT = DemoDataService.DEMO_TENANT_ID.toString();

    @Autowired
    private DemoDataService demoDataService;

    @Autowired
    private TenantSessionContext tenantSessionContext;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void freshDemo() {
        demoDataService.reset();
    }

    @Test
    void dataUnderADayOldIsKeptWithTheVisitorsChanges() throws Exception {
        Instant seededAt = createdAt();
        addVisitorConversation();

        assertFalse(demoDataService.resetIfOlderThan(DAY));

        assertEquals(seededAt, createdAt());
        assertEquals(1, count("SELECT count(*) FROM conversation WHERE tenant_id = '" + TENANT + "'"));
    }

    @Test
    void dayOldDataIsRegeneratedUnderTheSameIds() throws Exception {
        addVisitorConversation();
        update("UPDATE tenant SET created_at = now() - interval '25 hours' WHERE id = '" + TENANT + "'");
        Instant before = Instant.now().minusSeconds(5);

        assertTrue(demoDataService.resetIfOlderThan(DAY));

        // createdAt is now the regeneration, so the next boot keeps this data
        assertFalse(createdAt().isBefore(before));
        assertFalse(demoDataService.resetIfOlderThan(DAY));
        // stable ids: sessions (JWT with the admin id) and /chat links survive
        assertEquals(DemoDataService.DEMO_TENANT_ID, demoDataService.demoTenantId());
        assertEquals(1, count("SELECT count(*) FROM admin_user WHERE id = '" + DemoDataService.DEMO_ADMIN_ID + "'"));
        assertEquals(0, count("SELECT count(*) FROM conversation WHERE tenant_id = '" + TENANT + "'"));
        assertTrue(count("SELECT count(*) FROM customer WHERE tenant_id = '" + TENANT + "'") > 0);
    }

    @Test
    void missingDemoIsCreated() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            tenantSessionContext.apply(true, null);
            try {
                demoDataService.deleteExisting();
            } finally {
                tenantSessionContext.reset();
            }
        });
        assertEquals(0, count("SELECT count(*) FROM tenant WHERE id = '" + TENANT + "'"));

        assertTrue(demoDataService.resetIfOlderThan(DAY));

        assertEquals(DemoDataService.DEMO_TENANT_ID, demoDataService.demoTenantId());
        assertEquals(1, count("SELECT count(*) FROM admin_user WHERE id = '" + DemoDataService.DEMO_ADMIN_ID + "'"));
    }

    private void addVisitorConversation() throws Exception {
        update("INSERT INTO conversation (tenant_id, channel, external_id) VALUES ('" + TENANT + "', 'WEB', 'visitor')");
    }

    private Instant createdAt() throws Exception {
        return asSuperAdmin(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT created_at FROM tenant WHERE id = '" + TENANT + "'");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        });
    }

    private int count(String query) throws Exception {
        return asSuperAdmin(c -> {
            try (PreparedStatement ps = c.prepareStatement(query); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    private void update(String statement) throws Exception {
        asSuperAdmin(c -> {
            try (PreparedStatement ps = c.prepareStatement(statement)) {
                return ps.executeUpdate();
            }
        });
    }

    interface SqlWork<T> {
        T run(Connection c) throws Exception;
    }

    // RLS bypass for the assertions only; the pooled connection gets it
    // cleared again before going back.
    private <T> T asSuperAdmin(SqlWork<T> work) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            setSuperAdmin(c, true);
            try {
                return work.run(c);
            } finally {
                setSuperAdmin(c, false);
            }
        }
    }

    private static void setSuperAdmin(Connection c, boolean on) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.is_super_admin', '" + on + "', false)")) {
            ps.execute();
        }
    }
}
