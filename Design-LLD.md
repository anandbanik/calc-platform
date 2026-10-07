# Multi-Tenant CalculateNDI API — Low-Level Design

Oct 2, 2026 · @Anand · revised Oct 7, 2026

> **Oct 7 revision.** The web layer, the guard and the ArchUnit rules are now built and under test,
> so what were sketches below are the running code. Two things changed in the process: admission
> control moved from around the calculator to around the whole handler, and explicit size and time
> budgets were added. **Noisy neighbours** records what those bound and what they still do not.

## Overview

Each tenant gets its own endpoint, `POST /v1/tenants/{tenantId}/calculate-ndi`, and its own calculator class behind one shared interface. Shared code resolves the tenant, authorizes the caller and dispatches; it contains no tenant-specific logic and no `if (tenant == ...)` branch. Each tenant's logic lives in its own build module, which cannot import another tenant's code.

Assumptions this design rests on:

- **Stack:** Java 21 and Spring Boot 3 for the code sketches. The pattern carries to any language with interfaces and modules.
- **Tenants are code:** each tenant's calculator ships with the service and is known at deploy time.
- **Isolation is logical:** one deployment and one database, separated by code boundaries, tenant-scoped data access and per-tenant runtime limits.
- **Calculations are synchronous:** short and CPU-bound, so there is no job or polling API.
- **Example tenants:** `uscard` and `autofinance` stand in for the real tenants.
- **NDI:** read here as net disposable income. Both NDI formulas are placeholders for the real rules.

## Isolation model

Isolation is enforced at seven layers, so a mistake at one layer is caught by the next.

| Layer | Shared or per-tenant | How isolation is enforced |
| --- | --- | --- |
| Routing | Shared route template, one URL per tenant | Tenant id is read from the path once, by the interceptor only |
| Identity | Shared | Token's `tenant_id` claim must equal the path tenant, else 403 |
| NDI calculation logic | Per-tenant module | Each module implements `TenantCalculator`; modules cannot import each other |
| Input and output schema | Per-tenant | Each module owns its typed input and output records |
| Configuration | Per-tenant | Platform hands a calculator only its own `tenants.<id>.*` settings |
| Data | Shared tables | `tenant_id` on every row, enforced by Postgres row-level security |
| Runtime capacity | Per-tenant | Separate rate limiter, bulkhead and statement timeout per tenant |

Six rules follow from this:

1. **Resolve once.** Tenant identity is established at the edge and is immutable for the request.
2. **Look up, never branch.** Shared code finds a calculator by tenant id. It never contains `if`, `switch` or config flags keyed on a specific tenant.
3. **Depend on the contract only.** A tenant module depends on `calc-spi` and nothing else in the codebase.
4. **Keep calculators pure.** A calculator is stateless: input and settings in, result out. No database, HTTP or static mutable state.
5. **Scope every data access.** Every query, cache key and idempotency key carries the tenant id.
6. **Contain failure.** One tenant's load, slowness or bug cannot use another tenant's capacity.

## API contract

Every tenant has the same two endpoints under its own path. The envelope is shared; the `input` and `result` bodies are defined by the tenant's module.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/tenants/{tenantId}/calculate-ndi` | Run the tenant's NDI calculation and store the result |
| GET | `/v1/tenants/{tenantId}/calculate-ndi/{calculationId}` | Fetch a stored result |

`tenantId` is a lowercase slug matching `[a-z][a-z0-9-]{1,30}`.

### Headers

| Header | Required | Meaning |
| --- | --- | --- |
| `Authorization: Bearer <JWT>` | Yes | Token must carry a `tenant_id` claim equal to the path tenant |
| `Idempotency-Key` | No | Retries with the same key and body return the stored result |
| `X-Request-Id` | No | Echoed in the response and logs; generated if absent |

### Request and response

The same call for two tenants. Each tenant has its own input shape and its own formula.

```http
POST /v1/tenants/uscard/calculate-ndi
{ "input": { "monthlyNetIncome": "5200.00", "monthlyHousingCost": "1500.00", "monthlyDebtPayments": "650.00" } }

200 OK
{
  "calculationId": "7b0e4c1a-5d2f-4e7a-9a53-2f6f1c0d8e11",
  "tenantId": "uscard",
  "calculatorVersion": "uscard-1",
  "result": { "ndi": "1850.00" },
  "computedAt": "2026-10-02T15:47:00Z"
}
```

```http
POST /v1/tenants/autofinance/calculate-ndi
{ "input": { "annualGrossIncome": "84000.00", "monthlyObligations": "2100.00", "dependents": 2 } }

200 OK
{
  "calculationId": "c2a6f0de-91b4-4b0c-8a3e-0f4f7d5b6a22",
  "tenantId": "autofinance",
  "calculatorVersion": "autofinance-1",
  "result": { "ndi": "2550.00" },
  "computedAt": "2026-10-02T15:47:01Z"
}
```

Money is sent as a decimal string, never a JSON number, so no precision is lost in transit.

### Errors

Errors use `application/problem+json` (RFC 9457) with a stable `type` per row.

