# FinPay — Campus Wallet with Tiered Cashback

Java 17+ backend (JDK only, zero dependencies) + HTML/CSS/JS frontend.

## Run
    ./run.sh            # Windows: run.bat
    open http://localhost:8080

Demo logins: student `aarav@college.edu / Student@123` · coordinator `admin@finpay.edu / Admin@123`

## How it maps to the Detailed Design Record
| DDR decision | Implementation |
|---|---|
| All money in integer paise (no floats) | `long` everywhere; frontend parses ₹ strings without float maths |
| `calculate_cashback` = Alt B, integer floor | `Cashback.java`; zero-rate guard for TOPUP |
| UNIQUE(IdempotencyKey) | `Store.byKey`; `Idempotency-Key` header required on pay/top-up |
| UNIQUE(Wallet, Category, Month) accumulator | `Store.accumulators`, enforces monthly cap |
| BalanceInPaise >= 0, AmountInPaise > 0 | checked in `Store.pay/topup` |
| JWT in HttpOnly, SameSite cookie + RBAC | `Auth.java`, `Api.needRole` |
| DTO masking by role | admin responses mask email, never return phone |
| Append-only audit log | `Store.audit` (payments, top-ups, tier/rule changes, logins) |

## Assumptions (not specified in the document)
- Tier multipliers: BASIC 1.00×, PRIME 1.25×, PREMIUM 1.50×
- Seed categories/rates/caps (e.g. Food 5%, cap ₹200); editable by the coordinator
- Data is in memory (the design's RDBMS tables are modelled 1:1 in `Store.java`); restarting resets it.
  Swap `Store` for JDBC/PostgreSQL for persistence.
