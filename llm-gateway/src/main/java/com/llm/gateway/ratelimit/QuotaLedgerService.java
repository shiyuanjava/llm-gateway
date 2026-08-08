package com.llm.gateway.ratelimit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.llm.gateway.persistence.entity.TenantQuotaEventEntity;
import com.llm.gateway.persistence.mapper.TenantQuotaEventMapper;
import com.llm.gateway.persistence.mapper.TenantQuotaUsageMapper;

/**
 * Transactional, idempotent token quota settlement backed by MySQL.
 *
 * <p>The unique request id on {@code tenant_quota_event} determines whether the usage aggregate
 * is updated. Both writes happen in this service transaction, so a failed aggregate update cannot
 * leave a committed idempotency event behind.
 */
@Service
public class QuotaLedgerService {

    private static final int MAX_IDENTIFIER_LENGTH = 64;

    public enum SettlementResult {
        APPLIED,
        DUPLICATE
    }

    /** Raised when an idempotency key is reused with different settlement data. */
    public static class QuotaLedgerConflictException extends RuntimeException {

        public QuotaLedgerConflictException(String message) {
            super(message);
        }
    }

    private final TenantQuotaEventMapper eventMapper;
    private final TenantQuotaUsageMapper usageMapper;

    public QuotaLedgerService(TenantQuotaEventMapper eventMapper, TenantQuotaUsageMapper usageMapper) {
        this.eventMapper = eventMapper;
        this.usageMapper = usageMapper;
    }

    /**
     * Settles one request's token usage exactly once.
     *
     * @return {@link SettlementResult#APPLIED} for a new event, or
     *     {@link SettlementResult#DUPLICATE} for the same event payload
     */
    @Transactional
    public SettlementResult settle(String requestId, String tenant, long tokens) {
        validateIdentifier(requestId, "requestId");
        validateIdentifier(tenant, "tenant");
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens must not be negative");
        }

        if (eventMapper.insertIgnore(requestId, tenant, tokens) == 1) {
            usageMapper.addSettledTokens(tenant, tokens);
            return SettlementResult.APPLIED;
        }

        TenantQuotaEventEntity existing = eventMapper.selectByRequestId(requestId);
        if (existing == null
                || !tenant.equals(existing.getTenant())
                || existing.getTokens() == null
                || tokens != existing.getTokens()) {
            throw new QuotaLedgerConflictException("requestId is already bound to a different quota settlement");
        }
        return SettlementResult.DUPLICATE;
    }

    /** Returns the current persisted aggregate, or zero when no usage row exists. */
    public long settledTokens(String tenant) {
        Long value = usageMapper.selectSettledTokens(tenant);
        return value == null ? 0L : value;
    }

    private static void validateIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    fieldName + " must contain 1 to " + MAX_IDENTIFIER_LENGTH + " characters");
        }
    }
}