| Status | Type | When |
| --- | --- | --- |
| 400 | `invalid-input` | `input` does not match the tenant's schema |
| 401 | `unauthenticated` | Missing or invalid token |
| 403 | `tenant-mismatch` | Token's tenant differs from the path tenant |
| 404 | `tenant-not-found` | Caller's own tenant has no calculator registered |
| 404 | `calculation-not-found` | No such calculation for this tenant |
| 409 | `idempotency-conflict` | Same `Idempotency-Key`, different body |
| 413 | `payload-too-large` | Request body is over `platform.max-request-bytes` |
| 422 | `calculation-rejected` | Input is well-formed but a tenant rule rejects it |
| 429 | `rate-limited` | Tenant's quota is used up; `Retry-After` is set |
| 503 | `tenant-busy` | Tenant's concurrency limit is reached |
| 503 | `timed-out` | A statement exceeded the tenant's `statement_timeout` |

A caller with a valid token for one tenant always gets 403 on another tenant's path, whether or not that tenant exists. This keeps tenant names from being discoverable.

413 is the one error raised before authentication. An oversized body is refused without being read, so the cheapest rejection does not require knowing who is calling.

## Request flow

A request passes through the same four shared steps for every tenant, then crosses one interface into that tenant's module.

&#91;embedded content: request flow · 3 shared steps, 1 contract, 1 module per tenant\]

The diagram predates the Oct 7 revision: it shows three shared steps, before the payload filter was added ahead of security. The list below is current.

1. `PayloadLimitFilter` caps the request body before anything parses it. Over the cap is 413, and this runs ahead of security.
2. The security filter validates the JWT; an invalid token gets 401 before any tenant logic runs.
3. The interceptor compares the path tenant with the token's `tenant_id`, then builds the `TenantContext` with that tenant's settings.
4. The controller enters that tenant's guard. Over the rate limit is 429, at the concurrency limit 503, and neither waits.
5. Inside the guard: the idempotency key is looked up, the service asks the registry for the tenant's calculator, converts `input` to the calculator's own input type and calls it, and the repository stores the result.
6. Every statement runs in a transaction opened for that tenant and under that tenant's `statement_timeout`.

Step 4 is deliberately outside step 5 rather than inside it. The guard exists to keep one tenant off the shared database, so it has to sit above every statement, including the idempotency lookup. See **Noisy neighbours**.

## Code structure

The build has one contract module, one shared application module and one module per tenant. The compiler enforces the boundary, because a tenant module's classpath holds only `calc-spi`.

```text
calc-platform/
├── calc-spi/                 com.capitalone.calc.spi                 the contract, no framework
├── calc-app/                 com.capitalone.calc.app                 Spring Boot service, shared code
└── tenants/
    ├── tenant-uscard/        com.capitalone.calc.tenant.uscard
    └── tenant-autofinance/   com.capitalone.calc.tenant.autofinance
```

| Module | Contains | May depend on | Must not depend on |
| --- | --- | --- | --- |
| `calc-spi` | `TenantCalculator`, `TenantContext`, `TenantId`, `TenantSettings` | JDK only | Anything else |
| `tenant-<id>` | One calculator, its input and output records, its tests | `calc-spi`, JDK | `calc-app`, other tenant modules, Spring, JDBC, HTTP clients |
| `calc-app` | Controller, interceptor, registry, service, guard, repository | `calc-spi` at compile scope; tenant modules at **runtime scope only** | Tenant classes in source |

Runtime scope is the key detail. `calc-app` ships with every tenant module on its classpath, but its source cannot name `UsCardCalculator`, so a tenant-specific branch in shared code does not compile. Calculators are found at startup through Java's `ServiceLoader`.

Each tenant module can have its own code owners, so a change to one tenant's logic is reviewed by that tenant's team and touches no shared file.

## Core types

One interface is the whole contract between shared code and a tenant. The contract, both calculators, the registry and the service below were compiled and run; the web-layer classes are sketches.

### The contract (`calc-spi`)

```java
public record TenantId(String value) {
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,30}");

    public TenantId {
        if (value == null || !SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid tenant id");
        }
    }
}

/** Read-only settings for one tenant. The platform fills it from tenants.<id>.* only. */
public record TenantSettings(Map<String, String> values) {
    public TenantSettings {
        values = Map.copyOf(values);
    }

    public BigDecimal decimal(String key, String fallback) {
        return new BigDecimal(values.getOrDefault(key, fallback));
    }
}

/** Who the request is for. Built once at the edge, immutable, passed explicitly. */
public record TenantContext(TenantId tenantId, String requestId, TenantSettings settings) {}

/** Implementations must be stateless and must not do I/O. */
public interface TenantCalculator<I, O> {
    TenantId tenantId();

    /** Recorded with every result, so a stored result can be traced to the logic that made it. */
    String version();

    /** The tenant's own input type. The platform deserializes the request's "input" into it. */
    Class<I> inputType();

    O calculateNDI(TenantContext ctx, I input);
}
```

`TenantContext` is passed as a parameter, not held in a `ThreadLocal`. A method that needs the tenant must ask for it in its signature, so it cannot be forgotten or leak across pooled threads.

