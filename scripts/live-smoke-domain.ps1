# Direct Claims second-slice live smoke through the public domain API.
#
# Prerequisite: PostgreSQL 17 with database eia_direct (postgres/postgres), the
# expert on :8087, and the domain on :8088. Each run uses a fresh tenant so it
# never touches existing data.
#
#   pwsh ./scripts/live-smoke-domain.ps1
#
# The journey is injury -> exposure -> coverage -> OPEN -> reserve history ->
# settlement -> recovery, including the negative cases for each invariant.

param(
    [string]$Domain = 'http://localhost:8088',
    [string]$Tenant = ([guid]::NewGuid().ToString())
)

$ErrorActionPreference = 'Stop'
$headers = @{
    'X-EIA-Tenant' = $Tenant
    'X-EIA-Book'   = 'DIRECT'
    'Content-Type' = 'application/json'
}
$policyId = '22222222-2222-7222-8222-222222222222'
$claimantId = '33333333-3333-7333-8333-333333333333'
$reviewerId = '44444444-4444-7444-8444-444444444444'
$releaseId = '55555555-5555-7555-8555-555555555555'

function HttpPost([string]$Path, $Body) {
    Invoke-RestMethod -Method Post -Uri "$Domain$Path" -Headers $headers -Body ($Body | ConvertTo-Json -Depth 8)
}

function HttpPatch([string]$Path, $Body) {
    Invoke-RestMethod -Method Patch -Uri "$Domain$Path" -Headers $headers -Body ($Body | ConvertTo-Json -Depth 8)
}

function HttpGet([string]$Path, [string]$Book = 'DIRECT') {
    $requestHeaders = @{ 'X-EIA-Tenant' = $Tenant; 'X-EIA-Book' = $Book }
    Invoke-RestMethod -Method Get -Uri "$Domain$Path" -Headers $requestHeaders
}

function ExpectStatus([int]$Expected, [scriptblock]$Call, [string]$Label) {
    try {
        & $Call | Out-Null
        if ($Expected -ne 200 -and $Expected -ne 201) {
            throw "$Label expected HTTP $Expected but the call succeeded"
        }
    } catch {
        $code = 0
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $code = [int]$_.Exception.Response.StatusCode
        }
        if ($code -ne $Expected) {
            throw "$Label expected HTTP $Expected, got $code : $($_.ErrorDetails.Message)"
        }
    }
    Write-Host ("  ok: {0} -> HTTP {1}" -f $Label, $Expected)
}

Write-Host "Live smoke tenant $Tenant"

$claim = HttpPost '/api/v1/claims' @{
    policyId                   = $policyId
    claimantPartyId            = $claimantId
    lossDate                   = '2026-03-14'
    reportDate                 = '2026-03-18'
    causeCode                  = 'GL'
    claimType                  = 'MASS_TORT'
    reserveAmount              = '0.00'
    reserveCurrency            = 'USD'
    settlementAuthorityAmount  = '500000.00'
    settlementAuthorityCurrency = 'USD'
}
$claimId = $claim.id
Write-Host "  claim $($claim.claimNumber) ($claimId)"

ExpectStatus 400 { HttpPatch "/api/v1/claims/$claimId/status" @{ status = 'OPEN' } } 'OPEN before coverage'
ExpectStatus 400 { HttpPost "/api/v1/claims/$claimId/coverage" @{ position = 'COVERED'; basis = 'Premature'; decisionDate = '2026-03-18'; reviewerPartyId = $reviewerId } } 'coverage before facts'

$injury = HttpPost "/api/v1/claims/$claimId/injuries" @{
    claimantPartyId = $claimantId
    injuryType      = 'MESOTHELIOMA'
    bodyPart        = 'LUNG'
    diagnosisCodes  = @('C45.0', 'C45.9')
    severity        = 'SEVERE'
    diagnosisDate   = '2026-01-10'
}
Write-Host "  injury version $($injury.version)"

$exposure = HttpPost "/api/v1/claims/$claimId/exposures" @{
    claimantPartyId = $claimantId
    exposureType    = 'SUBSTANCE'
    substanceName   = 'Chrysotile asbestos'
    siteName        = 'Akron plant'
    jurisdiction    = 'US-OH'
    exposureFrom    = '2015-01-01'
    exposureTo      = '2020-12-31'
    intensity       = 'HIGH'
}
Write-Host "  exposure version $($exposure.version)"

ExpectStatus 400 { HttpPost "/api/v1/claims/$claimId/exposures" @{ claimantPartyId = $claimantId; exposureType = 'SUBSTANCE'; jurisdiction = 'US-OH'; exposureFrom = '2015-01-01'; exposureTo = '2026-04-01' } } 'exposure after report date'

