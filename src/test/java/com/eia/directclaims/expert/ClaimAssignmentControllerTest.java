package com.eia.directclaims.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.assignedClaim;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaimAssignmentController.class)
class ClaimAssignmentControllerTest {
    private static final String PUBLIC_URL = "/api/v1/claims/" + CLAIM_ID + "/assignment";
    private static final String INTERNAL_URL = "/internal/v1/claims/" + CLAIM_ID + "/assignment";
    private static final String VALID_BODY = """
            {
              "assignedAdjuster": "adjuster@example.com",
              "assignedTeam": "CASUALTY",
              "expectedVersion": 3
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ClaimService service;

    @Test
    void assignsThroughThePublicRouteAndPassesTheActor() throws Exception {
        when(service.assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class),
                eq("supervisor@example.com"))).thenReturn(assignedClaim("REGISTERED", 4));

        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .header("X-EIA-Actor", "supervisor@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdjuster").value("adjuster@example.com"))
                .andExpect(jsonPath("$.assignedTeam").value("CASUALTY"))
                .andExpect(jsonPath("$.assignedAt").exists())
                .andExpect(jsonPath("$.assignedBy").value("supervisor@example.com"))
                .andExpect(jsonPath("$.version").value(4));

        verify(service).assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class),
                eq("supervisor@example.com"));
    }

    @Test
    void theRemovedUserAliasHeaderIsIgnored() throws Exception {
        when(service.assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class),
                isNull())).thenReturn(assignedClaim("REGISTERED", 4));

        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .header("X-EIA-User", "legacy@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());

        verify(service).assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class),
                isNull());
    }

    @Test
    void actorHeaderAloneIsAccepted() throws Exception {
        when(service.assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class),
                eq("supervisor@example.com"))).thenReturn(assignedClaim("REGISTERED", 4));

        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .header("X-EIA-Actor", "supervisor@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedBy").value("supervisor@example.com"));

        verify(service).assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class),
                eq("supervisor@example.com"));
    }

    @Test
    void supportsTheInternalAliasWithoutAnActor() throws Exception {
        when(service.assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class),
                isNull())).thenReturn(assignedClaim("REGISTERED", 4));

        mvc.perform(patch(INTERNAL_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdjuster").value("adjuster@example.com"));
    }

    @Test
    void rejectsABlankAdjusterBeforeCallingTheService() throws Exception {
        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignedAdjuster": " ", "assignedTeam": "CASUALTY", "expectedVersion": 3}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("assignedAdjuster"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsAnAdjusterLongerThan128Characters() throws Exception {
        String body = "{\"assignedAdjuster\":\"" + "a".repeat(129)
                + "\",\"expectedVersion\":3}";

        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("assignedAdjuster"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsATeamLongerThan64Characters() throws Exception {
        String body = "{\"assignedAdjuster\":\"adjuster@example.com\",\"assignedTeam\":\""
                + "t".repeat(65) + "\",\"expectedVersion\":3}";

        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("assignedTeam"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsAMissingExpectedVersion() throws Exception {
        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedAdjuster\":\"adjuster@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("expectedVersion"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsANegativeExpectedVersionAsValidationNotConflict() throws Exception {
        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignedAdjuster": "adjuster@example.com", "expectedVersion": -1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("expectedVersion"));

        verifyNoInteractions(service);
    }

    @Test
    void staleClaimVersionIs409() throws Exception {
        when(service.assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class), any()))
                .thenThrow(new ClaimConflictException(
                        "Claim version 2 does not match current version 3"));

        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void unknownClaimIs404() throws Exception {
        when(service.assign(eq(TENANT), eq(CLAIM_ID), any(ClaimAssignmentRequest.class), any()))
                .thenThrow(new ClaimNotFoundException(CLAIM_ID));

        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void nonDirectBookIs404WithoutCallingTheService() throws Exception {
        mvc.perform(patch(PUBLIC_URL)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }
}