### A tenant module (`tenant-uscard`)

US Card takes monthly net income and subtracts housing, debt payments and a living allowance. Its input and output records are package-private, so nothing outside the module can use them.

```java
record UsCardInput(BigDecimal monthlyNetIncome, BigDecimal monthlyHousingCost, BigDecimal monthlyDebtPayments) {}
record UsCardResult(BigDecimal ndi) {}

public final class UsCardCalculator implements TenantCalculator<UsCardInput, UsCardResult> {
    private static final TenantId ID = new TenantId("uscard");

    @Override public TenantId tenantId() { return ID; }
    @Override public String version() { return "uscard-1"; }
    @Override public Class<UsCardInput> inputType() { return UsCardInput.class; }

    @Override
    public UsCardResult calculateNDI(TenantContext ctx, UsCardInput input) {
        if (input.monthlyNetIncome().signum() <= 0) {
            throw new CalculateNDIRejectedException("monthlyNetIncome must be positive");
        }
        BigDecimal livingAllowance = ctx.settings().decimal("living-allowance", "1200.00");

        BigDecimal ndi = input.monthlyNetIncome()
                .subtract(input.monthlyHousingCost())
                .subtract(input.monthlyDebtPayments())
                .subtract(livingAllowance)
                .setScale(2, RoundingMode.HALF_EVEN);
        return new UsCardResult(ndi);
    }
}
```

The module registers itself with one line in `META-INF/services/com.capitalone.calc.spi.TenantCalculator`:

```text
com.capitalone.calc.tenant.uscard.UsCardCalculator
```

### A second tenant (`tenant-autofinance`)

Auto Finance starts from annual gross income, applies an effective tax rate, then subtracts obligations and an allowance per dependent. It has a different input, formula and settings, and shares no code with US Card.

```java
record AutoFinanceInput(BigDecimal annualGrossIncome, BigDecimal monthlyObligations, int dependents) {}
record AutoFinanceResult(BigDecimal ndi) {}

public final class AutoFinanceCalculator implements TenantCalculator<AutoFinanceInput, AutoFinanceResult> {
    private static final TenantId ID = new TenantId("autofinance");
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    @Override public TenantId tenantId() { return ID; }
    @Override public String version() { return "autofinance-1"; }
    @Override public Class<AutoFinanceInput> inputType() { return AutoFinanceInput.class; }

    @Override
    public AutoFinanceResult calculateNDI(TenantContext ctx, AutoFinanceInput input) {
        if (input.annualGrossIncome().signum() <= 0) {
            throw new CalculateNDIRejectedException("annualGrossIncome must be positive");
        }
        if (input.dependents() < 0) {
            throw new CalculateNDIRejectedException("dependents must not be negative");
        }
        BigDecimal taxRate = ctx.settings().decimal("effective-tax-rate", "0.25");
        BigDecimal perDependent = ctx.settings().decimal("per-dependent-allowance", "300.00");

        BigDecimal monthlyGross = input.annualGrossIncome().divide(MONTHS_PER_YEAR, 10, RoundingMode.HALF_EVEN);
        BigDecimal monthlyNet = monthlyGross.multiply(BigDecimal.ONE.subtract(taxRate));
        BigDecimal ndi = monthlyNet
                .subtract(input.monthlyObligations())
                .subtract(perDependent.multiply(BigDecimal.valueOf(input.dependents())));
        return new AutoFinanceResult(ndi.setScale(2, RoundingMode.HALF_EVEN));
    }
}
```

### Shared dispatch (`calc-app`)

The registry is the only place shared code meets tenant code. It refuses to start if two modules claim the same tenant.

```java
public final class TenantCalculatorRegistry {
    private final Map<TenantId, TenantCalculator<?, ?>> byTenant;

    /** Discovers every calculator on the runtime classpath. */
    @SuppressWarnings("rawtypes")
    public static TenantCalculatorRegistry discover() {
        Map<TenantId, TenantCalculator<?, ?>> found = new HashMap<>();
        for (TenantCalculator calculator : ServiceLoader.load(TenantCalculator.class)) {
            register(found, calculator);
        }
        return new TenantCalculatorRegistry(found);
    }

    TenantCalculatorRegistry(Map<TenantId, TenantCalculator<?, ?>> byTenant) {
        this.byTenant = Map.copyOf(byTenant);
    }

    static void register(Map<TenantId, TenantCalculator<?, ?>> into, TenantCalculator<?, ?> calculator) {
        if (into.putIfAbsent(calculator.tenantId(), calculator) != null) {
            throw new IllegalStateException(
                    "More than one calculator registered for tenant " + calculator.tenantId().value());
        }
    }

    public TenantCalculator<?, ?> forTenant(TenantId id) {
        TenantCalculator<?, ?> calculator = byTenant.get(id);
        if (calculator == null) {
            throw new TenantNotFoundException(id);
        }
        return calculator;
    }
}
```

The service is identical for every tenant: look up, convert, call. It holds no guard; admission control is the controller's job, one level up.

