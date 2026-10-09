package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.CustomerRepository;
import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.customer.Email;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Optional;

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
                    INSERT INTO customer (id, tenant_id, name, tax_id, tax_id_type, external_ref, status, created_at, email)
                    VALUES (:id, :tenant, :name, :taxId, :taxIdType, :externalRef, :status, :createdAt, :email)""")
                    .param("id", customer.id().value())
                    .param("tenant", customer.tenantId().value())
                    .param("name", customer.name())
                    .param("taxId", customer.taxId().value())
                    .param("taxIdType", customer.taxId().type().name())
                    .param("externalRef", customer.externalRef(), Types.VARCHAR)
                    .param("status", customer.status().name())
                    .param("createdAt", Sql.ts(customer.createdAt()))
                    .param("email", customer.email() == null ? null : customer.email().value(), Types.VARCHAR)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("CUSTOMER_ALREADY_EXISTS",
                    "a customer with this taxId or externalRef already exists");
        }
    }

    @Override
    public Optional<Contact> findContactByTaxId(TenantId tenantId, TaxId taxId) {
        return jdbc.sql("""
                SELECT c.id, c.name, c.email, c.status, a.id AS account_id
                  FROM customer c
                  LEFT JOIN account a ON a.customer_id = c.id AND a.tenant_id = c.tenant_id AND a.kind = 'CUSTOMER'
                 WHERE c.tenant_id = :tenant AND c.tax_id = :taxId
                 LIMIT 1""")
                .param("tenant", tenantId.value())
                .param("taxId", taxId.value())
                .query((rs, i) -> new Contact(
                        new CustomerId(Sql.uuid(rs, "id")),
                        rs.getString("name"),
                        Email.parseOrNull(rs.getString("email")),
                        Customer.Status.valueOf(rs.getString("status")),
                        rs.getObject("account_id") == null ? null : new AccountId(Sql.uuid(rs, "account_id"))))
                .optional();
    }

    @Override
    public boolean updateEmail(TenantId tenantId, CustomerId customerId, Email email) {
        return jdbc.sql("UPDATE customer SET email = :email WHERE tenant_id = :tenant AND id = :id")
                .param("email", email == null ? null : email.value(), Types.VARCHAR)
                .param("tenant", tenantId.value())
                .param("id", customerId.value())
                .update() == 1;
    }
}
