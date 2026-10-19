package com.eia.directclaims.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live smoke against a real PostgreSQL database (SPEC 16.9.6 component and
 * integration verification). Run with {@code .\gradlew.bat test -Plive}; the
 * normal build excludes the {@code live} tag.
 *
 * <p>One claim travels injury -> exposure -> coverage -> reserve -> settlement
 * -> recovery, and the test asserts the invariants that require the database:
 * derived reserve history, authority limits, and recovery capping.</p>
 */
@Tag("live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectClaimsLiveSmokeTest {
    private static final UUID TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID REVIEWER_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID RELEASE_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM claim WHERE tenant_id = ?", TENANT);
    }

    @Test
    void oneClaimTravelsInjuryCoverageReserveSettlementAndRecovery() throws Exception {
        JsonNode claim = json(post("/internal/v1/claims", claimBody()));
        UUID claimId = UUID.fromString(claim.get("id").asText());
        assertEquals("MASS_TORT", claim.get("claimType").asText());
        assertEquals("0.0000", claim.get("currentReserveAmount").asText());

        // Coverage is required before OPEN and cannot be accepted without facts.
        assertEquals(400, patch("/internal/v1/claims/" + claimId + "/status",
                Map.of("status", "OPEN")).getStatusCode().value());
        assertEquals(400, post("/internal/v1/claims/" + claimId + "/coverage",
                coverageBody("COVERED")).getStatusCode().value());

        ResponseEntity<String> injury = post("/internal/v1/claims/" + claimId + "/injuries",
                injuryBody());
        assertEquals(201, injury.getStatusCode().value(), injury.getBody());
        assertEquals(1, json(injury).get("version").asInt());

        ResponseEntity<String> exposure = post("/internal/v1/claims/" + claimId + "/exposures",
                exposureBody("2020-12-31"));
        assertEquals(201, exposure.getStatusCode().value(), exposure.getBody());

        // Exposure dates cannot be after the report date.
        assertEquals(400, post("/internal/v1/claims/" + claimId + "/exposures",
                exposureBody("2026-04-01")).getStatusCode().value());

        ResponseEntity<String> coverage = post("/internal/v1/claims/" + claimId + "/coverage",
                coverageBody("COVERED"));
        assertEquals(201, coverage.getStatusCode().value(), coverage.getBody());
        assertEquals("COVERED", json(coverage).get("position").asText());

        ResponseEntity<String> open = patch("/internal/v1/claims/" + claimId + "/status",
                Map.of("status", "OPEN"));
        assertEquals(200, open.getStatusCode().value(), open.getBody());
        int version = json(get("/internal/v1/claims/" + claimId)).get("version").asInt();

        // Reserve history: negative amount, missing reason, stale version.
        assertEquals(400, post("/internal/v1/claims/" + claimId + "/reserves",
                reserveBody("INDEMNITY", "-1.00", "INITIAL", "Manager", version))
                .getStatusCode().value());
        assertEquals(400, post("/internal/v1/claims/" + claimId + "/reserves",
                reserveBody("INDEMNITY", "1000.00", null, "Manager", version))
                .getStatusCode().value());
        assertEquals(409, post("/internal/v1/claims/" + claimId + "/reserves",
                reserveBody("INDEMNITY", "1000.00", "INITIAL", "Manager", version + 10))
                .getStatusCode().value());

        ResponseEntity<String> indemnity = post("/internal/v1/claims/" + claimId + "/reserves",
                reserveBody("INDEMNITY", "100000.00", "INITIAL", "Manager", version));
        assertEquals(201, indemnity.getStatusCode().value(), indemnity.getBody());
        assertEquals("100000.0000", json(indemnity).get("amount").asText());

        ResponseEntity<String> defense = post("/internal/v1/claims/" + claimId + "/reserves",
                reserveBody("DEFENSE", "25000.00", "DEVELOPMENT", "Manager", version + 1));
        assertEquals(201, defense.getStatusCode().value(), defense.getBody());

        JsonNode reserves = json(get("/internal/v1/claims/" + claimId + "/reserves"));
        assertEquals("125000.0000", reserves.get("totalCurrentAmount").asText());
        assertEquals(2, reserves.get("history").size());
        assertEquals("125000.0000", json(get("/internal/v1/claims/" + claimId))
                .get("currentReserveAmount").asText());

        // Settlement: proposal, authority, execution, then recovery cap.
        ResponseEntity<String> settlement = post("/internal/v1/claims/" + claimId + "/settlements",
                settlementBody("60000.00"));
        assertEquals(201, settlement.getStatusCode().value(), settlement.getBody());
        UUID settlementId = UUID.fromString(json(settlement).get("id").asText());

        assertEquals(400, post("/internal/v1/claims/" + claimId + "/settlements/" + settlementId
                + "/approve", approvalBody("", 1)).getStatusCode().value());
        assertEquals(400, post("/internal/v1/claims/" + claimId + "/settlements/" + settlementId
                + "/execute", executionBody(1)).getStatusCode().value());

        ResponseEntity<String> authorized = post("/internal/v1/claims/" + claimId + "/settlements/"
                + settlementId + "/approve", approvalBody("Claims Committee", 1));
        assertEquals(200, authorized.getStatusCode().value(), authorized.getBody());
        assertEquals("AUTHORIZED", json(authorized).get("status").asText());

        ResponseEntity<String> executed = post("/internal/v1/claims/" + claimId + "/settlements/"
                + settlementId + "/execute", executionBody(2));
        assertEquals(200, executed.getStatusCode().value(), executed.getBody());
        assertEquals("EXECUTED", json(executed).get("status").asText());

        ResponseEntity<String> recovery = post("/internal/v1/claims/" + claimId + "/recoveries",
                recoveryBody("20000.00"));
        assertEquals(201, recovery.getStatusCode().value(), recovery.getBody());
        assertEquals("RECEIVED", json(recovery).get("status").asText());

        ResponseEntity<String> excess = post("/internal/v1/claims/" + claimId + "/recoveries",
                recoveryBody("50000.00"));
        assertEquals(400, excess.getStatusCode().value(), excess.getBody());
        assertTrue(excess.getBody().contains("paid loss"), excess.getBody());

        // Real PostgreSQL rows back the derived read model.
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM claim_reserve_history WHERE claim_id = ?
                """, Integer.class, claimId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM claim_injury WHERE claim_id = ?
                """, Integer.class, claimId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM claim_exposure WHERE claim_id = ?
                """, Integer.class, claimId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM claim_coverage_decision WHERE claim_id = ? AND status = 'FINAL'
                """, Integer.class, claimId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM claim_settlement WHERE claim_id = ? AND status = 'EXECUTED'
                """, Integer.class, claimId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM claim_recovery WHERE claim_id = ? AND status = 'RECEIVED'
                """, Integer.class, claimId));

        // The DIRECT wall still hides the claim from the assumed book.
        assertEquals(404, get("/internal/v1/claims/" + claimId, "ASSUMED").getStatusCode().value());
    }

    private Map<String, Object> claimBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", TENANT.toString());
        body.put("policyId", POLICY_ID.toString());
        body.put("claimantPartyId", CLAIMANT_ID.toString());
        body.put("lossDate", "2026-03-14");
        body.put("reportDate", "2026-03-18");
        body.put("causeCode", "GL");
        body.put("claimType", "MASS_TORT");
        body.put("reserveAmount", "0.00");
        body.put("reserveCurrency", "USD");
        body.put("settlementAuthorityAmount", "500000.00");
        body.put("settlementAuthorityCurrency", "USD");
        return body;
    }

    private Map<String, Object> injuryBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("claimantPartyId", CLAIMANT_ID.toString());
        body.put("injuryType", "MESOTHELIOMA");
        body.put("bodyPart", "LUNG");
        body.put("diagnosisCodes", java.util.List.of("C45.0", "C45.9"));
        body.put("description", "Pleural mesothelioma");
        body.put("severity", "SEVERE");
        body.put("diagnosisDate", "2026-01-10");
        return body;
    }

    private Map<String, Object> exposureBody(String toDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("claimantPartyId", CLAIMANT_ID.toString());
        body.put("exposureType", "SUBSTANCE");
        body.put("substanceName", "Chrysotile asbestos");
        body.put("siteName", "Akron plant");
        body.put("jurisdiction", "US-OH");
        body.put("exposureFrom", "2015-01-01");
        body.put("exposureTo", toDate);
        body.put("intensity", "HIGH");
        return body;
    }

    private Map<String, Object> coverageBody(String position) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("position", position);
        body.put("basis", "Policy year 2024 responds to the continuous trigger");
        body.put("policyProvisions", "Coverage A; occurrence trigger");
        body.put("allocationMethod", "PRO_RATA_TIME");
        body.put("allocationPct", "0.25");
        body.put("decisionDate", "2026-03-18");
        body.put("reviewerPartyId", REVIEWER_ID.toString());
        return body;
    }

    private Map<String, Object> reserveBody(String component, String amount, String reason,
                                            String authority, int expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("component", component);
        body.put("amount", amount);
        body.put("currency", "USD");
        body.put("effectiveDate", "2026-03-18");
        body.put("reasonCode", reason);
        body.put("reasonText", "Live smoke reserve");
        body.put("authority", authority);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private Map<String, Object> settlementBody(String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", "USD");
        body.put("structure", "LUMP_SUM");
        body.put("releaseScope", "FULL");
        body.put("paymentInstructions", "Wire to claimant trust account");
        return body;
    }

    private Map<String, Object> approvalBody(String authority, int expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalAuthority", authority);
        body.put("authorizedBy", REVIEWER_ID.toString());
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private Map<String, Object> executionBody(int expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("releaseDocumentId", RELEASE_ID.toString());
        body.put("paymentInstructions", "Wire funds per release");
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private Map<String, Object> recoveryBody(String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recoveryType", "SUBROGATION");
        body.put("amount", amount);
        body.put("currency", "USD");
        body.put("source", "Co-defendant Acme");
        body.put("status", "RECEIVED");
        body.put("recoveryDate", "2026-05-01");
        return body;
    }

    private ResponseEntity<String> get(String path) {
        return get(path, "DIRECT");
    }

    private ResponseEntity<String> get(String path, String book) {
        String separator = path.contains("?") ? "&" : "?";
        return rest.exchange(path + separator + "tenantId=" + TENANT, HttpMethod.GET,
                new HttpEntity<>(headers(book)), String.class);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers("DIRECT")),
                String.class);
    }

    private ResponseEntity<String> patch(String path, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers("DIRECT")),
                String.class);
    }

    private HttpHeaders headers(String book) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-EIA-Book", book);
        headers.set("X-EIA-Tenant", TENANT.toString());
        return headers;
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }
}