```java
public final class CalculateNDIService {
    private final TenantCalculatorRegistry registry;
    private final ObjectMapper mapper;

    public CalculateNDIService(TenantCalculatorRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    public Computation calculateNDI(TenantContext ctx, JsonNode rawInput) {
        return run(registry.forTenant(ctx.tenantId()), ctx, rawInput);
    }

    private <I, O> Computation run(TenantCalculator<I, O> calculator, TenantContext ctx, JsonNode rawInput) {
        I input = toInput(rawInput, calculator.inputType());
        O output = calculator.calculateNDI(ctx, input);
        return new Computation(calculator.version(), mapper.valueToTree(output));
    }

    private <I> I toInput(JsonNode rawInput, Class<I> type) {
        try {
            return mapper.convertValue(rawInput, type);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(e);   // 400 invalid-input
        }
    }
}

public record Computation(String calculatorVersion, JsonNode result) {}

/** Runs work inside one tenant's rate limit and concurrency limit. */
public interface TenantGuard {
    <T> T run(TenantId tenant, Supplier<T> work);
}
```

The platform's `ObjectMapper` rejects unknown, missing and null fields, and writes `BigDecimal` as a string. Tenant modules therefore need no JSON library, and sending US Card's body to Auto Finance's endpoint fails with 400.

### Web layer

The interceptor is the only code that reads `{tenantId}` from the URL. Everything after it receives a `TenantContext`. The tenant id has to agree in two independent places, the path and the token, or the request never reaches a calculator.

```java
@Component
class TenantContextInterceptor implements HandlerInterceptor {
    static final String ATTRIBUTE = TenantContext.class.getName();
    private static final String TIMER_SAMPLE = TenantContextInterceptor.class.getName() + ".timer";

    private final TenantSettingsProvider settings;
    private final MeterRegistry meters;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables = (Map<String, String>)
                request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String pathTenant = pathVariables == null ? null : pathVariables.get("tenantId");
        String tokenTenant = SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getClaimAsString("tenant_id")
                : null;

        if (pathTenant == null || !pathTenant.equals(tokenTenant)) {
            throw new TenantMismatchException();   // 403, whether or not the tenant exists
        }
        TenantId tenantId;
        try {
            tenantId = new TenantId(pathTenant);
        } catch (IllegalArgumentException e) {
            throw new TenantMismatchException();   // a malformed slug is indistinguishable from a wrong one
        }
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);

        request.setAttribute(ATTRIBUTE, new TenantContext(tenantId, requestId, settings.forTenant(tenantId)));
        request.setAttribute(TIMER_SAMPLE, Timer.start(meters));
        MDC.put("tenant", tenantId.value());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (request.getAttribute(TIMER_SAMPLE) instanceof Timer.Sample sample
                && request.getAttribute(ATTRIBUTE) instanceof TenantContext ctx) {
            sample.stop(Timer.builder("ndi.requests")
                    .tag("tenant", ctx.tenantId().value())
                    .tag("method", request.getMethod())
                    .tag("status", Integer.toString(response.getStatus()))
                    .register(meters));
        }
        MDC.remove("tenant");
    }
}
```

Three details carry weight here:

- **Two sources must agree.** `pathTenant.equals(tokenTenant)` is the whole authorization decision. A token is only ever valid on its own tenant's path.
- **The slug is validated, not trusted.** `new TenantId(...)` enforces `[a-z][a-z0-9-]{1,30}`, which is what makes the value safe to put in a URL path, compare to a claim without case folding, use as a registry key and emit as a metric tag. A malformed slug returns 403, not 400, so a probe learns nothing.
- **`X-Request-Id` comes from the filter**, not the header, because `RequestIdFilter` runs before security and so already stamped 401 responses with it.

A `HandlerMethodArgumentResolver` turns that request attribute into a controller parameter, so the controller has no `@PathVariable tenantId` to misuse.

```java
@RestController
@RequestMapping("/v1/tenants/{tenantId}/calculate-ndi")
class CalculateNDIController {

    @PostMapping
    CalculateNDIResponse calculateNDI(TenantContext ctx,
                                      @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                      @RequestBody CalculateNDIRequest body) {
        return guard.run(ctx.tenantId(), () -> calculate(ctx, key, body));
    }

    private CalculateNDIResponse calculate(TenantContext ctx, String key, CalculateNDIRequest body) {
        if (key != null) {
            Optional<StoredNDI> previous = repository.findByIdempotencyKey(ctx, key);
            if (previous.isPresent()) {
                return replay(previous.get(), body.input());
            }
        }
        Computation computed = service.calculateNDI(ctx, body.input());
        try {
            StoredNDI stored = repository.save(ctx, key, body.input(), computed);
            return CalculateNDIResponse.of(stored);
        } catch (DuplicateKeyException e) {
            // A concurrent request with the same key stored its result first.
            return replay(repository.findByIdempotencyKey(ctx, key).orElseThrow(() -> e), body.input());
        }
    }

    @GetMapping("/{calculationId}")
    CalculateNDIResponse get(TenantContext ctx, @PathVariable UUID calculationId) {
        return guard.run(ctx.tenantId(), () -> repository.find(ctx, calculationId)
                .map(CalculateNDIResponse::of)
                .orElseThrow(NDINotFoundException::new));
    }
}
```

