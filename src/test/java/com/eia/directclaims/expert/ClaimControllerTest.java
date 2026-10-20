package com.eia.directclaims.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.eia.directclaims.expert.Fixtures.CLAIMANT_ID;
import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.assignedClaim;
import static com.eia.directclaims.expert.Fixtures.claim;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaimController.class)
class ClaimControllerTest {
    private static final String URL = "/internal/v1/claims";

    private static final String VALID_CREATE = """
            {
              "tenantId": "11111111-1111-7111-8111-111111111111",
              "policyId": "22222222-2222-7222-8222-222222222222",
              "claimantPartyId": "33333333-3333-7333-8333-333333333333",
              "lossDate": "2026-03-14",
              "reportDate": "2026-03-18",
              "causeCode": "GL",
              "claimType": "MASS_TORT",
              "reserveAmount": 125000.00,
              "reserveCurrency": "USD",
              "settlementAuthorityAmount": 500000.00,
              "settlementAuthorityCurrency": "USD"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ClaimService service;

    @Test
    void listsClaimsForTheDirectBook() throws Exception {
        when(service.list(TENANT, null, null)).thenReturn(List.of(claim("REGISTERED", 0)));

        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].claimNumber").value("CLM-2026-0000001"))
                .andExpect(jsonPath("$[0].businessBook").value("DIRECT"))
                .andExpect(jsonPath("$[0].claimType").value("BODILY_INJURY"));
    }

    @Test
    void listIncludesClaimAssignmentFields() throws Exception {
        when(service.list(TENANT, null, null)).thenReturn(List.of(assignedClaim("REGISTERED", 4)));

        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assignedAdjuster").value("adjuster@example.com"))
                .andExpect(jsonPath("$[0].assignedTeam").value("CASUALTY"))
                .andExpect(jsonPath("$[0].assignedAt").exists())
                .andExpect(jsonPath("$[0].assignedBy").value("supervisor@example.com"));
    }

    @Test
    void moneyFieldsAreSerializedAsDecimalStrings() throws Exception {
        when(service.list(TENANT, null, null)).thenReturn(List.of(claim("REGISTERED", 0)));

        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reserveAmount").isString())
                .andExpect(jsonPath("$[0].reserveAmount").value("125000.0000"))
                .andExpect(jsonPath("$[0].currentReserveAmount").isString())
                .andExpect(jsonPath("$[0].currentReserveAmount").value("125000.0000"))
                .andExpect(jsonPath("$[0].settlementAuthorityAmount").isString());
    }

    @Test
    void filtersClaimsByClaimantParty() throws Exception {
        when(service.list(TENANT, null, CLAIMANT_ID)).thenReturn(List.of(claim("REGISTERED", 0)));

        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .param("claimantPartyId", CLAIMANT_ID.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].claimNumber").value("CLM-2026-0000001"))
                .andExpect(jsonPath("$[0].claimantPartyId").value(CLAIMANT_ID.toString()));

        verify(service).list(TENANT, null, CLAIMANT_ID);
    }

    @Test
    void statusAndClaimantPartyFiltersAreCombined() throws Exception {
        when(service.list(TENANT, "OPEN", CLAIMANT_ID)).thenReturn(List.of(claim("OPEN", 1)));

        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .param("status", "OPEN")
                        .param("claimantPartyId", CLAIMANT_ID.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"));

        verify(service).list(TENANT, "OPEN", CLAIMANT_ID);
    }

    @Test
    void claimantPartyFilterStaysInsideTheBookWall() throws Exception {
        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .param("claimantPartyId", CLAIMANT_ID.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void malformedClaimantPartyReturns400WithoutTouchingTheService() throws Exception {
        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .param("claimantPartyId", "not-a-uuid")
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(service);
    }

    @Test
    void nonDirectBookReturns404WithoutTouchingTheService() throws Exception {
        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Not found"));

        verifyNoInteractions(service);
    }

    @Test
    void missingBookReturns404() throws Exception {
        mvc.perform(get(URL).param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void createsClaimForTheDirectBook() throws Exception {
        when(service.create(any(CreateClaimRequest.class))).thenReturn(claim("REGISTERED", 0));

        mvc.perform(post(URL)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimNumber").value("CLM-2026-0000001"))
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    @Test
    void negativeReserveIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post(URL)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE.replace("125000.00", "-1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("reserveAmount"));

        verifyNoInteractions(service);
    }

    @Test
    void reportDateBeforeLossDateIsRejected() throws Exception {
        when(service.create(any(CreateClaimRequest.class)))
                .thenThrow(new ClaimValidationException("reportDate must be on or after lossDate"));

        mvc.perform(post(URL)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE.replace("2026-03-18", "2026-03-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unknownClaimReturnsStructured404() throws Exception {
        when(service.get(TENANT, CLAIM_ID)).thenThrow(new ClaimNotFoundException(CLAIM_ID));

        mvc.perform(get(URL + "/" + CLAIM_ID)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value(URL + "/" + CLAIM_ID));
    }

    @Test
    void illegalStatusTransitionReturns400() throws Exception {
        when(service.changeStatus(TENANT, CLAIM_ID, "PAID", 0))
                .thenThrow(new ClaimValidationException("Illegal claim status transition REGISTERED -> PAID"));

        mvc.perform(patch(URL + "/" + CLAIM_ID + "/status")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PAID", "expectedVersion": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void closesAClaim() throws Exception {
        when(service.close(TENANT, CLAIM_ID, 5)).thenReturn(claim("CLOSED", 6));

        mvc.perform(post(URL + "/" + CLAIM_ID + "/close")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void closeOnTheWrongBookReturns404() throws Exception {
        mvc.perform(post(URL + "/" + CLAIM_ID + "/close")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }
}
