package com.eia.directclaims.expert;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static com.eia.directclaims.expert.Fixtures.ALLOCATION_ID;
import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.LIMIT_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.allocation;
import static com.eia.directclaims.expert.Fixtures.failedAllocation;
import static com.eia.directclaims.expert.Fixtures.postedAllocation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaimAllocationController.class)
class ClaimAllocationControllerTest {
    private static final String BASE = "/api/v1/claims/" + CLAIM_ID + "/allocations";
    private static final String EROSION = BASE + "/" + ALLOCATION_ID + "/erosion";

    private static final String VALID_CREATE = """
            {
              "policyId": "22222222-2222-7222-8222-222222222222",
              "policyYear": 2024,
              "layer": "PRIMARY",
              "allocationPct": "0.25",
              "allocatedAmount": "100000.00"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ClaimAllocationService service;

    @Test
    void listsAllocationsWithSummaryTotals() throws Exception {
        when(service.list(TENANT, CLAIM_ID)).thenReturn(new ClaimAllocationSummary(
                List.of(allocation(2024, "PRIMARY", "0.250000", "100000.0000")),
                new BigDecimal("0.250000"), new BigDecimal("100000.0000")));

        mvc.perform(get(BASE)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d3b"))
                .andExpect(jsonPath("$.items[0].policyYear").value(2024))
                .andExpect(jsonPath("$.items[0].layer").value("PRIMARY"))
                .andExpect(jsonPath("$.items[0].allocationPct").isString())
                .andExpect(jsonPath("$.items[0].allocationPct").value("0.250000"))
                .andExpect(jsonPath("$.items[0].allocatedAmount").isString())
                .andExpect(jsonPath("$.items[0].allocatedAmount").value("100000.0000"))
                .andExpect(jsonPath("$.items[0].limitId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].erosionStatus").value("NOT_POSTED"))
                .andExpect(jsonPath("$.items[0].erosionMessage").value(nullValue()))
                .andExpect(jsonPath("$.items[0].erosionPostedAt").value(nullValue()))
                .andExpect(jsonPath("$.totalPct").value("0.250000"))
                .andExpect(jsonPath("$.totalAmount").value("100000.0000"));
    }

    @Test
    void listAcceptsTheTenantHeaderInsteadOfTheQueryParameter() throws Exception {
        when(service.list(TENANT, CLAIM_ID)).thenReturn(new ClaimAllocationSummary(
                List.of(), new BigDecimal("0.000000"), new BigDecimal("0.0000")));

        mvc.perform(get(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalPct").value("0.000000"))
                .andExpect(jsonPath("$.totalAmount").value("0.0000"));
    }

    @Test
    void createsAnAllocationWithoutALimitReference() throws Exception {
        when(service.create(eq(TENANT), eq(CLAIM_ID), any(CreateClaimAllocationRequest.class)))
                .thenReturn(allocation(2024, "PRIMARY", "0.250000", "100000.0000"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyYear").value(2024))
                .andExpect(jsonPath("$.layer").value("PRIMARY"))
                .andExpect(jsonPath("$.allocationPct").value("0.250000"))
                .andExpect(jsonPath("$.allocatedAmount").value("100000.0000"))
                .andExpect(jsonPath("$.limitId").value(nullValue()))
                .andExpect(jsonPath("$.erosionStatus").value("NOT_POSTED"));

        assertThat(capturedCreate().limitId()).isNull();
    }

    @Test
    void createAcceptsAnOptionalLimitReference() throws Exception {
        when(service.create(eq(TENANT), eq(CLAIM_ID), any(CreateClaimAllocationRequest.class)))
                .thenReturn(allocation(LIMIT_ID, 2024, "PRIMARY", "0.250000", "100000.0000"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyId": "22222222-2222-7222-8222-222222222222",
                                  "policyYear": 2024,
                                  "layer": "PRIMARY",
                                  "allocationPct": "0.25",
                                  "allocatedAmount": "100000.00",
                                  "limitId": "66666666-6666-7666-8666-666666666666"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.limitId").value(LIMIT_ID.toString()))
                .andExpect(jsonPath("$.erosionStatus").value("NOT_POSTED"));

        assertThat(capturedCreate().limitId()).isEqualTo(LIMIT_ID);
    }

    @Test
    void internalAliasServesTheDomainContract() throws Exception {
        String internal = "/internal/v1/claims/" + CLAIM_ID + "/allocations";
        when(service.list(TENANT, CLAIM_ID)).thenReturn(new ClaimAllocationSummary(
                List.of(allocation(2024, "PRIMARY", "0.250000", "100000.0000")),
                new BigDecimal("0.250000"), new BigDecimal("100000.0000")));
        when(service.create(eq(TENANT), eq(CLAIM_ID), any(CreateClaimAllocationRequest.class)))
                .thenReturn(allocation(2024, "PRIMARY", "0.250000", "100000.0000"));

        mvc.perform(get(internal)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPct").value("0.250000"));

        mvc.perform(post(internal)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyYear").value(2024));
    }

    @Test
    void marksAnAllocationErosionPosted() throws Exception {
        when(service.updateErosion(eq(TENANT), eq(CLAIM_ID), eq(ALLOCATION_ID),
                any(AllocationErosionRequest.class)))
                .thenReturn(postedAllocation("Limit erosion accepted"));

        mvc.perform(patch(EROSION)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "POSTED",
                                  "message": "Limit erosion accepted"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erosionStatus").value("POSTED"))
                .andExpect(jsonPath("$.erosionMessage").value("Limit erosion accepted"))
                .andExpect(jsonPath("$.erosionPostedAt").value("2026-03-18T09:00:00Z"));

        ArgumentCaptor<AllocationErosionRequest> captor =
                ArgumentCaptor.forClass(AllocationErosionRequest.class);
        verify(service).updateErosion(eq(TENANT), eq(CLAIM_ID), eq(ALLOCATION_ID), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("POSTED");
        assertThat(captor.getValue().message()).isEqualTo("Limit erosion accepted");
    }

    @Test
    void erosionIsAlsoReachableThroughTheInternalAlias() throws Exception {
        when(service.updateErosion(eq(TENANT), eq(CLAIM_ID), eq(ALLOCATION_ID),
                any(AllocationErosionRequest.class)))
                .thenReturn(postedAllocation("internal posting"));

        mvc.perform(patch("/internal/v1/claims/" + CLAIM_ID + "/allocations/"
                        + ALLOCATION_ID + "/erosion")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "POSTED", "message": "internal posting"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erosionStatus").value("POSTED"))
                .andExpect(jsonPath("$.erosionMessage").value("internal posting"));
    }

    @Test
    void recordsAFailedErosionWithoutAPostedAt() throws Exception {
        when(service.updateErosion(eq(TENANT), eq(CLAIM_ID), eq(ALLOCATION_ID),
                any(AllocationErosionRequest.class)))
                .thenReturn(failedAllocation("Limit service unavailable"));

        mvc.perform(patch(EROSION)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "FAILED",
                                  "message": "Limit service unavailable"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erosionStatus").value("FAILED"))
                .andExpect(jsonPath("$.erosionMessage").value("Limit service unavailable"))
                .andExpect(jsonPath("$.erosionPostedAt").value(nullValue()));
    }

    @Test
    void repeatingTheCurrentErosionStatusIsA200NoOp() throws Exception {
        when(service.updateErosion(eq(TENANT), eq(CLAIM_ID), eq(ALLOCATION_ID),
                any(AllocationErosionRequest.class)))
                .thenReturn(postedAllocation("Original posting"));

        mvc.perform(patch(EROSION)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "POSTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erosionStatus").value("POSTED"))
                .andExpect(jsonPath("$.erosionMessage").value("Original posting"));
    }

    @Test
    void changingAnAlreadyPostedAllocationIs409() throws Exception {
        when(service.updateErosion(eq(TENANT), eq(CLAIM_ID), eq(ALLOCATION_ID),
                any(AllocationErosionRequest.class)))
                .thenThrow(new ClaimConflictException(
                        "Claim allocation " + ALLOCATION_ID
                                + " is already POSTED and cannot be changed to FAILED"));

        mvc.perform(patch(EROSION)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "FAILED", "message": "Too late"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void anAbsentAllocationIs404() throws Exception {
        when(service.updateErosion(eq(TENANT), eq(CLAIM_ID), eq(ALLOCATION_ID),
                any(AllocationErosionRequest.class)))
                .thenThrow(RecordNotFoundException.of("Claim allocation", ALLOCATION_ID));

        mvc.perform(patch(EROSION)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "POSTED"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aMissingErosionStatusIs400ValidationError() throws Exception {
        mvc.perform(patch(EROSION)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "no status"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(service);
    }

    @Test
    void policyMismatchIs400WithTheStableCode() throws Exception {
        when(service.create(eq(TENANT), eq(CLAIM_ID), any(CreateClaimAllocationRequest.class)))
                .thenThrow(new PolicyMismatchException(
                        "policyId does not match the claim policy"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POLICY_MISMATCH"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void exceedingOneHundredPercentIs409WithTheStableCode() throws Exception {
        when(service.create(eq(TENANT), eq(CLAIM_ID), any(CreateClaimAllocationRequest.class)))
                .thenThrow(new AllocationExceedsHundredException(
                        "Allocations for claim CLM-2026-0000001 would total 1.200000, which exceeds 1"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_100"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void anInvalidPolicyYearIs400ValidationError() throws Exception {
        when(service.create(eq(TENANT), eq(CLAIM_ID), any(CreateClaimAllocationRequest.class)))
                .thenThrow(new ClaimValidationException(
                        "policyYear must be between 1900 and 2200"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void allocationRoutesStayInsideTheBookWall() throws Exception {
        mvc.perform(get(BASE)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mvc.perform(patch(EROSION)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "POSTED"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void missingBookIs404() throws Exception {
        mvc.perform(get(BASE).param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    private CreateClaimAllocationRequest capturedCreate() {
        ArgumentCaptor<CreateClaimAllocationRequest> captor =
                ArgumentCaptor.forClass(CreateClaimAllocationRequest.class);
        verify(service).create(eq(TENANT), eq(CLAIM_ID), captor.capture());
        return captor.getValue();
    }
}