When `Idempotency-Key` is present, the controller first looks up the key for this tenant. A stored row with the same input is returned as is; a different input returns 409.

**Both handlers are wrapped, including the `GET`.** Every path here reaches the database, so every path has to be inside the guard. The invariant the class comment states is that nothing in this controller touches the repository outside `guard.run`. It is not compiler-enforced, which is the known cost of putting the guard here rather than in the interceptor; the alternative was rejected because Spring skips `afterCompletion` for an interceptor whose `preHandle` threw, which would leak the `tenant` MDC entry and lose the 429 from the `ndi.requests` timer.

### Request size

A tenant cannot be rate-limited into safety if a single request can exhaust the shared heap. `PayloadLimitFilter` caps the body ahead of security, and has to do it twice because a caller can simply decline to declare a length.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class PayloadLimitFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            Problems.write(response, mapper, Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, Problems.PAYLOAD_TOO_LARGE,
                    new PayloadTooLargeException(maxBytes).getMessage()));
            return;
        }
        chain.doFilter(new CappedBodyRequest(request, maxBytes), response);
    }
}
```

- **Declared length** is the fast path: refuse before reading a byte.
- **Chunked bodies** report `-1`, so the wrapped `ServletInputStream` counts as it reads and cuts the request off at the cap. This is the path that actually matters, because it is the one an attacker would choose.
- **The stream throws an unchecked `PayloadTooLargeException`**, not an `IOException`. The body is read inside the `DispatcherServlet`, so an unchecked `ApiException` reaches `ApiExceptionHandler` and becomes a 413 problem document; an `IOException` would have surfaced as a 500.
- **Filters write their own problem document.** They run outside the `DispatcherServlet` and so never reach `ApiExceptionHandler`. `Problems.write` is shared with `ProblemAuthenticationEntryPoint` so a 413 and a 401 have the same shape.
- **`server.tomcat.max-swallow-size` stays at 2MB** so Tomcat drains a rejected body and the 413 reaches the client instead of a connection reset.

## Data model

All tenants share one table, and Postgres row-level security filters every statement by tenant. A query that forgets its `WHERE tenant_id = ...` returns no rows, not another tenant's rows.

```sql
-- Run by the migration role (owns the table).
CREATE TABLE ndi_calculation (
    tenant_id          text        NOT NULL,
    calculation_id     uuid        NOT NULL,
    calculator_version text        NOT NULL,
    idempotency_key    text,
    input              jsonb       NOT NULL,
    result             jsonb       NOT NULL,
    computed_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, calculation_id),
    UNIQUE (tenant_id, idempotency_key)
);

ALTER TABLE ndi_calculation ENABLE ROW LEVEL SECURITY;
ALTER TABLE ndi_calculation FORCE ROW LEVEL SECURITY;

-- A row is visible, and writable, only when it belongs to the tenant
-- the current transaction was opened for.
CREATE POLICY tenant_isolation ON ndi_calculation
    USING      (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- The service connects as calc_app: not the owner, no BYPASSRLS.
GRANT SELECT, INSERT ON ndi_calculation TO calc_app;
```

The repository opens every transaction by naming the tenant and arming that tenant's time budget. The third argument `true` makes each setting local to the transaction, so neither can carry over on a pooled connection.

```java
@Repository
class CalculateNDIRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    CalculateNDIRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    Optional<StoredNDI> find(TenantContext ctx, UUID calculationId) {
        return inTenant(ctx, () -> jdbc.sql("""
                SELECT tenant_id, calculation_id, calculator_version, result, computed_at
                  FROM ndi_calculation
                 WHERE tenant_id = :tenant AND calculation_id = :id""")
                .param("tenant", ctx.tenantId().value())
                .param("id", calculationId)
                .query(StoredNDI.class)
                .optional());
    }

    private <T> T inTenant(TenantContext ctx, Supplier<T> work) {
        String timeoutMs = Integer.toString(tenants.limits(ctx.tenantId()).statementTimeoutMs());
        return tx.execute(status -> {
            jdbc.sql("""
                    SELECT set_config('app.tenant_id', :tenant, true) AS tenant,
                           set_config('statement_timeout', :timeout, true) AS timeout""")
                    .param("tenant", ctx.tenantId().value())
                    .param("timeout", timeoutMs)
                    .query()
                    .singleRow();
            return work.get();
        });
    }
}
```

Design points:

- **Two filters, on purpose.** The query names the tenant and the policy checks it again. Either one alone would be enough; together a bug in one is harmless.
- **No `TenantContext`, no query.** Every repository method takes a `TenantContext`, so data access without a tenant does not compile.
- **Idempotency keys are per tenant.** Two tenants can use the same key without colliding.
- **Results are append-only.** The service role has no `UPDATE` or `DELETE` grant.
- **One round trip arms both settings.** Tenant and timeout are set in a single `SELECT`, so the time budget costs nothing extra.
- **The budget is per tenant.** `statement_timeout` reads from the same `limits.*` block as the rate and concurrency limits, so a tenant with heavier queries is widened without widening everyone. When it fires, Postgres cancels the statement, Spring raises `QueryTimeoutException` and the caller gets 503 `timed-out` rather than an opaque 500.

The schema above was run on Postgres 16 as `calc_app`. Auto Finance could not read or count US Card's row, could not insert a row labelled `uscard`, and a session with no tenant set saw zero rows.

## Runtime isolation

Every shared runtime resource is keyed by tenant id, so one tenant's load or failure stays with that tenant.

| Concern | Mechanism | Effect on other tenants |
| --- | --- | --- |
| Request rate | One rate limiter per tenant; over the limit returns 429 | None: each tenant has its own quota, per instance |
| Concurrency | One semaphore bulkhead per tenant; full returns 503 | A slow calculator cannot take every request thread |
| Request size | `platform.max-request-bytes`, capped before security | One caller cannot exhaust the shared heap |
| Database time | `statement_timeout` per tenant, armed per transaction | A slow query cannot hold a pooled connection |
| Connection pool | `maximum-pool-size` at or above the sum of every tenant's `max-concurrent` | Tenants do not queue on each other for connections |
| Calculator failure | Exceptions are caught at the service boundary and mapped to a problem response | A bug in one module fails only that tenant's endpoint |
| Configuration | Calculator receives only `tenants.<id>.*` | A tenant cannot read another tenant's settings |
| Caching | Any cache key starts with the tenant id | No cross-tenant cache hits |
| Logs | `tenant` and `requestId` on every log line; `input` is not logged | Logs can be filtered and access-controlled per tenant |
| Metrics | `tenant` tag on request count, latency, 429s and 503s | Per-tenant dashboards and alerts |

Limits come from platform defaults, overridden per tenant under `tenants.<id>.limits.*`.

The guard is a thin wrapper over Resilience4j, which creates one limiter and one bulkhead per tenant name, each sized from that tenant's limits. Neither waits:

```java
@Component
class Resilience4jTenantGuard implements TenantGuard {
    private static final Duration REFRESH_PERIOD = Duration.ofSeconds(1);

