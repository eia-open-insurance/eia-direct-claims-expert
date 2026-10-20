package com.eia.directclaims.expert;

import com.eia.platform.libs.events.EventJson;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.eia.directclaims.expert.Fixtures.ALLOCATION_ID;
import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.LIMIT_ID;
import static com.eia.directclaims.expert.Fixtures.NOW;
import static com.eia.directclaims.expert.Fixtures.OTHER_POLICY_ID;
import static com.eia.directclaims.expert.Fixtures.OTHER_TENANT;
import static com.eia.directclaims.expert.Fixtures.POLICY_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.allocation;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.failedAllocation;
import static com.eia.directclaims.expert.Fixtures.postedAllocation;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
class ClaimAllocationServiceTest {
    @Mock
    private ClaimAllocationRepository repository;
    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OutboxRepository outbox;

    private ClaimAllocationService service;

    @BeforeEach
    void setUp() {
        service = new ClaimAllocationService(repository, claimRepository,
                new ClaimLookup(claimRepository), outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAnAllocationAndWritesTheAllocationCreatedEvent() {
        claimExists(0);
        when(repository.totalPct(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("0.250000"));
        when(claimRepository.bumpVersion(TENANT, CLAIM_ID, 0, NOW)).thenReturn(1);
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq(POLICY_ID), isNull(), eq(2024),
                eq("PRIMARY"), eq(new BigDecimal("0.250000")),
                eq(new BigDecimal("150000.0000")), eq(NOW)))
                .thenReturn(allocation(2024, "PRIMARY", "0.250000", "150000.0000"));
        when(repository.totals(TENANT, CLAIM_ID)).thenReturn(new ClaimAllocationRepository.Totals(
                new BigDecimal("0.500000"), new BigDecimal("300000.0000")));

        ClaimAllocation created = service.create(TENANT, CLAIM_ID,
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "150000.00", null));

        assertEquals(ALLOCATION_ID, created.id());
        assertEquals(2024, created.policyYear());
        assertEquals("PRIMARY", created.layer());
        assertNull(created.limitId());
        assertEquals("NOT_POSTED", created.erosionStatus());
        assertNull(created.erosionMessage());
        assertNull(created.erosionPostedAt());

        OutboxEvent event = capturedEvent();
        assertEquals(ClaimEvents.ALLOCATION_CREATED, event.eventType());
        assertEquals("Claim", event.aggregateType());
        assertEquals(CLAIM_ID, event.aggregateId());
        assertEquals(2L, event.aggregateVersion());
        Map<String, Object> payload = payload(event);
        assertEquals(CLAIM_ID.toString(), payload.get("claimId"));
        assertEquals("CLM-2026-0000001", payload.get("claimNumber"));
        assertEquals(POLICY_ID.toString(), payload.get("policyId"));
        assertEquals(2024, payload.get("policyYear"));
        assertEquals("PRIMARY", payload.get("layer"));
        assertEquals("0.250000", payload.get("allocationPct"));
        assertEquals("150000.0000", payload.get("allocatedAmount"));
        assertEquals("0.500000", payload.get("totalPct"));
        assertEquals("300000.0000", payload.get("totalAmount"));
    }

    @Test
    void persistsAnOptionalLimitReference() {
        claimExists(0);
        when(repository.totalPct(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("0.000000"));
        when(claimRepository.bumpVersion(TENANT, CLAIM_ID, 0, NOW)).thenReturn(1);
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq(POLICY_ID), eq(LIMIT_ID),
                eq(2024), eq("PRIMARY"), eq(new BigDecimal("0.250000")),
                eq(new BigDecimal("150000.0000")), eq(NOW)))
                .thenReturn(allocation(LIMIT_ID, 2024, "PRIMARY", "0.250000", "150000.0000"));
        when(repository.totals(TENANT, CLAIM_ID)).thenReturn(new ClaimAllocationRepository.Totals(
                new BigDecimal("0.250000"), new BigDecimal("150000.0000")));

