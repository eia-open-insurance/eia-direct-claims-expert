package com.eia.directclaims.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaimController.class)
class ClaimControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final String URL = "/internal/v1/claims";

    private static final String VALID_CREATE = """
            {
              "tenantId": "11111111-1111-7111-8111-111111111111",
              "policyId": "22222222-2222-7222-8222-222222222222",
              "claimantPartyId": "33333333-3333-7333-8333-333333333333",
              "lossDate": "2026-03-14",
              "reportDate": "2026-03-18",
              "causeCode": "GL",
              "reserveAmount": 125000.00,
              "reserveCurrency": "USD"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ClaimService service;

    @Test
    void listsClaimsForTheDirectBook() throws Exception {
        when(service.list(TENANT, null)).thenReturn(List.of(claim("REGISTERED", 0)));

        mvc.perform(get(URL)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].claimNumber").value("CLM-2026-0000001"))
                .andExpect(jsonPath("$[0].businessBook").value("DIRECT"));
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

    private static Claim claim(String status, int version) {
        return new Claim(
                CLAIM_ID,
                TENANT,
                "DIRECT",
                "CLM-2026-0000001",
                POLICY_ID,
                CLAIMANT_ID,
                status,
                LocalDate.of(2026, 3, 14),
                LocalDate.of(2026, 3, 18),
                "GL",
                new BigDecimal("125000.0000"),
                "USD",
                version,
                Instant.parse("2026-03-18T09:00:00Z"),
                Instant.parse("2026-03-18T09:00:00Z"));
    }
}