    private final RateLimiterRegistry limiters = RateLimiterRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final TenantSettingsProvider tenants;

    @Override
    public <T> T run(TenantId tenant, Supplier<T> work) {
        TenantLimits limits = tenants.limits(tenant);
        RateLimiter limiter = limiters.rateLimiter(tenant.value(), () -> RateLimiterConfig.custom()
                .limitForPeriod(limits.requestsPerSecond())
                .limitRefreshPeriod(REFRESH_PERIOD)
                .timeoutDuration(Duration.ZERO)
                .build());
        Bulkhead bulkhead = bulkheads.bulkhead(tenant.value(), () -> BulkheadConfig.custom()
                .maxConcurrentCalls(limits.maxConcurrent())
                .maxWaitDuration(Duration.ZERO)
                .build());
        try {
            return RateLimiter.decorateSupplier(limiter, Bulkhead.decorateSupplier(bulkhead, work)).get();
        } catch (RequestNotPermitted e) {
            throw new RateLimitedException(REFRESH_PERIOD.toSeconds());
        } catch (BulkheadFullException e) {
            throw new TenantBusyException();
        }
    }
}
```

- **Nothing queues.** Both `Duration.ZERO` settings mean a tenant at its limit is rejected immediately rather than parked on a request thread, which is what makes 429 and 503 cheap.
- **Limiters are created lazily per name**, so their number is bounded by the number of tenants that actually receive traffic.
- **Rejection is translated at the boundary.** `RequestNotPermitted` and `BulkheadFullException` become the API's own exceptions here, so nothing downstream handles Resilience4j types.

### Timeouts and caps

Every budget in one place. Hikari takes plain milliseconds; Tomcat takes durations.

| Budget | Setting | Value | What it bounds |
| --- | --- | --- | --- |
| Request body | `platform.max-request-bytes` | 64KB | Heap and parse time from one caller |
| Request headers | `server.max-http-request-header-size` | 16KB | Header-driven memory growth |
| Header arrival | `server.tomcat.connection-timeout` | 5s | A slow client holding a thread before the body arrives |
| Idle connection | `server.tomcat.keep-alive-timeout` | 15s | Idle keep-alive connections holding threads |
| Rejected body drain | `server.tomcat.max-swallow-size` | 2MB | Keeps the connection reusable after a 413 |
| Connection acquisition | `spring.datasource.hikari.connection-timeout` | 2000ms | Waiting on a saturated pool, against a 30s default |
| Pool size | `spring.datasource.hikari.maximum-pool-size` | 20 | Must stay at or above the sum of `max-concurrent` |
| Held connection | `spring.datasource.hikari.leak-detection-threshold` | 10000ms | Logs a connection held too long |
| Statement time | `platform.limits.statement-timeout-ms`, per tenant | 2000ms | One tenant's slow query holding a connection |
| Request rate | `limits.requests-per-second`, per tenant | 50 default, 20 for `uscard` | Per-tenant throughput, per instance |
| Concurrency | `limits.max-concurrent`, per tenant | 10 default, 5 for `uscard` | Per-tenant in-flight work in this JVM |

The pool-size line is an invariant, not a preference: `autofinance` at 10 plus `uscard` at 5 is 15 concurrent calls, so a pool below 15 makes tenants wait on each other for connections no matter what their own limits say. It is stated in a comment in `application.yml` and is **not** enforced at startup.

## Noisy neighbours

Worth separating what this design actually bounds from what it only appears to.

**Bounded.** One tenant cannot take another's request slots (per-tenant bulkhead), cannot exceed its own throughput (per-tenant rate limiter), cannot hold a pooled connection indefinitely (per-tenant `statement_timeout`), cannot exhaust the shared heap with one request (`max-request-bytes`), and cannot read or write another tenant's rows (row-level security, forced, on a non-owner role). A calculator that throws fails only its own tenant's endpoint.

**Not bounded.**

- **No wall-clock cap on handler execution.** A calculator stuck in a CPU loop cannot be interrupted: Spring MVC is synchronous, and a Resilience4j `TimeLimiter` would need async dispatch that still would not stop the work. What contains it today is the bulkhead, which keeps the damage inside that tenant's slot count, and the statement timeout, which bounds the database half. A hard cap means moving the handler to async dispatch.
- **Rate limits are per JVM.** `RateLimiterRegistry.ofDefaults()` is an in-memory map, so `20` requests per second means 20 **per replica**. See below.
- **The pool-size invariant is unenforced.** Nothing fails at startup if the sum of `max-concurrent` exceeds `maximum-pool-size`.

### Per-instance limits and horizontal scaling

The two limiters have opposite answers to whether they should be distributed, and conflating them is the easy mistake.

The **bulkhead should stay local**. It guards a genuinely per-JVM resource, that process's threads and its slice of the connection pool. "No more than five of this tenant's requests running in this JVM" is the correct statement, and coordinating it across replicas would be coordinating over something that is not shared.

The **rate limiter wants to be global**, for four reasons:

1. **It guards something shared.** Everything the rate limit protects, Postgres and its connection slots, is one resource behind every replica. A per-replica budget cannot bound load on a shared thing. `maximum-pool-size: 20` is also per replica, so five replicas is 100 connections against Postgres' default `max_connections` of 100: the database hits its ceiling before any tenant hits a limit.
2. **It makes the published number meaningless.** "20 rps" in a tenant contract is really 20 × however many pods are running, and it moves without anyone changing config.
3. **Autoscaling inverts it.** Load rises, replicas are added, and every tenant's effective ceiling rises with it. The limiter loosens exactly when it should hold.
4. **It is simultaneously too strict.** Load balancing is not perfectly even, so keep-alive pinning or hash routing can land most of one tenant's traffic on one replica. That tenant sees 429s at 20 rps while the fleet is nowhere near 20 × N. Per-instance limits are too loose globally and too tight locally at the same time.

Distributing it costs a round trip to Redis or similar on every request, before any work is done, plus a dependency whose failure mode has to be chosen: fail open and risk the database, or fail closed and reject everyone. Resilience4j has no distributed mode, so this means Bucket4j over Redis or Hazelcast, or a Lua token bucket.

**Recommendation.** If there is an API gateway or service mesh in front of this, enforce the contractual per-tenant rate there. It already sees every request at one chokepoint and keeps Redis off the hot path; the in-process limiter then stays as a local backstop with a deliberately generous ceiling. Only with no such chokepoint does Redis-backed limiting in the application earn its cost.

None of this bites while the service runs as a single instance, which is where it is today. It is a precondition for the second replica, and `maximum-pool-size` is the one to fix first: it needs dividing across replicas, not replicating, and that bites immediately at the second pod.

## Enforcing isolation

Each isolation rule has a check that fails the build or a test when the rule is broken, so the boundaries do not depend on code review.

| Rule | Enforced by | Breaks at |
| --- | --- | --- |
| A tenant module imports another tenant | Module classpath holds only `calc-spi` | Compile |
| Shared code names a tenant class | Tenant modules are runtime-scope dependencies of `calc-app` | Compile |
| A tenant module uses Spring, JDBC or an HTTP client | ArchUnit rule below | Unit test |
| Two modules claim one tenant | Registry throws at startup | Startup |
| A caller uses another tenant's path | API test: US Card token on Auto Finance path returns 403 | Integration test |
| A query runs without a tenant | Row-level security returns no rows | Integration test |
| A tenant's formula changes by accident | Golden input and output cases inside the tenant module | Unit test |
| A rejected request still reaches the database | `RateLimitOrderingTest` spies the repository and counts lookups against admitted requests | Integration test |
| An oversized body is accepted | `PayloadLimitApiTest`, declared length and chunked, authenticated and not | Integration test |
| The statement timeout is not armed or leaks | `StatementTimeoutTest` cancels a `pg_sleep` and asserts the setting resets next transaction | Integration test |
| `calc-spi` grows a dependency | ArchUnit rule `contract_depends_on_the_jdk_only` | Unit test |

The four ArchUnit rules live in `calc-app`'s tests, where every tenant module is on the classpath:

```java
@AnalyzeClasses(packages = "com.capitalone.calc")
class TenantIsolationArchTest {

