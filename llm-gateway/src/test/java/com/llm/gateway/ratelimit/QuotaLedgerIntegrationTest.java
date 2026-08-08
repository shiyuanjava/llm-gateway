package com.llm.gateway.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.llm.gateway.AdminTestTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Flyway schema and the transaction boundary against the configured MySQL instance.
 *
 * <p>This test intentionally uses a dedicated tenant and request id and removes both rows around
 * each invocation so it is safe to rerun against a shared development database.
 */
@SpringBootTest(properties = {"gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET})
class QuotaLedgerIntegrationTest {

    private static final String TENANT = "it-quota-ledger";
    private static final String REQUEST_ID = "it-quota-ledger-request";
    private static final String SECOND_REQUEST_ID = "it-quota-ledger-request-2";
    private static final String CASE_REQUEST_ID = "it-quota-ledger-case-key";
    private static final String CASE_VARIANT_REQUEST_ID = "it-quota-ledger-case-KEY";

    @Autowired
    private QuotaLedgerService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBefore() {
        clean();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    @Test
    void repeatedSettlementIsIdempotentAndConflictsDoNotChangeUsage() {
        assertThat(service.settle(REQUEST_ID, TENANT, 42)).isEqualTo(QuotaLedgerService.SettlementResult.APPLIED);
        assertThat(service.settle(REQUEST_ID, TENANT, 42)).isEqualTo(QuotaLedgerService.SettlementResult.DUPLICATE);
        assertThat(service.settledTokens(TENANT)).isEqualTo(42L);

        assertThatThrownBy(() -> service.settle(REQUEST_ID, TENANT, 43))
                .isInstanceOf(QuotaLedgerService.QuotaLedgerConflictException.class);
        assertThat(service.settledTokens(TENANT)).isEqualTo(42L);
    }

    @Test
    void distinctRequestsAccumulateUsageAtomically() {
        assertThat(service.settle(REQUEST_ID, TENANT, 7)).isEqualTo(QuotaLedgerService.SettlementResult.APPLIED);
        assertThat(service.settle(SECOND_REQUEST_ID, TENANT, 5)).isEqualTo(QuotaLedgerService.SettlementResult.APPLIED);

        assertThat(service.settledTokens(TENANT)).isEqualTo(12L);
    }

    @Test
    void requestIdsDifferingOnlyByCaseAreAppliedIndependently() {
        assertThat(service.settle(CASE_REQUEST_ID, TENANT, 11)).isEqualTo(QuotaLedgerService.SettlementResult.APPLIED);
        assertThat(service.settle(CASE_VARIANT_REQUEST_ID, TENANT, 13))
                .isEqualTo(QuotaLedgerService.SettlementResult.APPLIED);

        assertThat(service.settledTokens(TENANT)).isEqualTo(24L);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM tenant_quota_event WHERE request_id = ?", REQUEST_ID);
        jdbcTemplate.update("DELETE FROM tenant_quota_event WHERE request_id = ?", SECOND_REQUEST_ID);
        jdbcTemplate.update("DELETE FROM tenant_quota_event WHERE request_id = ?", CASE_REQUEST_ID);
        jdbcTemplate.update("DELETE FROM tenant_quota_event WHERE request_id = ?", CASE_VARIANT_REQUEST_ID);
        jdbcTemplate.update("DELETE FROM tenant_quota_usage WHERE tenant = ?", TENANT);
    }
}