$coverage = HttpPost "/api/v1/claims/$claimId/coverage" @{
    position         = 'COVERED'
    basis            = 'Policy year 2024 responds to the continuous trigger'
    policyProvisions = 'Coverage A; occurrence trigger'
    allocationMethod = 'PRO_RATA_TIME'
    allocationPct    = '0.25'
    decisionDate     = '2026-03-18'
    reviewerPartyId  = $reviewerId
}
Write-Host "  coverage $($coverage.position)"

$open = HttpPatch "/api/v1/claims/$claimId/status" @{ status = 'OPEN' }
$version = (HttpGet "/api/v1/claims/$claimId").version
Write-Host "  claim version after OPEN: $version"

ExpectStatus 400 { HttpPost "/api/v1/claims/$claimId/reserves" @{ component = 'INDEMNITY'; amount = '-1.00'; reasonCode = 'INITIAL'; expectedVersion = $version } } 'negative reserve'
ExpectStatus 400 { HttpPost "/api/v1/claims/$claimId/reserves" @{ component = 'INDEMNITY'; amount = '1000.00'; expectedVersion = $version } } 'reserve without reason'
ExpectStatus 409 { HttpPost "/api/v1/claims/$claimId/reserves" @{ component = 'INDEMNITY'; amount = '1000.00'; reasonCode = 'INITIAL'; expectedVersion = ($version + 10) } } 'stale reserve version'

$indemnity = HttpPost "/api/v1/claims/$claimId/reserves" @{
    component = 'INDEMNITY'; amount = '100000.00'; currency = 'USD'; effectiveDate = '2026-03-18'
    reasonCode = 'INITIAL'; reasonText = 'Live smoke'; authority = 'Manager'; expectedVersion = $version
}
$defense = HttpPost "/api/v1/claims/$claimId/reserves" @{
    component = 'DEFENSE'; amount = '25000.00'; currency = 'USD'; effectiveDate = '2026-03-18'
    reasonCode = 'DEVELOPMENT'; reasonText = 'Live smoke'; authority = 'Manager'; expectedVersion = $version + 1
}
Write-Host "  reserve sequence $($indemnity.sequence) / $($defense.sequence)"

$reserves = HttpGet "/api/v1/claims/$claimId/reserves"
if ($reserves.totalCurrentAmount -ne '125000.0000') {
    throw "expected derived current reserve 125000.0000, got $($reserves.totalCurrentAmount)"
}
Write-Host "  derived current reserve $($reserves.totalCurrentAmount) from $($reserves.history.Count) rows"

$settlement = HttpPost "/api/v1/claims/$claimId/settlements" @{
    amount = '60000.00'; currency = 'USD'; structure = 'LUMP_SUM'; releaseScope = 'FULL'
    paymentInstructions = 'Wire to claimant trust account'
}
ExpectStatus 400 { HttpPost "/api/v1/claims/$claimId/settlements/$($settlement.id)/approve" @{ approvalAuthority = ''; authorizedBy = $reviewerId; expectedVersion = $settlement.version } } 'approval without authority'
$authorized = HttpPost "/api/v1/claims/$claimId/settlements/$($settlement.id)/approve" @{
    approvalAuthority = 'Claims Committee'; authorizedBy = $reviewerId; expectedVersion = $settlement.version
}
$executed = HttpPost "/api/v1/claims/$claimId/settlements/$($settlement.id)/execute" @{
    releaseDocumentId = $releaseId; paymentInstructions = 'Wire funds per release'; expectedVersion = $authorized.version
}
Write-Host "  settlement $($executed.status)"

$recovery = HttpPost "/api/v1/claims/$claimId/recoveries" @{
    recoveryType = 'SUBROGATION'; amount = '20000.00'; currency = 'USD'; source = 'Co-defendant Acme'
    status = 'RECEIVED'; recoveryDate = '2026-05-01'
}
Write-Host "  recovery $($recovery.status)"
ExpectStatus 400 { HttpPost "/api/v1/claims/$claimId/recoveries" @{ recoveryType = 'SUBROGATION'; amount = '50000.00'; currency = 'USD'; source = 'Co-defendant Acme'; status = 'RECEIVED'; recoveryDate = '2026-05-01' } } 'recovery above paid loss'

$final = HttpGet "/api/v1/claims/$claimId"
if ($final.currentReserveAmount -ne '125000.0000') {
    throw "expected claim current reserve 125000.0000, got $($final.currentReserveAmount)"
}

ExpectStatus 404 { HttpGet "/api/v1/claims/$claimId" 'ASSUMED' } 'assumed-book access'

Write-Host "Live smoke passed for claim $($claim.claimNumber)"
