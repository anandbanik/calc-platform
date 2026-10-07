# calc-platform: Multi-Tenant CalculateNDI API (prototype)

A working prototype of [Multi-Tenant CalculateNDI API — Low-Level Design](../Multi-Tenant%20CalculateNDI%20API%20—%20Low-Level%20Design.md).
Each tenant has its own endpoint `POST /v1/tenants/{tenantId}/calculate-ndi` and its own calculator module.
Shared code resolves the tenant, authorizes the caller and dispatches. It contains no tenant-specific branch.

## Layout

```text
calc-platform/
├── calc-spi/                  the contract: TenantCalculator, TenantContext, TenantId, TenantSettings (JDK only)
├── calc-app/                  Spring Boot service: security, interceptor, registry, service, guard, repository
│   └── src/main/resources/db/schema.sql   table + row-level security policy
├── tenants/
│   ├── tenant-uscard/         UsCardCalculator + golden tests
│   └── tenant-autofinance/    AutoFinanceCalculator + golden tests
├── docker-compose.yml         Postgres 16 initialized with schema.sql
└── scripts/mint-token.sh      dev JWT for a tenant
```

Tenant modules depend only on `calc-spi`. `calc-app` depends on them at **runtime scope**, so its source cannot name a tenant class. Calculators are discovered with `ServiceLoader`.

## Requirements

- JDK 21 or newer, Maven 3.9+
- Docker (for Postgres and the integration tests)

## Run it

```bash
docker compose up -d --wait            # Postgres on localhost:5433
mvn -DskipTests package
java -jar calc-app/target/calc-app.jar # http://localhost:8080
```

Call it:

```bash
TOKEN=$(scripts/mint-token.sh uscard)
curl -s localhost:8080/v1/tenants/uscard/calculate-ndi \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{ "input": { "monthlyNetIncome": "5200.00", "monthlyHousingCost": "1500.00", "monthlyDebtPayments": "650.00" } }'
# {"calculationId":"…","tenantId":"uscard","calculatorVersion":"uscard-1","result":{"ndi":"1850.00"},"computedAt":"…"}

TOKEN=$(scripts/mint-token.sh autofinance)
curl -s localhost:8080/v1/tenants/autofinance/calculate-ndi \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{ "input": { "annualGrossIncome": "84000.00", "monthlyObligations": "2100.00", "dependents": 2 } }'
# … "result":{"ndi":"2550.00"} …
```

`GET /v1/tenants/{tenantId}/calculate-ndi/{calculationId}` fetches a stored result. Pass `Idempotency-Key` to make retries safe, and `X-Request-Id` to correlate logs.

Environment overrides: `CALC_DB_URL`, `CALC_DB_USER`, `CALC_DB_PASSWORD`, `CALC_JWT_SECRET`.

## Test it

```bash
mvn test
```

The integration tests start their own Postgres through Testcontainers. With Colima instead of Docker Desktop, point Testcontainers at Colima's socket:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

| Test | Checks |
| --- | --- |
| `UsCardCalculatorTest`, `AutoFinanceCalculatorTest` | Golden input/output cases, settings, tenant rejections |
| `TenantIdTest`, `TenantSettingsTest` | Slug rule; settings are immutable copies |
| `TenantIsolationArchTest` | Tenants don't depend on each other or on anything but `calc-spi` and the JDK; shared code never names a tenant class |
| `TenantCalculatorRegistryTest` | Both modules discovered; duplicate tenant fails at startup |
| `CalculateNDIServiceTest` | Both worked examples; each tenant's body rejected on the other's endpoint; money must be a string |
| `TenantSettingsProviderTest` | A calculator sees only its own `tenants.<id>.*`, without `limits.*` |
| `Resilience4jTenantGuardTest` | One tenant's exhausted quota or full bulkhead does not affect another |
| `CalculateNDIApiTest` | End to end over HTTP: 200/400/401/403/404/409/422, idempotency, request id |
| `RateLimitApiTest` | 429 with `Retry-After` for one tenant while the other still gets 200 |
| `RowLevelSecurityTest` | As `calc_app`: no cross-tenant reads, counts or inserts; no rows without a tenant; no `DELETE` |

## Onboarding a tenant

1. Copy `tenants/tenant-uscard` to `tenants/tenant-<id>` and add it to `<modules>` in the root `pom.xml`.
2. Write the input/output records and the `TenantCalculator`, returning the new slug from `tenantId()`.
3. Register the class in `src/main/resources/META-INF/services/com.capitalone.calc.spi.TenantCalculator`.
4. Add golden tests.
5. Add the module to `calc-app/pom.xml` with `<scope>runtime</scope>`.
6. Add `tenants.<id>.*` (and optional `tenants.<id>.limits.*`) to `application.yml`.
7. Issue tokens with `tenant_id = <id>`.

## Prototype shortcuts and differences from the design

- **Tokens** are HS256 with a shared dev secret, so `scripts/mint-token.sh` can mint them. Production should use the identity provider's keys (`spring.security.oauth2.resourceserver.jwt.issuer-uri`).
- **Schema** is applied by Postgres's init script (compose) or Testcontainers, not by a migration tool. It also creates the `calc_app` role with a dev password.
- **`X-Request-Id`** is handled by a servlet filter that runs before security, not by the tenant interceptor as in the design sketch, so 401 responses carry it too. Ids that aren't plain tokens (`[A-Za-z0-9._-]{1,64}`) are replaced, which prevents log injection.
- **Problem `type`** values are URNs such as `urn:calc:problem:tenant-mismatch`. A `500 internal-error` type covers calculator bugs, which the design's table leaves out.
- **Invalid-input details** name the offending field (`input.monthlyNetIncome`) but never Jackson's message, which would expose tenant class names.
- **Metrics:** an `ndi.requests` timer with `tenant`, `method` and `status` tags is recorded but not exposed over HTTP. Only `/actuator/health` is public.
- **Rate limits** use a 1-second window: `requests-per-second` and `max-concurrent`, with platform defaults under `platform.limits.*`.
