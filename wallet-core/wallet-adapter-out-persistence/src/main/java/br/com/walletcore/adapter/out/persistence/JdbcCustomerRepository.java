package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.CustomerRepository;
import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.shared.TenantId;
import java.sql.Types;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCustomerRepository implements CustomerRepository {

    private final JdbcClient jdbc;

    JdbcCustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsByTaxId(TenantId tenantId, TaxId taxId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM customer WHERE tenant_id = :tenant AND tax_id = :taxId)")
                .param("tenant", tenantId.value())
                .param("taxId", taxId.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public void insert(Customer customer) {
        try {
            jdbc.sql("""
                    INSERT INTO customer (id, tenant_id, name, tax_id, tax_id_type, external_ref, status, created_at)
                    VALUES (:id, :tenant, :name, :taxId, :taxIdType, :externalRef, :status, :createdAt)""")
                    .param("id", customer.id().value())
                    .param("tenant", customer.tenantId().value())
                    .param("name", customer.name())
                    .param("taxId", customer.taxId().value())
                    .param("taxIdType", customer.taxId().type().name())
                    .param("externalRef", customer.externalRef(), Types.VARCHAR)
                    .param("status", customer.status().name())
                    .param("createdAt", Sql.ts(customer.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("CUSTOMER_ALREADY_EXISTS",
                    "a customer with this taxId or externalRef already exists");
        }
    }
}
