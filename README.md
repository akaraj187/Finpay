<div align="center">

# 💸 FinPay

### The campus wallet that pays you back

Tiered cashback · Paise-exact arithmetic · Duplicate-proof payments · HttpOnly-cookie auth

**Java 17+ backend (JDK only, zero dependencies) · HTML / CSS / JS frontend**

</div>

---

## Table of contents

1. [Overview](#1-overview)
2. [Features](#2-features)
3. [Quick start](#3-quick-start)
4. [Demo accounts and seed data](#4-demo-accounts-and-seed-data)
5. [Project structure](#5-project-structure)
6. [Architecture](#6-architecture)
7. [Data design (ER model and 3NF schema)](#7-data-design)
8. [Algorithm: `calculate_cashback`](#8-algorithm-calculate_cashback)
9. [Security design](#9-security-design)
10. [REST API reference](#10-rest-api-reference)
11. [Trying the API with curl / PowerShell](#11-trying-the-api)
12. [Design decisions traced to the DDR](#12-design-decisions-traced-to-the-ddr)
13. [Assumptions](#13-assumptions)
14. [Configuration](#14-configuration)
15. [Troubleshooting](#15-troubleshooting)
16. [Limitations and roadmap](#16-limitations-and-roadmap)

---

## 1. Overview

FinPay is a campus digital wallet. Students top up a wallet, pay at campus merchants
(cafés, grocery, travel, books, cinema) and earn **tiered cashback** on every payment.
Coordinators manage cashback rules and student tiers, and review an append-only audit log.

The project implements the **Detailed Design Record (DDR)** from the Data, Algorithm and
Security design lab: every approved decision (integer paise money, idempotency keys, monthly
accumulators, JWT in HttpOnly cookies, DTO masking, append-only audit) is reflected in code.

| Layer | Technology |
|---|---|
| Backend | Java 17+, `com.sun.net.httpserver` (built into the JDK) |
| Frontend | Single-page HTML + CSS + vanilla JavaScript (no framework, no build step) |
| Auth | Hand-rolled HS256 JWT (`javax.crypto`), PBKDF2-HMAC-SHA256 password hashing |
| Storage | In-memory store that mirrors the 3NF relational schema |

---

## 2. Features

### Students
- Register / sign in; each student automatically gets one wallet (tier `BASIC`).
- **Top up** the wallet and **pay** merchants.
- **Live cashback preview** while typing an amount, including a "cap reached" notice.
- **Monthly cashback progress** per category against its cap.
- Recent activity feed with cashback shown per transaction.
- Celebratory confetti when cashback is earned.

### Coordinators (admin)
- Platform KPIs: wallet float, payment volume, cashback issued, transaction count.
- Student list with **masked contact data** and an inline tier selector.
- Edit **cashback rules** (rate and monthly cap) per category.
- **Audit log** of payments, top-ups, logins, failed logins, rule edits and tier changes.

### Engineering guarantees
- No floating-point money anywhere. Amounts are `long` paise end to end.
- Idempotent payments and top-ups (safe retries and double clicks).
- Monthly cap enforced atomically with the balance update.
- Server-side role checks on every protected route.

---

## 3. Quick start

### Prerequisites
- **JDK 17 or newer** (the JDK, not only the JRE). Get one at <https://adoptium.net>.
- A modern browser. Internet access is optional (only used for Google Fonts).

Check your install:

```bash
javac -version
```

### Run it

**Windows (PowerShell or Command Prompt)**
```powershell
cd path\to\finpay
.\run.bat
```

**macOS / Linux**
```bash
cd path/to/finpay
chmod +x run.sh
./run.sh
```

Then open **http://localhost:8080**.

### Manual compile and run (any OS)

```bash
mkdir out
javac -d out src/*.java        # Windows: javac -d out src\*.java
java -cp out Main
```

Stop the server with `Ctrl + C`. Data lives in memory, so it resets on every restart.

---

## 4. Demo accounts and seed data

| Role | Email | Password | Notes |
|---|---|---|---|
| Student | `aarav@college.edu` | `Student@123` | Tier **PRIME**, pre-seeded transactions |
| Student | `priya@college.edu` | `Student@123` | Tier **PREMIUM**, ₹2,500 balance |
| Coordinator | `admin@finpay.edu` | `Admin@123` | Admin console |

### Seeded merchants and cashback rules

| Category | Merchants | Base rate | Monthly cap |
|---|---|---|---|
| FOOD | Campus Café, Biryani Junction | 5.00% | ₹200 |
| GROCERY | FreshMart | 3.00% | ₹150 |
| TRAVEL | CityRide, Metro Smart Card | 4.00% | ₹250 |
| BOOKS | PageTurner Books, Stationery Hub | 8.00% | ₹300 |
| ENTERTAINMENT | CineMax | 6.00% | ₹200 |
| TOPUP | FinPay Wallet Top-Up | 0.00% | n/a |

### Tier multipliers

| Tier | Multiplier | Effective example (Books) |
|---|---|---|
| BASIC | 1.00× | 8.00% |
| PRIME | 1.25× | 10.00% |
| PREMIUM | 1.50× | 12.00% |

---

## 5. Project structure

```
finpay/
├── README.md
├── run.sh                 # macOS / Linux launcher
├── run.bat                # Windows launcher
├── public/
│   └── index.html         # Entire frontend (landing, auth modal, student + admin UI)
└── src/
    ├── Main.java          # HTTP server bootstrap, static file serving, security headers
    ├── Api.java           # Router + service layer: auth, RBAC, DTO mapping, validation
    ├── Store.java         # In-memory 3NF data model, transactions, accumulators, audit
    ├── Cashback.java      # calculate_cashback (pure integer-paise algorithm)
    ├── Auth.java          # JWT (HS256) issue/verify, PBKDF2 password hashing
    └── Json.java          # Minimal JSON parser/writer (no external libraries)
```

---

## 6. Architecture

```
 Browser (HTML/CSS/JS)
        │  fetch('/api/...', credentials: same-origin)   ← HttpOnly cookie sent automatically
        ▼
 ┌──────────────────────── Main.java ────────────────────────┐
 │  /        → static files from ./public  (CSP, nosniff…)    │
 │  /api/*   → Api.handle                                     │
 └───────────────────────────┬────────────────────────────────┘
                             ▼
 Api.java  (Router → Service)
   • parse JSON, authenticate cookie → user
   • RBAC: needRole(STUDENT | ADMIN)
   • validate input, map entities → DTOs (masking)
                             ▼
 Store.java  (Repository)            Cashback.java (pure function)
   • synchronized LOCK = atomic         • no DB lookups, no floats
     pay / top-up / cap update
   • idempotency index, accumulators, audit log
```

The layering follows the Router → Service → Repository separation from the Lab 3
architecture. All state mutation happens inside `synchronized (Store.LOCK)` so a payment's
balance debit, cashback credit, cap accumulation, transaction record and audit entry are
one atomic unit.

---

## 7. Data design

### 7.1 Storage choice

**Option A: RDBMS-only** semantics. Everything FinPay stores is structured and relational
(balances, ledger, rules). No documents, search or vector workloads exist, so no polyglot
persistence is used. This build keeps the relations in memory; see
[Limitations](#16-limitations-and-roadmap) for moving to PostgreSQL.

### 7.2 Entities and relationships

```
STUDENT 1 ──── 1 WALLET
WALLET  1 ──── N TRANSACTION
MERCHANT 1 ─── N TRANSACTION
CASHBACK_RULE 1 ─ N MERCHANT          (rule is keyed by MerchantCategory)
WALLET 1 ──── N MONTHLY_CASHBACK_ACCUMULATOR
```

### 7.3 Normalized relations (3NF)

| Relation | Key attributes | Constraints |
|---|---|---|
| `STUDENT` | **StudentID** PK, FullName, Email, PasswordHash, Phone, Role | Email unique (enforced on register) |
| `WALLET` | **WalletID** PK, StudentID FK, BalanceInPaise, Tier | `BalanceInPaise >= 0`; 1:1 with student |
| `MERCHANT` | **MerchantID** PK, MerchantName, MerchantCategory | |
| `CASHBACK_RULE` | **MerchantCategory** PK, BaseRatePercent (stored as basis points), BaseMonthlyCapInPaise | `rate >= 0` |
| `TRANSACTION` | **TransactionID** PK, WalletID FK, MerchantID FK, AmountInPaise, CashbackEarnedInPaise, IdempotencyKey, Status, Timestamp | `AmountInPaise > 0`; **UNIQUE(IdempotencyKey)** |
| `MONTHLY_CASHBACK_ACCUMULATOR` | **AccumulatorID** PK, WalletID FK, MerchantCategory, MonthYear, AccumulatedCashbackInPaise | **UNIQUE(WalletID, MerchantCategory, MonthYear)** |
| `AUDIT_LOG` | Id, Timestamp, Actor, Action, Entity, Detail | **Append-only** |

Functional dependencies honoured by the decomposition:

```
StudentID        → FullName, Email, PasswordHash, Phone
WalletID         → StudentID, BalanceInPaise, Tier
MerchantID       → MerchantName, MerchantCategory
MerchantCategory → BaseRatePercent, BaseMonthlyCapInPaise
TransactionID    → WalletID, MerchantID, AmountInPaise, CashbackEarnedInPaise, IdempotencyKey, Status, Timestamp
AccumulatorID    → WalletID, MerchantCategory, MonthYear, AccumulatedCashbackInPaise
```

### 7.4 Money representation

All monetary fields (`balance`, `amount`, `cashback`, `cap`) are `long` **paise**
(₹1 = 100 paise). The frontend converts rupee text to paise with string parsing
(`toPaise`), never with floating-point multiplication.

---

## 8. Algorithm: `calculate_cashback`

**Selected design: Alternative B, integer-paise multiplier with floor truncation**
(rejected: Alt A, floating-point rupee maths, because of IEEE 754 drift).

Located in `src/Cashback.java`.

### Inputs
| Input | Type | Meaning |
|---|---|---|
| `amountInPaise` | `long` | Transaction amount; must be `> 0` |
| `category` | `String` | Merchant category |
| `tier` | `String` | `BASIC` / `PRIME` / `PREMIUM` |
| `rateBp` | `long` | Base rate in basis points (5.00% = 500) |

### Pseudocode

```
function calculate_cashback(amountInPaise, category, tier, rateBp):
    if amountInPaise <= 0:           raise "amount must be positive"
    if category == "TOPUP" or rateBp <= 0:  return 0        # zero-rate guard
    mult = tierMultiplierPercent(tier)                       # 100 | 125 | 150
    return (amountInPaise * rateBp * mult) / 1_000_000       # one integer division = floor
```

The raw result is **before** the monthly cap. The service layer then applies the cap:

```
remaining = max(0, rule.capInPaise - accumulator(wallet, category, month))
cashback  = min(raw, remaining)
```

### Worked examples

| Amount | Category | Tier | Calculation | Result |
|---|---|---|---|---|
| ₹185.50 | FOOD (5%) | PRIME (1.25×) | `18550 × 500 × 125 / 1 000 000` = 1159.375 | **1159 paise** (₹11.59) |
| ₹100.00 | BOOKS (8%) | BASIC | `10000 × 800 × 100 / 1 000 000` = 800 | **800 paise** (₹8.00) |
| ₹0.20 | FOOD | BASIC | `20 × 500 × 100 / 1 000 000` = 1 | **1 paise** |
| ₹0.10 | FOOD | BASIC | `10 × 500 × 100 / 1 000 000` = 0.5 | **0 paise** (floor) |
| any | TOPUP | any | zero-rate guard | **0** |

### Edge cases handled
- Amount `<= 0` is rejected; amounts above ₹1,00,00,000 are rejected (overflow guard).
- `Math.multiplyExact` throws instead of silently overflowing.
- Top-ups never earn cashback.
- Cap already exhausted gives 0 cashback, and the UI says so in the preview.
- Fractional paise are always floored, never rounded up.

---

## 9. Security design

Three decisions from the DDR, each selected from two alternatives.

### 9.1 Authentication and authorization (selected: stateless JWT in HttpOnly cookie + server-side RBAC)

- Passwords hashed with **PBKDF2-HMAC-SHA256**, 120,000 iterations, 16-byte random salt,
  constant-time comparison.
- Session is an **HS256 JWT** with claims `sub` (StudentID), `role`, `exp` (1 hour).
- Cookie flags: `HttpOnly; SameSite=Strict; Path=/; Max-Age=3600`. The token is **never**
  exposed to JavaScript, which mitigates theft via XSS (the DDR rejected `localStorage`).
- Roles are enforced on the server for every route (`needRole`). The role is **never read
  from the client**: a registration body containing `"role":"ADMIN"` still produces a student.
- Brute-force protection: 5 failed logins per email triggers a 60-second lockout (HTTP 429).

### 9.2 Sensitive-data protection (selected: role-based DTO field masking)

- Students can only read **their own** wallet, transactions and cashback summary.
- Admin responses mask emails (`a•••@college.edu`) and never include phone numbers or
  password hashes.
- Entities are never serialized directly; each response is an explicit DTO map.

### 9.3 Integrity and audit (selected: append-only log of sensitive state changes)

Logged events: `REGISTER`, `LOGIN`, `LOGIN_FAILED`, `TOPUP`, `PAYMENT`, `RULE_UPDATED`,
`TIER_CHANGED`. Entries are only ever appended and are visible to coordinators only.

### 9.4 Additional hardening in this build
- `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin` on static pages; `Cache-Control: no-store` on API responses.
- Static file server resolves and normalizes paths, so `../` traversal returns 404.
- All HTML rendered from API data is escaped in the frontend (`esc()`).
- Request bodies are capped at 64 KB; malformed JSON returns 400.

> Security *testing* (pen-testing, fuzzing) is out of scope, as stated in the lab manual.

---

## 10. REST API reference

All bodies are JSON. Amounts are **integer paise**. Authentication is via the
`finpay_token` HttpOnly cookie set at login/registration.

`Idempotency-Key` header: required on `POST /api/pay` and `POST /api/wallet/topup`;
8 to 64 characters from `A-Za-z0-9_-` (a UUID works well).

### Auth

| Method | Path | Auth | Body | Response |
|---|---|---|---|---|
| POST | `/api/auth/register` | none | `fullName`, `email`, `password` (≥ 8), `phone` (optional, 10 digits) | user object, sets cookie |
| POST | `/api/auth/login` | none | `email`, `password` | user object, sets cookie |
| POST | `/api/auth/logout` | none | n/a | `{ok:true}`, clears cookie |
| GET | `/api/me` | any | n/a | current user |

### Student

| Method | Path | Auth | Body / notes |
|---|---|---|---|
| GET | `/api/merchants` | any | list with `category`, `icon`, `rateBp` |
| GET | `/api/wallet` | STUDENT | `balanceInPaise`, `tier`, `tierMultiplierPercent` |
| GET | `/api/transactions` | STUDENT | latest 50, newest first |
| GET | `/api/cashback/summary` | STUDENT | per-category earned vs cap for the current month, lifetime totals |
| POST | `/api/cashback/preview` | STUDENT | `{merchantId, amountInPaise}` → `rawInPaise`, `cashbackInPaise`, `capRemainingInPaise`, `capped` |
| POST | `/api/wallet/topup` | STUDENT | `{amountInPaise}` + `Idempotency-Key` |
| POST | `/api/pay` | STUDENT | `{merchantId, amountInPaise}` + `Idempotency-Key` |

`/api/pay` and `/api/wallet/topup` return:
```json
{ "transaction": { "id": 129, "type": "PAYMENT", "merchant": "Campus Café",
    "category": "FOOD", "amountInPaise": 18550, "cashbackInPaise": 1159,
    "status": "SUCCESS", "timestamp": "2026-11-14T08:19:04Z" },
  "balanceInPaise": 283655, "replayed": false }
```
Re-sending the same `Idempotency-Key` returns the **original** transaction with
`"replayed": true` and does **not** debit again.

### Admin

| Method | Path | Body / notes |
|---|---|---|
| GET | `/api/admin/overview` | KPIs + masked student list |
| GET | `/api/admin/audit` | latest 60 audit entries |
| GET | `/api/admin/rules` | all category rules |
| PUT | `/api/admin/rules` | `{category, rateBp, capInPaise}` (rate 0 to 10000 bp; TOPUP is locked at 0) |
| PUT | `/api/admin/students/{id}/tier` | `{tier: "BASIC" \| "PRIME" \| "PREMIUM"}` |

### Status codes

| Code | Meaning |
|---|---|
| 200 | Success |
| 400 | Malformed JSON, missing/invalid `Idempotency-Key`, non-integer amount |
| 401 | Not signed in, invalid or expired token, wrong credentials |
| 403 | Signed in but wrong role |
| 404 | Unknown route, merchant or student |
| 409 | Email already registered, or idempotency key used by another wallet |
| 422 | Validation failure (amount range, insufficient balance, weak password…) |
| 429 | Login lockout |
| 500 | Unexpected server error (details are logged, never returned) |

---

## 11. Trying the API

### curl (macOS / Linux / Git Bash)

```bash
B=http://localhost:8080

# sign in and keep the cookie
curl -c jar.txt -H 'Content-Type: application/json' \
  -d '{"email":"aarav@college.edu","password":"Student@123"}' $B/api/auth/login

curl -b jar.txt $B/api/wallet
curl -b jar.txt $B/api/merchants

# pay ₹185.50 at a merchant (use an id from /api/merchants)
curl -b jar.txt -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-key-0001' \
  -d '{"merchantId":104,"amountInPaise":18550}' $B/api/pay

# run the same command again: you get replayed:true and no second charge
```

### PowerShell

```powershell
$B = "http://localhost:8080"
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession

Invoke-RestMethod "$B/api/auth/login" -Method Post -WebSession $s -ContentType "application/json" `
  -Body '{"email":"aarav@college.edu","password":"Student@123"}'

Invoke-RestMethod "$B/api/wallet" -WebSession $s
Invoke-RestMethod "$B/api/pay" -Method Post -WebSession $s -ContentType "application/json" `
  -Headers @{ "Idempotency-Key" = "demo-key-0001" } `
  -Body '{"merchantId":104,"amountInPaise":18550}'
```

### Manual test checklist

- [ ] Unauthenticated `GET /api/wallet` → 401
- [ ] Student calling `/api/admin/audit` → 403
- [ ] Same `Idempotency-Key` twice → balance changes once, second call `replayed: true`
- [ ] Payment larger than balance → 422 "Insufficient wallet balance"
- [ ] `amountInPaise: 10.5` → 400 (must be a whole number)
- [ ] Registration with `"role":"ADMIN"` → created as `STUDENT`
- [ ] Tampered cookie → 401
- [ ] Keep paying at one category until the monthly cap is hit → cashback becomes 0

---

## 12. Design decisions traced to the DDR

| Area | AI silent decision | Human decision | Implemented as |
|---|---|---|---|
| ER / money | Float rupees for balances | **Rejected**: integer paise | `long` paise everywhere |
| ER / keys | IdempotencyKey as PK | **Modified**: TransactionID PK + UNIQUE(IdempotencyKey) | `Store.byKey` index; txn id from sequence |
| ER / accumulator | UNIQUE(Wallet, Category, Month) | Accepted | `Store.accumulators` key `wallet|category|month` |
| Normalization | 6 decomposed relations, CHECK constraints | Accepted | Balance ≥ 0, amount > 0, rate ≥ 0 enforced in code |
| Algorithm | Float calculation (Alt A) | **Selected Alt B**: integer multiplier + floor | `Cashback.calculate` |
| Auth | Dedicated OAuth2 server (Alt A) | **Selected Alt B**: stateless JWT + HttpOnly cookie + RBAC | `Auth.java`, `Api.needRole` |
| Sensitive data | Separate role endpoints (Alt B) | **Selected Alt A**: DTO masking | `Api.overview`, `maskEmail` |
| Audit | Log every request (Alt B) | **Selected Alt A**: state changes only, append-only | `Store.log` |

---

## 13. Assumptions

The design document does not specify the following, so they are explicit assumptions you
can change:

1. **Tier multipliers**: BASIC 1.00×, PRIME 1.25×, PREMIUM 1.50× (`Cashback.tierMultiplierPercent`).
2. **Rates and caps**: the seeded values in [section 4](#4-demo-accounts-and-seed-data); editable by coordinators at runtime.
3. **Rate precision**: `BaseRatePercent` is stored as integer basis points (hundredths of a percent) to avoid decimals.
4. **Month boundary**: monthly caps reset on the 1st, evaluated in `Asia/Kolkata` time.
5. **Tier changes**: manual, by a coordinator. No automatic upgrade rule is defined.
6. **Transaction size guard**: 1 paise to ₹1,00,00,000, purely a technical overflow guard, not a business rule.
7. **Top-up** is modelled as a transaction with the `TOPUP` merchant category (always 0% cashback).
8. **Session lifetime**: 1 hour, with no refresh token.

---

## 14. Configuration

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `FINPAY_JWT_SECRET` | random per start | Signing secret (≥ 32 chars). Set it to keep sessions valid across restarts |
| `FINPAY_PUBLIC` | `public` | Directory of static frontend files |

Examples:

```powershell
# PowerShell
$env:PORT = 9090; $env:FINPAY_JWT_SECRET = "change-me-to-a-long-random-string-123456"; .\run.bat
```
```bash
# macOS / Linux
PORT=9090 FINPAY_JWT_SECRET="change-me-to-a-long-random-string-123456" ./run.sh
```

---

## 15. Troubleshooting

| Problem | Fix |
|---|---|
| `javac is not recognized` | Install a **JDK** (not only a JRE) from adoptium.net, then reopen the terminal |
| `.\run.bat` not recognized in PowerShell | Include the `.\` prefix and `cd` into the `finpay` folder first |
| `Address already in use` | Another app uses the port. Set `PORT` to something else (see section 14) |
| Blank page or 404 | Run the server **from the project root** so `public/` is found, or set `FINPAY_PUBLIC` |
| Fonts look plain | Google Fonts needs internet; the site still works with system fonts |
| Logged out after restart | Expected: the random JWT secret and in-memory data reset. Set `FINPAY_JWT_SECRET` to keep tokens valid (data still resets) |
| "Too many attempts" at login | Wait 60 seconds after 5 failed logins |

---

## 16. Limitations and roadmap

**Current limitations**
- Data is **in memory**: it resets on restart and is not shared across instances.
- Plain HTTP only. For any real deployment put it behind HTTPS and add the `Secure` cookie flag.
- No real payment gateway or bank integration; top-ups simply credit the wallet.
- No password reset, email verification or refresh tokens.

**Suggested next steps**
1. Replace `Store.java` with a JDBC/PostgreSQL repository using the schema in section 7
   (add `CHECK` and `UNIQUE` constraints, use a DB transaction around `pay`).
2. Add `Secure` to the cookie and serve over TLS.
3. Add pagination for transactions and the audit log.
4. Add automated tests (JUnit) for `Cashback.calculate` and the cap logic.
5. Automatic tier promotion rules once the requirement is approved.

---

<div align="center">

Built from the FinPay Detailed Design Record · Money is always integer paise 💜

</div>
