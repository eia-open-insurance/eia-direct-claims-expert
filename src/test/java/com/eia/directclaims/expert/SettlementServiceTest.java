package com.eia.directclaims.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.OTHER_PARTY_ID;
import static com.eia.directclaims.expert.Fixtures.OTHER_TENANT;
import static com.eia.directclaims.expert.Fixtures.PAYMENT_ID;
import static com.eia.directclaims.expert.Fixtures.SETTLEMENT_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.coverage;
import static com.eia.directclaims.expert.Fixtures.settlement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {
    @Mock
    private SettlementRepository repository;
    @Mock
    private CoverageRepository coverageRepository;
    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OutboxRepository outbox;

    private SettlementService service;

    @BeforeEach
    void setUp() {
        service = new SettlementService(repository, coverageRepository, new ClaimLookup(claimRepository),
                claimRepository, outbox, Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"),
                        ZoneOffset.UTC));
    }

    @Test
    void refusesToProposeWithoutAFinalCoverageDecision() {
        claimExists();
        when(coverageRepository.findLatestFinal(TENANT, CLAIM_ID)).thenReturn(Optional.empty());

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.propose(TENANT, CLAIM_ID, proposal()));

        assertTrue(error.getMessage().contains("coverage decision"));
        verifyNoInteractions(repository);
    }

    @Test
    void refusesToSettleADeniedClaim() {
        claimExists();
        when(coverageRepository.findLatestFinal(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(coverage("DENIED")));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.propose(TENANT, CLAIM_ID, proposal()));

        assertTrue(error.getMessage().contains("DENIED"));
        verifyNoInteractions(repository);
    }

    @Test
    void proposesASettlementUnderCoveredCoverage() {
        claimExists();
        when(coverageRepository.findLatestFinal(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(coverage("COVERED")));
        when(repository.nextSettlementSequence()).thenReturn(1L);
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq("SET-2026-0000001"),
                eq(new BigDecimal("60000.0000")), eq("USD"), eq("LUMP_SUM"), eq("FULL"),
                eq("Wire to claimant trust account"), any(Instant.class)))
                .thenReturn(settlement("PROPOSED"));

        Settlement proposed = service.propose(TENANT, CLAIM_ID, proposal());

        assertEquals("PROPOSED", proposed.status());
    }

    @Test
    void authorizationRequiresAClaimAuthorityLimit() {
        claimExistsWithAuthority(null);
        when(coverageRepository.findLatestFinal(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(coverage("COVERED")));
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("PROPOSED")));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.approve(TENANT, CLAIM_ID, SETTLEMENT_ID, approval("Committee", 1)));

        assertTrue(error.getMessage().contains("no settlement authority"));
    }

    @Test
    void authorizationCannotExceedTheRemainingLimit() {
        claimExistsWithAuthority(new BigDecimal("50000.0000"));
        when(coverageRepository.findLatestFinal(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(coverage("COVERED")));
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("PROPOSED")));
        when(repository.committedAmount(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(new BigDecimal("10000.0000"));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.approve(TENANT, CLAIM_ID, SETTLEMENT_ID, approval("Committee", 1)));

        assertTrue(error.getMessage().contains("remaining settlement authority"));
    }

    @Test
    void authorizationRequiresAnApprovalAuthority() {
        claimExists();
        when(coverageRepository.findLatestFinal(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(coverage("COVERED")));
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("PROPOSED")));

        assertThrows(ClaimValidationException.class,
                () -> service.approve(TENANT, CLAIM_ID, SETTLEMENT_ID, approval(" ", 1)));
    }

    @Test
    void authorizationRejectsAStaleVersion() {
        claimExists();
        when(coverageRepository.findLatestFinal(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(coverage("COVERED")));
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("PROPOSED")));

        assertThrows(ClaimConflictException.class,
                () -> service.approve(TENANT, CLAIM_ID, SETTLEMENT_ID, approval("Committee", 4)));
    }

    @Test
    void authorizesWithinTheRemainingLimit() {
        claimExists();
        when(coverageRepository.findLatestFinal(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(coverage("COVERED")));
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("PROPOSED")));
        when(repository.committedAmount(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(new BigDecimal("10000.0000"));
        when(repository.authorize(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID), eq("Claims Committee"),
                eq(OTHER_PARTY_ID), eq(1), any(Instant.class))).thenReturn(settlement("AUTHORIZED"));

        Settlement authorized = service.approve(TENANT, CLAIM_ID, SETTLEMENT_ID,
                approval("Claims Committee", 1));

        assertEquals("AUTHORIZED", authorized.status());
    }

    @Test
    void executionRequiresAReleaseDocument() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("AUTHORIZED")));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.execute(TENANT, CLAIM_ID, SETTLEMENT_ID,
                        new SettlementExecutionRequest(null, "Wire funds", 1)));

        assertTrue(error.getMessage().contains("releaseDocumentId"));
    }

    @Test
    void executionRequiresPaymentInstructions() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(new Settlement(SETTLEMENT_ID, TENANT, CLAIM_ID,
                        "SET-2026-0000001", new BigDecimal("60000.0000"), "USD", "LUMP_SUM", "FULL",
                        "Claims Committee", null, null, "AUTHORIZED", OTHER_PARTY_ID, Fixtures.NOW,
                        null, null, null, 1, Fixtures.NOW, Fixtures.NOW)));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.execute(TENANT, CLAIM_ID, SETTLEMENT_ID,
                        new SettlementExecutionRequest(OTHER_PARTY_ID, null, 1)));

        assertTrue(error.getMessage().contains("paymentInstructions"));
    }

    @Test
    void executesAnAuthorizedSettlementWithARelease() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("AUTHORIZED")));
        when(repository.execute(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID), eq(OTHER_PARTY_ID),
                eq("Wire funds"), eq(1), any(Instant.class))).thenReturn(settlement("EXECUTED"));

        Settlement executed = service.execute(TENANT, CLAIM_ID, SETTLEMENT_ID,
                new SettlementExecutionRequest(OTHER_PARTY_ID, "Wire funds", 1));

        assertEquals("EXECUTED", executed.status());
    }

    @Test
    void executionRejectsAStaleVersion() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("AUTHORIZED")));

        assertThrows(ClaimConflictException.class, () -> service.execute(TENANT, CLAIM_ID, SETTLEMENT_ID,
                new SettlementExecutionRequest(OTHER_PARTY_ID, "Wire funds", 9)));
    }

    @Test
    void linksAPaymentOnAnAuthorizedSettlement() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("AUTHORIZED")));
        when(claimRepository.bumpVersion(eq(TENANT), eq(CLAIM_ID), eq(0), any(Instant.class)))
                .thenReturn(1);
        when(repository.updatePayment(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID), eq(PAYMENT_ID),
                any(Instant.class), eq(1), any(Instant.class)))
                .thenReturn(settlement("AUTHORIZED", PAYMENT_ID, Fixtures.NOW, 2));

        Settlement linked = service.updatePayment(TENANT, CLAIM_ID, SETTLEMENT_ID,
                paymentRequest(PAYMENT_ID));

        assertEquals(PAYMENT_ID, linked.paymentId());
        assertNotNull(linked.paymentLinkedAt());
        assertEquals(2, linked.version());
        verify(outbox).insert(any());
    }

    @Test
    void unlinksThePaymentOnAnExecutedSettlement() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID)).thenReturn(Optional.of(
                settlement("EXECUTED", PAYMENT_ID, Fixtures.NOW, 2)));
        when(claimRepository.bumpVersion(eq(TENANT), eq(CLAIM_ID), eq(0), any(Instant.class)))
                .thenReturn(1);
        when(repository.updatePayment(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID), isNull(),
                isNull(), eq(2), any(Instant.class)))
                .thenReturn(settlement("EXECUTED", null, null, 3));

        Settlement unlinked = service.updatePayment(TENANT, CLAIM_ID, SETTLEMENT_ID,
                paymentRequest(null));

        assertNull(unlinked.paymentId());
        assertNull(unlinked.paymentLinkedAt());
        assertEquals(3, unlinked.version());
        verify(outbox).insert(any());
    }

    @Test
    void refusesToLinkAPaymentOnAProposedSettlement() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("PROPOSED")));

        SettlementNotLinkableException error = assertThrows(SettlementNotLinkableException.class,
                () -> service.updatePayment(TENANT, CLAIM_ID, SETTLEMENT_ID,
                        paymentRequest(PAYMENT_ID)));

        assertTrue(error.getMessage().contains("PROPOSED"));
        verify(repository, never()).updatePayment(any(), any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void refusesToLinkAPaymentOnARejectedSettlement() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement("REJECTED")));

        assertThrows(SettlementNotLinkableException.class,
                () -> service.updatePayment(TENANT, CLAIM_ID, SETTLEMENT_ID,
                        paymentRequest(PAYMENT_ID)));

        verifyNoInteractions(outbox);
    }

    @Test
    void paymentLinkIsTenantScoped() {
        when(claimRepository.findById(OTHER_TENANT, CLAIM_ID)).thenReturn(Optional.empty());

        assertThrows(ClaimNotFoundException.class,
                () -> service.updatePayment(OTHER_TENANT, CLAIM_ID, SETTLEMENT_ID,
                        paymentRequest(PAYMENT_ID)));

        verify(repository, never()).findById(any(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void repeatingTheCurrentPaymentIsANoOp() {
        claimExists();
        Settlement current = settlement("AUTHORIZED", PAYMENT_ID, Fixtures.NOW, 2);
        when(repository.findById(TENANT, CLAIM_ID, SETTLEMENT_ID)).thenReturn(Optional.of(current));

        Settlement unchanged = service.updatePayment(TENANT, CLAIM_ID, SETTLEMENT_ID,
                paymentRequest(PAYMENT_ID));

        assertEquals(current, unchanged);
        verify(repository, never()).updatePayment(any(), any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    private void claimExists() {
        claimExistsWithAuthority(new BigDecimal("500000.0000"));
    }

    private void claimExistsWithAuthority(BigDecimal authority) {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim(
                "BODILY_INJURY", "REGISTERED", 0, authority)));
    }

    private static SettlementRequest proposal() {
        return new SettlementRequest(new BigDecimal("60000.00"), "USD", "LUMP_SUM", "FULL",
                "Wire to claimant trust account");
    }

    private static SettlementApprovalRequest approval(String authority, int version) {
        return new SettlementApprovalRequest(authority, OTHER_PARTY_ID, version);
    }

    private static SettlementPaymentRequest paymentRequest(UUID paymentId) {
        return new SettlementPaymentRequest(paymentId);
    }
}
