package br.com.walletcore.bootstrap;

import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase.Command;
import br.com.walletcore.application.port.out.TenantRepository;
import br.com.walletcore.domain.tenant.Tenant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Creates demo tenants on first start. Active only with the "dev" profile. Two tenants (not
 * one) on purpose: a single tenant can't exercise or demonstrate multi-tenant isolation - e.g.
 * confirming that a transfer can't cross tenant boundaries (see ADR-004) needs a second tenant's
 * account to even attempt it against.
 */
@Component
@Profile("dev")
class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    // Kept as the "primary" demo tenant's credentials for anything (docs, scripts) that only
    // ever needs one - the README's curl walkthrough, the console's default login hint, etc.
    static final String CLIENT_ID = "demo-tenant";
    static final String CLIENT_SECRET = "demo-secret-change-me-please";

    private static final List<Command> SEED_TENANTS = List.of(
            new Command(CLIENT_ID, CLIENT_SECRET, "Demo Tenant", "12345678", "0001", 4, Tenant.DEFAULT_SCOPES),
            new Command("segundo-tenant", "segundo-tenant-secret-please", "Segundo Tenant", "87654321", "0001", 4,
                    Tenant.DEFAULT_SCOPES));

    private final TenantRepository tenants;
    private final ProvisionTenantUseCase provisionTenant;

    DevDataSeeder(TenantRepository tenants, ProvisionTenantUseCase provisionTenant) {
        this.tenants = tenants;
        this.provisionTenant = provisionTenant;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Command command : SEED_TENANTS) {
            if (tenants.findByClientId(command.clientId()).isPresent()) {
                continue;
            }
            provisionTenant.provision(command);
            log.warn("DEV ONLY: created tenant '{}' (secret '{}')", command.clientId(), command.clientSecret());
        }
    }
}
