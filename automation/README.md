# Automation — Astronomy Shop buy path

Java 17 + JUnit 5 + gRPC stubs from `demo.proto`, plus a thin HTTP smoke against `frontend-proxy:8080`.

## Prerequisites

1. **JDK 17+** (`java -version`)
2. **Docker** with the OpenTelemetry demo running:

```bash
git clone --depth=1 https://github.com/open-telemetry/opentelemetry-demo
cd opentelemetry-demo
docker compose up -d
# Frontend / API proxy: http://localhost:8080
```

3. Wait until homepage returns 200 and core containers are up (`cart`, `checkout`, `payment`, `currency`, `product-catalog`).

Host gRPC ports are **dynamically mapped** by Compose. Tests resolve them via `docker port <container> <containerPort>`, or you can set overrides:

| Env var | Default container port |
|---|---|
| `CART_HOST_PORT` | 7070 |
| `CHECKOUT_HOST_PORT` | 5050 |
| `CURRENCY_HOST_PORT` | 7001 |
| `PRODUCT_CATALOG_HOST_PORT` | 3550 |
| `PAYMENT_HOST_PORT` | 50051 |
| `FRONTEND_BASE_URL` | `http://localhost:8080` |
| `OTEL_DEMO_HOST` | `localhost` |

## Run

From the repo root (this module is part of the root multi-project build):

```bash
./gradlew :automation:test
```

Re-run after stack/code changes:

```bash
./gradlew :automation:clean :automation:test
```

JUnit XML / HTML reports: `automation/build/reports/tests/test/index.html`

## What is automated vs not

| Manual case | Automated? | Where |
|---|---|---|
| **CHK-01** Happy path (2 items) | Yes (gRPC + HTTP) | `CheckoutFlowTest`, `FrontendHttpSmokeTest` |
| **CHK-02** Empty cart | Yes | `CheckoutFlowTest` |
| **CHK-03** Invalid / expired card | Yes | `CheckoutFlowTest`, `PaymentTest` |
| **CHK-04** Payment flag / unreachable | **No** | Needs flagd control + shared-env safety |
| **CHK-05** Ship fails after charge | **No** | Needs fault injection; pre-release manual |
| **CHK-06** Currency on order | Yes (EUR) | `CheckoutFlowTest` |
| **CART-01** Add + persist | Yes (gRPC) | `CartTest` |
| **CART-02** Merge qty / empty | Yes | `CartTest` |
| **CART-03** Session TTL 60m | **No** | Slow / needs TTL acceleration |
| **CART-04** Valkey down | **No** | Infra chaos; pre-release |
| **CART-05** Multi-tab concurrency | **No** | Browser-only; exploratory |
| **BE-01** Unit price vs qty | Yes | `PricingTest` |
| **BE-02** FX round-trip | Yes | `CurrencyTest` |
| **BE-03** Charge amount vs logs | **No** | No amount on `ChargeResponse`; log scraping is brittle |
| **BE-04** Qty boundaries | Yes (+ xfail known bugs) | `QuantityBoundaryTest` |
| **BE-05** Cart price-blind | Yes | `CartPriceBlindTest` |
| **BE-06** Bad currency | Yes as **xfail** (bug still open) | `CurrencyTest` |
| UI visual / Cypress selectors | **No** | Deliberate — keep PR gate stable |

### Coverage (P0 flow definition)

Of **17** documented manual cases:

- **Fully automated:** 11  
- **Documented known-bug (xfail):** 2 scenario groups inside BE-04 / BE-06  
- **Deliberately unautomated:** 6 (flags, shipping partial commit, TTL, Valkey down, multi-tab, charge-log reconciliation)

**P0 automation coverage ≈ 11 / 17 ≈ 65% of listed cases**, and **~100% of the stable API-level buy-path cases** that do not require chaos or browser sessions.

UI browser E2E is intentionally out of this package: the same money-path contracts are covered at gRPC/HTTP API where flakes are lower and failures are attributable to a service.

## Known defects encoded as xfail

`Xfail.expectFailure` documents **desired** behavior that the live stack still violates (see `test-cases/checkout-payment-backend.md`):

- Unsupported currency completes a $0 order (BE-06)
- Misleading “email service” error on bad quantity (BE-04)
- Extreme quantity hangs / destabilizes checkout (BE-04)

If an xfail suddenly fails the build with “no longer reproduces”, the product bug was fixed — remove the wrapper and keep the assertion.

## Layout

```
automation/
├── README.md
├── build.gradle
├── src/main/proto/demo.proto
└── src/test/java/qa/
    ├── GrpcClients.java      # port discovery + stubs
    ├── TestSupport.java
    ├── Xfail.java
    ├── CartTest.java
    ├── CheckoutFlowTest.java
    ├── PaymentTest.java
    ├── PricingTest.java
    ├── CurrencyTest.java
    ├── QuantityBoundaryTest.java
    ├── CartPriceBlindTest.java
    └── FrontendHttpSmokeTest.java
```
