package br.com.walletcore.bootstrap;

import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.out.TenantRepository;
import br.com.walletcore.domain.tenant.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Creates a demo tenant on first start. Active only with the "dev" profile. */
@Component
@Profile("dev")
class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    static final String CLIENT_ID = "demo-tenant";
    static final String CLIENT_SECRET = "demo-secret-change-me-please";

    private final TenantRepository tenants;
    private final ProvisionTenantUseCase provisionTenant;

    DevDataSeeder(TenantRepository tenants, ProvisionTenantUseCase provisionTenant) {
        this.tenants = tenants;
        this.provisionTenant = provisionTenant;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tenants.findByClientId(CLIENT_ID).isPresent()) {
            return;
        }
        provisionTenant.provision(new ProvisionTenantUseCase.Command(CLIENT_ID, CLIENT_SECRET, "Demo Tenant",
                "12345678", "0001", 4, Tenant.DEFAULT_SCOPES));
        log.warn("DEV ONLY: created tenant '{}' (secret '{}')", CLIENT_ID, CLIENT_SECRET);
    }
}
