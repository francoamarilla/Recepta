package com.turnos.demo;

import com.turnos.appointments.Appointment;
import com.turnos.appointments.AppointmentOrigin;
import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.appointments.PaymentMethod;
import com.turnos.auth.AdminRole;
import com.turnos.auth.AdminUser;
import com.turnos.auth.AdminUserRepository;
import com.turnos.customers.Customer;
import com.turnos.customers.CustomerRepository;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.professionals.ProfessionalSchedule;
import com.turnos.professionals.ProfessionalScheduleRepository;
import com.turnos.professionals.ProfessionalServiceAssignment;
import com.turnos.professionals.ProfessionalServiceAssignmentRepository;
import com.turnos.services.Service;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.BranchSchedule;
import com.turnos.tenancy.BranchScheduleRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import com.turnos.tenancy.TenantSessionContext;
import com.turnos.tenancy.TenantStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

// Builds the public demo's sample barbershop. Only loaded under the "demo"
// profile, so nothing here can run in local development or production.
//
// Writes go in with the SuperAdmin RLS bypass (app.is_super_admin = true),
// the same GUC the real SuperAdmin endpoints use: there is no request here
// and therefore no tenant context to inherit, and every table is FORCE ROW
// LEVEL SECURITY.
@org.springframework.stereotype.Service
@Profile("demo")
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    // Identifies the demo tenant across restarts. reset() wipes whatever
    // tenant carries this name, so a redeploy never stacks duplicates.
    public static final String DEMO_BUSINESS_NAME = "Barbería Demo";

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int DAYS_BACK = 60;
    private static final int DAYS_FORWARD = 14;

    // Fixed seed: a redeploy regenerates the same barbershop, so a link
    // shared with a prospect keeps showing what it showed yesterday, only
    // shifted forward (every date is relative to today).
    private static final long SEED = 20260214L;

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final BranchScheduleRepository branchScheduleRepository;
    private final AdminUserRepository adminUserRepository;
    private final ServiceRepository serviceRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalScheduleRepository professionalScheduleRepository;
    private final ProfessionalServiceAssignmentRepository assignmentRepository;
    private final CustomerRepository customerRepository;
    private final AppointmentRepository appointmentRepository;
    private final TenantSessionContext tenantSessionContext;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    @PersistenceContext
    private EntityManager entityManager;

    public DemoDataService(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            BranchScheduleRepository branchScheduleRepository,
            AdminUserRepository adminUserRepository,
            ServiceRepository serviceRepository,
            ProfessionalRepository professionalRepository,
            ProfessionalScheduleRepository professionalScheduleRepository,
            ProfessionalServiceAssignmentRepository assignmentRepository,
            CustomerRepository customerRepository,
            AppointmentRepository appointmentRepository,
            TenantSessionContext tenantSessionContext,
            PasswordEncoder passwordEncoder,
            @Value("${app.demo.admin-email:}") String adminEmail,
            @Value("${app.demo.admin-password:}") String adminPassword) {
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.branchScheduleRepository = branchScheduleRepository;
        this.adminUserRepository = adminUserRepository;
        this.serviceRepository = serviceRepository;
        this.professionalRepository = professionalRepository;
        this.professionalScheduleRepository = professionalScheduleRepository;
        this.assignmentRepository = assignmentRepository;
        this.customerRepository = customerRepository;
        this.appointmentRepository = appointmentRepository;
        this.tenantSessionContext = tenantSessionContext;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    public boolean isConfigured() {
        return !adminEmail.isBlank() && !adminPassword.isBlank();
    }

    // Idempotent: deletes the demo tenant and everything hanging off it,
    // then rebuilds it from scratch with dates relative to today.
    @Transactional
    public void reset() {
        if (!isConfigured()) {
            log.warn("Demo profile active but DEMO_ADMIN_EMAIL/DEMO_ADMIN_PASSWORD are unset - skipping seed");
            return;
        }
        long started = System.currentTimeMillis();
        tenantSessionContext.apply(true, null);
        try {
            deleteExisting();
            int appointments = seed();
            tenantSessionContext.flush();
            log.info(
                    "Demo data regenerated: {} appointments in {} ms",
                    appointments,
                    System.currentTimeMillis() - started);
        } finally {
            tenantSessionContext.reset();
        }
    }

    // The startup seed: regenerates only when the demo is missing or at least
    // maxAge old. Tenant.createdAt is the last regeneration (reset() deletes
    // the tenant and inserts it again), so a boot within maxAge - Render
    // waking the free instance, or a deploy while a visitor is using the
    // demo - leaves their data alone. Same transaction as reset(): the check
    // and the rebuild commit together.
    @Transactional
    public boolean resetIfOlderThan(Duration maxAge) {
        if (!isConfigured()) {
            log.warn("Demo profile active but DEMO_ADMIN_EMAIL/DEMO_ADMIN_PASSWORD are unset - skipping seed");
            return false;
        }
        Optional<Instant> seededAt;
        tenantSessionContext.apply(true, null);
        try {
            seededAt = tenantRepository.findAll().stream()
                    .filter(t -> DEMO_BUSINESS_NAME.equals(t.getBusinessName()))
                    .map(Tenant::getCreatedAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder());
        } finally {
            tenantSessionContext.reset();
        }
        if (!needsReset(seededAt, Instant.now(), maxAge)) {
            log.info("Demo data from {} is newer than {} - keeping it", seededAt.get(), maxAge);
            return false;
        }
        reset();
        return true;
    }

    static boolean needsReset(Optional<Instant> seededAt, Instant now, Duration maxAge) {
        return seededAt.map(at -> !at.isAfter(now.minus(maxAge))).orElse(true);
    }

    // Package-private for the "no demo tenant yet" test; callers need the
    // super-admin context and a transaction, as reset() provides.
    void deleteExisting() {
        List<UUID> tenantIds = tenantRepository.findAll().stream()
                .filter(t -> DEMO_BUSINESS_NAME.equals(t.getBusinessName()))
                .map(Tenant::getId)
                .toList();
        if (tenantIds.isEmpty()) {
            return;
        }
        // Native statements in FK order. Going through the repositories would
        // mean loading every row first, and the cascade is wide.
        String branchesOfTenant = "SELECT id FROM branch WHERE tenant_id IN (:ids)";
        String professionalsOfTenant = "SELECT id FROM professional WHERE branch_id IN (" + branchesOfTenant + ")";
        // The AI assistant's tables (V17-V20) point at appointments,
        // customers and the tenant, so they go before all of those.
        execute("DELETE FROM pending_action WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM ai_turn WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM message WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM conversation WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM assistant_config WHERE tenant_id IN (:ids)", tenantIds);
        // reminder_sent has no tenant_id (see V10): it hangs off the
        // appointment, so it has to go first and by appointment.
        execute(
                "DELETE FROM reminder_sent WHERE appointment_id IN"
                        + " (SELECT id FROM appointment WHERE branch_id IN (" + branchesOfTenant + "))",
                tenantIds);
        execute("DELETE FROM appointment WHERE branch_id IN (" + branchesOfTenant + ")", tenantIds);
        execute("DELETE FROM professional_service WHERE professional_id IN (" + professionalsOfTenant + ")", tenantIds);
        execute("DELETE FROM professional_schedule WHERE professional_id IN (" + professionalsOfTenant + ")", tenantIds);
        execute("DELETE FROM professional WHERE branch_id IN (" + branchesOfTenant + ")", tenantIds);
        execute("DELETE FROM bot_conversation_state WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM customer WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM service WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM whatsapp_integration WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM branch_closure WHERE branch_id IN (" + branchesOfTenant + ")", tenantIds);
        execute("DELETE FROM branch_schedule WHERE branch_id IN (" + branchesOfTenant + ")", tenantIds);
        execute("DELETE FROM branch WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM admin_user WHERE tenant_id IN (:ids)", tenantIds);
        execute("DELETE FROM tenant WHERE id IN (:ids)", tenantIds);
    }

    // Fixed ids, so every reset (each Render free boot reseeds) rebuilds the
    // same tenant and admin: a JWT from before the reset, an open /chat page
    // or a /chat/<id> link keeps working instead of answering 404.
    static final UUID DEMO_TENANT_ID = UUID.fromString("0de70000-0000-4000-8000-000000000001");
    static final UUID DEMO_ADMIN_ID = UUID.fromString("0de70000-0000-4000-8000-000000000002");

    // ponytail: the ids are generated, so re-key the row right after insert,
    // before anything references it, and reload it under the fixed id.
    private <T> T pinId(T entity, java.util.function.Function<T, UUID> id, String table, UUID fixed, Class<T> type) {
        UUID generated = id.apply(entity);
        entityManager.detach(entity);
        entityManager.createNativeQuery("UPDATE " + table + " SET id = :fixed WHERE id = :generated")
                .setParameter("fixed", fixed)
                .setParameter("generated", generated)
                .executeUpdate();
        return entityManager.find(type, fixed);
    }

    private void execute(String sql, List<UUID> tenantIds) {
        entityManager.createNativeQuery(sql).setParameter("ids", tenantIds).executeUpdate();
    }

    private int seed() {
        Tenant tenant = new Tenant();
        tenant.setBusinessName(DEMO_BUSINESS_NAME);
        tenant.setContactPhone("+54 11 5555-0100");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setBusinessCategory("barbershop");
        tenant.setCancellationWindowHours(2);
        tenant.setTimezone(ZONE.getId());
        tenant.setAddress("Av. Siempreviva 742, CABA");
        tenant = tenantRepository.saveAndFlush(tenant);
        tenant = pinId(tenant, Tenant::getId, "tenant", DEMO_TENANT_ID, Tenant.class);

        Branch branch = new Branch();
        branch.setTenantId(tenant.getId());
        branch.setAddress("Av. Siempreviva 742, CABA");
        branch = branchRepository.saveAndFlush(branch);

        AdminUser admin = new AdminUser();
        admin.setTenantId(tenant.getId());
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole(AdminRole.ADMIN);
        admin.setActive(true);
        admin = adminUserRepository.saveAndFlush(admin);
        admin = pinId(admin, AdminUser::getId, "admin_user", DEMO_ADMIN_ID, AdminUser.class);

        // Split day, Monday to Saturday - also what makes the week editor in
        // Configuración worth looking at.
        for (DayOfWeek day : WORK_WEEK) {
            saveBranchRange(branch.getId(), day, MORNING_OPEN, MORNING_CLOSE);
            saveBranchRange(branch.getId(), day, AFTERNOON_OPEN, AFTERNOON_CLOSE);
        }

        List<Service> services = seedServices(tenant.getId());
        List<Professional> professionals = seedProfessionals(branch.getId(), services);
        List<Customer> customers = seedCustomers(tenant.getId());
        seedAssistantConfig(tenant.getId(), admin.getId());
        return seedAppointments(branch.getId(), admin.getId(), professionals, services, customers);
    }

    // A published assistant, so /chat/demo shows the owner configuration at
    // work. A small budget: the public demo's real cap is AI_DAILY_BUDGET_USD.
    private void seedAssistantConfig(UUID tenantId, UUID adminId) {
        entityManager.createNativeQuery("INSERT INTO assistant_config (tenant_id, version, status, assistant_name, "
                        + "tone, voseo, emoji_level, greeting, policies, confirmation_mode, monthly_budget_usd, "
                        + "created_by, published_at) VALUES (:tenant, 1, 'PUBLISHED', 'Tito', 'CERCANO', true, 'LOW', "
                        + ":greeting, :policies, 'STANDARD', 5, :admin, now())")
                .setParameter("tenant", tenantId)
                .setParameter("greeting", "¡Hola! Soy Tito, el asistente de Barbería Demo. ¿Querés sacar un turno o "
                        + "consultar algo?")
                .setParameter("policies", "Tolerancia de 10 minutos de demora. Se paga en el local, en efectivo, "
                        + "débito o transferencia.")
                .setParameter("admin", adminId)
                .executeUpdate();
    }

    // For the fixed /chat/demo link: the demo tenant's id changes on every reset.
    public UUID demoTenantId() {
        tenantSessionContext.apply(true, null);
        try {
            return tenantRepository.findAll().stream()
                    .filter(t -> DEMO_BUSINESS_NAME.equals(t.getBusinessName()))
                    .map(Tenant::getId)
                    .findFirst()
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.NOT_FOUND));
        } finally {
            tenantSessionContext.reset();
        }
    }

    private static final List<DayOfWeek> WORK_WEEK = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    private static final LocalTime MORNING_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MORNING_CLOSE = LocalTime.of(13, 0);
    private static final LocalTime AFTERNOON_OPEN = LocalTime.of(16, 0);
    private static final LocalTime AFTERNOON_CLOSE = LocalTime.of(20, 0);

    private void saveBranchRange(UUID branchId, DayOfWeek day, LocalTime open, LocalTime close) {
        BranchSchedule schedule = new BranchSchedule();
        schedule.setBranchId(branchId);
        schedule.setDayOfWeek(day);
        schedule.setOpenTime(open);
        schedule.setCloseTime(close);
        branchScheduleRepository.save(schedule);
    }

    private record ServiceSpec(String name, String description, int minutes, int price) {}

    private List<Service> seedServices(UUID tenantId) {
        List<ServiceSpec> specs = List.of(
                new ServiceSpec("Corte clásico", "Corte con tijera y máquina, lavado incluido", 30, 9000),
                new ServiceSpec("Corte + barba", "Corte completo más perfilado de barba", 45, 13500),
                new ServiceSpec("Perfilado de barba", "Recorte, perfilado y toalla caliente", 20, 6500),
                new ServiceSpec("Corte niño", "Hasta 12 años", 30, 7000),
                new ServiceSpec("Afeitado clásico", "Navaja, espuma caliente y after shave", 30, 8500),
                new ServiceSpec("Color / camuflaje de canas", "Aplicación y enjuague", 60, 18000),
                new ServiceSpec("Corte + color", "Corte clásico con color", 75, 24000));

        List<Service> saved = new ArrayList<>();
        for (ServiceSpec spec : specs) {
            Service service = new Service();
            service.setTenantId(tenantId);
            service.setName(spec.name());
            service.setDescription(spec.description());
            service.setDurationMinutes(spec.minutes());
            service.setPrice(BigDecimal.valueOf(spec.price()));
            service.setActive(true);
            saved.add(serviceRepository.saveAndFlush(service));
        }
        return saved;
    }

    private record ProfessionalSpec(String name, String phone, List<DayOfWeek> days, boolean afternoonOnly) {}

    private List<Professional> seedProfessionals(UUID branchId, List<Service> services) {
        List<ProfessionalSpec> specs = List.of(
                new ProfessionalSpec("Nico Ejemplo", "+54 11 5555-0111", WORK_WEEK, false),
                new ProfessionalSpec("Juli Muestra", "+54 11 5555-0112", WORK_WEEK, false),
                new ProfessionalSpec("Rama Prueba", "+54 11 5555-0113",
                        List.of(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), false),
                new ProfessionalSpec("Dai Ficticia", "+54 11 5555-0114",
                        List.of(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), true));

        List<Professional> saved = new ArrayList<>();
        for (ProfessionalSpec spec : specs) {
            Professional professional = new Professional();
            professional.setBranchId(branchId);
            professional.setName(spec.name());
            professional.setContactPhone(spec.phone());
            professional.setActive(true);
            professional = professionalRepository.saveAndFlush(professional);

            for (DayOfWeek day : spec.days()) {
                if (!spec.afternoonOnly()) {
                    saveProfessionalRange(professional.getId(), day, MORNING_OPEN, MORNING_CLOSE);
                }
                saveProfessionalRange(professional.getId(), day, AFTERNOON_OPEN, AFTERNOON_CLOSE);
            }
            for (Service service : services) {
                ProfessionalServiceAssignment assignment = new ProfessionalServiceAssignment();
                assignment.setProfessionalId(professional.getId());
                assignment.setServiceId(service.getId());
                assignmentRepository.save(assignment);
            }
            saved.add(professional);
        }
        return saved;
    }

    private void saveProfessionalRange(UUID professionalId, DayOfWeek day, LocalTime open, LocalTime close) {
        ProfessionalSchedule schedule = new ProfessionalSchedule();
        schedule.setProfessionalId(professionalId);
        schedule.setDayOfWeek(day);
        schedule.setOpenTime(open);
        schedule.setCloseTime(close);
        professionalScheduleRepository.save(schedule);
    }

    private List<Customer> seedCustomers(UUID tenantId) {
        String[] firstNames = {
            "Agus", "Beto", "Caro", "Dani", "Emi", "Fede", "Gaby", "Hernán", "Ivo", "Juli",
            "Kevin", "Lau", "Mati", "Nati", "Ori", "Pato", "Quique", "Rocío", "Santi", "Tomi"
        };
        String[] lastNames = {"Prueba", "Ejemplo", "Muestra", "Ficticio", "Demo", "Ensayo", "Testeo", "Simulado"};
        List<Customer> saved = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Customer customer = new Customer();
            customer.setTenantId(tenantId);
            customer.setName(firstNames[i % firstNames.length] + " " + lastNames[(i / 3) % lastNames.length]);
            // 11 5555-xxxx is the range reserved for fiction, like 555-0100
            // in North American film.
            customer.setPhone(String.format("+54 11 5555-%04d", 1000 + i));
            customer.setActive(true);
            saved.add(customerRepository.saveAndFlush(customer));
        }
        return saved;
    }

    // Walks every professional's own schedule day by day and drops
    // appointments into consecutive slots, so two appointments of the same
    // professional can never overlap - which is what appointment_no_overlap
    // (V7, widened in V13) enforces in the database anyway.
    private int seedAppointments(
            UUID branchId,
            UUID adminId,
            List<Professional> professionals,
            List<Service> services,
            List<Customer> customers) {
        Random random = new Random(SEED);
        LocalDate today = LocalDate.now(ZONE);
        List<Appointment> batch = new ArrayList<>();

        for (Professional professional : professionals) {
            List<ProfessionalSchedule> schedules =
                    professionalScheduleRepository.findByProfessionalId(professional.getId());
            for (int offset = -DAYS_BACK; offset <= DAYS_FORWARD; offset++) {
                LocalDate date = today.plusDays(offset);
                for (ProfessionalSchedule schedule : schedules) {
                    if (schedule.getDayOfWeek() != date.getDayOfWeek()) {
                        continue;
                    }
                    LocalTime cursor = schedule.getOpenTime();
                    while (cursor.isBefore(schedule.getCloseTime())) {
                        Service service = services.get(random.nextInt(services.size()));
                        LocalTime end = cursor.plusMinutes(service.getDurationMinutes());
                        if (end.isAfter(schedule.getCloseTime())) {
                            break;
                        }
                        // ~35% of the slots stay free, so the agenda looks
                        // busy without looking generated.
                        if (random.nextInt(100) < 65) {
                            batch.add(buildAppointment(
                                    branchId,
                                    adminId,
                                    professional,
                                    service,
                                    customers.get(random.nextInt(customers.size())),
                                    date,
                                    cursor,
                                    end,
                                    offset,
                                    random));
                        }
                        cursor = end;
                    }
                }
            }
        }
        appointmentRepository.saveAll(batch);
        return batch.size();
    }

    private Appointment buildAppointment(
            UUID branchId,
            UUID adminId,
            Professional professional,
            Service service,
            Customer customer,
            LocalDate date,
            LocalTime start,
            LocalTime end,
            int dayOffset,
            Random random) {
        Appointment appointment = new Appointment();
        appointment.setBranchId(branchId);
        appointment.setProfessionalId(professional.getId());
        appointment.setServiceId(service.getId());
        appointment.setCustomerId(customer.getId());
        appointment.setStartTime(date.atTime(start).atZone(ZONE).toInstant());
        appointment.setEndTime(date.atTime(end).atZone(ZONE).toInstant());
        appointment.setDurationMinutes(service.getDurationMinutes());
        appointment.setPrice(service.getPrice());
        appointment.setOrigin(random.nextInt(100) < 40 ? AppointmentOrigin.BOT : AppointmentOrigin.DASHBOARD);

        if (dayOffset >= 0) {
            appointment.setStatus(AppointmentStatus.CONFIRMED);
            return appointment;
        }
        // Past: mostly charged, with enough cancellations and no-shows that
        // Estadísticas has something to show.
        int roll = random.nextInt(100);
        if (roll < 80) {
            appointment.setStatus(AppointmentStatus.COMPLETED);
            appointment.setCompletedBy(adminId);
            appointment.setCompletedAt(appointment.getEndTime());
            appointment.setPaymentMethod(paymentMethod(random));
        } else if (roll < 92) {
            appointment.setStatus(AppointmentStatus.CANCELLED);
        } else {
            appointment.setStatus(AppointmentStatus.NO_SHOW);
            appointment.setNoShowMarkedBy(adminId);
        }
        return appointment;
    }

    private PaymentMethod paymentMethod(Random random) {
        int roll = random.nextInt(100);
        if (roll < 55) {
            return PaymentMethod.CASH;
        }
        return roll < 85 ? PaymentMethod.TRANSFER : PaymentMethod.CARD;
    }
}