    @ArchTest
    static final ArchRule tenants_do_not_depend_on_each_other =
            slices().matching("com.capitalone.calc.tenant.(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule tenants_depend_only_on_the_contract =
            classes().that().resideInAPackage("com.capitalone.calc.tenant..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("com.capitalone.calc.spi..", "com.capitalone.calc.tenant..", "java..");

    @ArchTest
    static final ArchRule shared_code_does_not_name_tenants =
            noClasses().that().resideOutsideOfPackage("com.capitalone.calc.tenant..")
                    .should().dependOnClassesThat().resideInAPackage("com.capitalone.calc.tenant..");

    @ArchTest
    static final ArchRule contract_depends_on_the_jdk_only =
            classes().that().resideInAPackage("com.capitalone.calc.spi..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("com.capitalone.calc.spi..", "java..");
}
```

### What was checked

As of the Oct 7 revision the whole design is built and under test. The gap the first draft recorded, that the web layer, the guard and the ArchUnit rules had never been run, is closed.

- **Full suite green:** 12 tests in `calc-spi`, 6 in `tenant-uscard`, 7 in `tenant-autofinance`, 53 in `calc-app`.
- **Worked examples:** both still produce 1850.00 and 2550.00, and each tenant's body is still rejected on the other's endpoint.
- **Web layer:** the interceptor, argument resolver, controller, filters and problem mapping run against a live Tomcat on a random port, with real HTTP from `ApiClient` so tests see what a caller sees.
- **Guard:** `Resilience4jTenantGuardTest` proves a tenant exhausting its quota does not limit another, and that a tenant filling its bulkhead fills only its own.
- **Row-level security:** the production `schema.sql` runs on Postgres 16 via Testcontainers, with the app connecting as `calc_app`, so every integration test exercises the policy rather than a stand-in.
- **ArchUnit:** all four rules run.
- **Guard ordering:** `RateLimitOrderingTest` was confirmed to be a real regression test, failing against the previous ordering (idempotency lookup before the guard) and passing after.
- **Configuration:** Hikari, Tomcat and filter values were read back from the running context rather than assumed, since a misspelled property binds silently. Observed: pool 20, acquisition 2000ms, validation 1000ms, leak detection 10000ms; Tomcat 5000ms, 15000ms, 2097152, 16384; filter cap 65536.
- **Not covered by a test:** the pool-size invariant (nothing fails at startup), and any hard cap on handler execution time, which does not exist.

Running the suite needs a Docker socket Testcontainers can find. With Colima that means `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Onboarding a new tenant

Adding a tenant adds one module and one dependency line. No shared class, route or table changes.

1. Create `tenants/tenant-<id>` with `calc-spi` as its only dependency.
2. Define the tenant's input and output records and implement `TenantCalculator`, returning the new slug from `tenantId()`.
3. Add the calculator's class name to `META-INF/services/com.capitalone.calc.spi.TenantCalculator`.
4. Add golden input and output cases as unit tests in the module.
5. Add the module to `calc-app` as a runtime-scope dependency. The ArchUnit rules cover it by package pattern.
6. Add `tenants.<id>.*` settings and limits to configuration.
7. Issue credentials whose token carries `tenant_id = <id>`.
8. Deploy. `POST /v1/tenants/<id>/calculate-ndi` is live.

Removing a tenant is the reverse of steps 5 and 7. Its endpoint then rejects every caller, and its stored rows stay unreadable until they are archived or deleted.

## Open decisions

These are the assumptions most likely to change the design.

- [ ] **Stack.** Java and Spring Boot are assumed. Which language and framework will this be built in?
- [ ] **Tenant in the URL.** The design uses a path segment. A subdomain per tenant (`uscard.api.capitalone.com`) changes only the interceptor.
- [ ] **How tenants are added.** The design assumes a deploy per new tenant. If logic must change without a deploy, calculators become plugin jars or rule definitions, and the registry loads them at runtime.
- [ ] **Storing results.** If the API can be stateless, drop the `GET` endpoint, the table and idempotency.
- [ ] **Depth of data isolation.** One table with row-level security is assumed. Does any tenant need its own schema or database?
- [ ] **Authentication.** A JWT with a `tenant_id` claim is assumed. Are API keys or mutual TLS required instead?
- [ ] **Long calculations.** Partly settled: database time is now bounded per tenant by `statement_timeout`. Calculator time is not, because synchronous Spring MVC cannot interrupt a CPU loop. If any tenant's calculation can run for seconds, this needs async dispatch or a job API.
- [ ] **Horizontal scaling.** The design is single-instance today. Before a second replica: `maximum-pool-size` must be divided across replicas rather than replicated, and the per-tenant rate limit has to move to a shared chokepoint (gateway, or Redis-backed) to mean anything. The bulkhead stays per instance. See **Noisy neighbours**.
- [ ] **Enforcing the pool invariant.** Should the service refuse to start when the sum of every tenant's `max-concurrent` exceeds `maximum-pool-size`? It is a comment in configuration today, which means it will drift.
- [ ] **Payload cap per tenant.** `max-request-bytes` is platform-wide at 64KB. If one tenant's input is legitimately larger, it moves into `limits.*` like the others.