        ClaimAllocation created = service.create(TENANT, CLAIM_ID,
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "150000.00", LIMIT_ID));

        assertEquals(LIMIT_ID, created.limitId());
        verify(repository).insert(any(), eq(TENANT), eq(CLAIM_ID), eq(POLICY_ID), eq(LIMIT_ID),
                eq(2024), eq("PRIMARY"), eq(new BigDecimal("0.250000")),
                eq(new BigDecimal("150000.0000")), eq(NOW));
    }

    @Test
    void marksAPostingAndStampsThePostingInstant() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID))
                .thenReturn(Optional.of(allocation(2024, "PRIMARY", "0.250000", "150000.0000")));
        when(repository.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID, "POSTED",
                "Limit erosion accepted", NOW))
                .thenReturn(Optional.of(postedAllocation("Limit erosion accepted")));

        ClaimAllocation result = service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                new AllocationErosionRequest("POSTED", "Limit erosion accepted"));

        assertEquals("POSTED", result.erosionStatus());
        assertEquals("Limit erosion accepted", result.erosionMessage());
        assertEquals(NOW, result.erosionPostedAt());
        verifyNoInteractions(outbox);
    }

    @Test
    void recordsAFailedPostingWithoutAPostingInstant() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID))
                .thenReturn(Optional.of(allocation(2024, "PRIMARY", "0.250000", "150000.0000")));
        when(repository.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID, "FAILED",
                "Limit service unavailable", null))
                .thenReturn(Optional.of(failedAllocation("Limit service unavailable")));

        ClaimAllocation result = service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                new AllocationErosionRequest("FAILED", "Limit service unavailable"));

        assertEquals("FAILED", result.erosionStatus());
        assertEquals("Limit service unavailable", result.erosionMessage());
        assertNull(result.erosionPostedAt());
    }

    @Test
    void aFailedPostingMayBeRetriedAsPosted() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID))
                .thenReturn(Optional.of(failedAllocation("Limit service unavailable")));
        when(repository.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID, "POSTED",
                "Retry accepted", NOW))
                .thenReturn(Optional.of(postedAllocation("Retry accepted")));

        ClaimAllocation result = service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                new AllocationErosionRequest("POSTED", "Retry accepted"));

        assertEquals("POSTED", result.erosionStatus());
        assertEquals(NOW, result.erosionPostedAt());
    }

    @Test
    void repeatingTheCurrentStatusIsAnIdempotentNoOp() {
        claimExists(0);
        ClaimAllocation posted = postedAllocation("Original posting");
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID))
                .thenReturn(Optional.of(posted));

        ClaimAllocation result = service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                new AllocationErosionRequest("POSTED", "A different message"));

        assertEquals(posted, result);
        verify(repository, never()).updateErosion(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsChangingAnAllocationThatIsAlreadyPosted() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID))
                .thenReturn(Optional.of(postedAllocation("Original posting")));

        ClaimConflictException error = assertThrows(ClaimConflictException.class,
                () -> service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                        new AllocationErosionRequest("FAILED", "Too late")));

        assertTrue(error.getMessage().contains("already POSTED"));
        assertTrue(error.getMessage().contains("FAILED"));
        verify(repository, never()).updateErosion(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anErosionUpdateOfAnAbsentAllocationIsNotFound() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID)).thenReturn(Optional.empty());

        assertThrows(RecordNotFoundException.class,
                () -> service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                        new AllocationErosionRequest("POSTED", null)));
    }

    @Test
    void erosionUpdatesStayBehindTheTenantWall() {
        assertThrows(ClaimNotFoundException.class,
                () -> service.updateErosion(OTHER_TENANT, CLAIM_ID, ALLOCATION_ID,
                        new AllocationErosionRequest("POSTED", null)));

        verify(repository, never()).findById(any(), any(), any());
    }

    @Test
    void rejectsAnUnknownErosionStatus() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID))
                .thenReturn(Optional.of(allocation(2024, "PRIMARY", "0.250000", "150000.0000")));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                        new AllocationErosionRequest("NOT_POSTED", null)));

        assertTrue(error.getMessage().contains("status"));
    }

    @Test
    void rejectsAnErosionMessageLongerThanFourThousandCharacters() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID))
                .thenReturn(Optional.of(allocation(2024, "PRIMARY", "0.250000", "150000.0000")));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                        new AllocationErosionRequest("FAILED", "X".repeat(4001))));

        assertTrue(error.getMessage().contains("at most 4000 characters"));
    }

    @Test
    void aConcurrentRemovalOfTheRowIsReportedAsNotFound() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, ALLOCATION_ID))
                .thenReturn(Optional.of(allocation(2024, "PRIMARY", "0.250000", "150000.0000")),
                        Optional.empty());
        when(repository.updateErosion(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThrows(RecordNotFoundException.class,
                () -> service.updateErosion(TENANT, CLAIM_ID, ALLOCATION_ID,
                        new AllocationErosionRequest("FAILED", null)));
    }

    @Test
    void rejectsAPolicyThatIsNotTheClaimsPolicy() {
        claimExists(0);

        PolicyMismatchException error = assertThrows(PolicyMismatchException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        request(OTHER_POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", null)));

        assertTrue(error.getMessage().contains("does not match the claim policy"));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), anyInt(), any(), any(),
                any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsAPercentageAboveOne() {
        claimExists(0);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        request(POLICY_ID, 2024, "PRIMARY", "1.5", "1000.00", null)));

        assertTrue(error.getMessage().contains("allocationPct"));
        verify(repository, never()).totalPct(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsAPercentageOfZeroOrLess() {
        claimExists(0);

        assertThrows(ClaimValidationException.class, () -> service.create(TENANT, CLAIM_ID,
                request(POLICY_ID, 2024, "PRIMARY", "0", "1000.00", null)));
        assertThrows(ClaimValidationException.class, () -> service.create(TENANT, CLAIM_ID,
                request(POLICY_ID, 2024, "PRIMARY", "-0.1", "1000.00", null)));
    }

    @Test
    void rejectsAPercentageThatRoundsToZero() {
        claimExists(0);

        assertThrows(ClaimValidationException.class, () -> service.create(TENANT, CLAIM_ID,
                request(POLICY_ID, 2024, "PRIMARY", "0.0000004", "1000.00", null)));
    }

    @Test
    void acceptsOneHundredPercentOfTheClaim() {
        claimExists(0);
        when(repository.totalPct(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("0.000000"));
        when(claimRepository.bumpVersion(TENANT, CLAIM_ID, 0, NOW)).thenReturn(1);
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq(POLICY_ID), isNull(), eq(2024),
                eq("PRIMARY"), eq(new BigDecimal("1.000000")), eq(new BigDecimal("1000.0000")),
                eq(NOW))).thenReturn(allocation(2024, "PRIMARY", "1.000000", "1000.0000"));
        when(repository.totals(TENANT, CLAIM_ID)).thenReturn(
                new ClaimAllocationRepository.Totals(
                        new BigDecimal("1.000000"), new BigDecimal("1000.0000")));

        service.create(TENANT, CLAIM_ID,
                request(POLICY_ID, 2024, "PRIMARY", "1", "1000.00", null));

        verify(repository).insert(any(), eq(TENANT), eq(CLAIM_ID), eq(POLICY_ID), isNull(),
                eq(2024), eq("PRIMARY"), eq(new BigDecimal("1.000000")),
                eq(new BigDecimal("1000.0000")), eq(NOW));
    }

    @Test
    void rejectsATotalPercentageAboveOne() {
        claimExists(0);
        when(repository.totalPct(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("0.800000"));

        AllocationExceedsHundredException error = assertThrows(
                AllocationExceedsHundredException.class, () -> service.create(TENANT, CLAIM_ID,
                        request(POLICY_ID, 2024, "PRIMARY", "0.5", "1000.00", null)));

        assertTrue(error.getMessage().contains("exceeds 1"));
        verify(claimRepository, never()).bumpVersion(any(), any(), anyInt(), any());
        verify(repository, never()).insert(any(), any(), any(), any(), any(), anyInt(), any(), any(),
                any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsAPolicyYearOutsideTheSupportedRange() {
        claimExists(0);

        ClaimValidationException tooEarly = assertThrows(ClaimValidationException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        request(POLICY_ID, 1899, "PRIMARY", "0.5", "1000.00", null)));
        ClaimValidationException tooLate = assertThrows(ClaimValidationException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        request(POLICY_ID, 2201, "PRIMARY", "0.5", "1000.00", null)));

        assertTrue(tooEarly.getMessage().contains("1900"));
        assertTrue(tooLate.getMessage().contains("2200"));
    }

    @Test
    void rejectsAMissingPolicyYear() {
        claimExists(0);

        assertThrows(ClaimValidationException.class, () -> service.create(TENANT, CLAIM_ID,
                request(POLICY_ID, null, "PRIMARY", "0.5", "1000.00", null)));
    }

    @Test
    void rejectsANegativeAllocatedAmount() {
        claimExists(0);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        request(POLICY_ID, 2024, "PRIMARY", "0.5", "-1.00", null)));

        assertTrue(error.getMessage().contains("allocatedAmount"));
    }

    @Test
    void rejectsALayerLongerThanThirtyTwoCharacters() {
        claimExists(0);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        request(POLICY_ID, 2024, "X".repeat(33), "0.5", "1000.00", null)));

        assertTrue(error.getMessage().contains("at most 32 characters"));
    }

    @Test
    void allocatedAmountMayExceedTheCurrentReserve() {
        claimExists(0);
        when(repository.totalPct(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("0.000000"));
        when(claimRepository.bumpVersion(TENANT, CLAIM_ID, 0, NOW)).thenReturn(1);
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq(POLICY_ID), isNull(), eq(2024),
                eq("EXCESS"), eq(new BigDecimal("0.500000")), eq(new BigDecimal("999999999.0000")),
                eq(NOW))).thenReturn(allocation(2024, "EXCESS", "0.500000", "999999999.0000"));
        when(repository.totals(TENANT, CLAIM_ID)).thenReturn(
                new ClaimAllocationRepository.Totals(
                        new BigDecimal("0.500000"), new BigDecimal("999999999.0000")));

        ClaimAllocation created = service.create(TENANT, CLAIM_ID,
                request(POLICY_ID, 2024, "EXCESS", "0.5", "999999999.00", null));

        assertEquals(new BigDecimal("999999999.0000"), created.allocatedAmount());
    }

    @Test
    void listsAllocationsOldestFirstWithTotals() {
        claimExists(0);
        List<ClaimAllocation> allocations = List.of(
                allocation(2023, "PRIMARY", "0.250000", "100000.0000"),
                allocation(2024, "EXCESS", "0.500000", "200000.0000"));
        when(repository.findAll(TENANT, CLAIM_ID)).thenReturn(allocations);

        ClaimAllocationSummary summary = service.list(TENANT, CLAIM_ID);

        assertEquals(allocations, summary.items());
        assertEquals(new BigDecimal("0.750000"), summary.totalPct());
        assertEquals(new BigDecimal("300000.0000"), summary.totalAmount());
        verifyNoInteractions(outbox);
    }

    @Test
    void listHidesAClaimFromAnotherTenant() {
        assertThrows(ClaimNotFoundException.class, () -> service.list(OTHER_TENANT, CLAIM_ID));

        verify(repository, never()).findAll(any(), any());
    }

    private void claimExists(int version) {
        when(claimRepository.findById(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(claim("REGISTERED", version)));
    }

    private static CreateClaimAllocationRequest request(UUID policyId, Integer policyYear,
                                                        String layer, String pct, String amount,
                                                        UUID limitId) {
        return new CreateClaimAllocationRequest(policyId, policyYear, layer,
                new BigDecimal(pct), new BigDecimal(amount), limitId);
    }

    private OutboxEvent capturedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> payload(OutboxEvent event) {
        return EventJson.toMap(event.payload());
    }
}
