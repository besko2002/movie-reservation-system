# Movie Reservation System — REST API

A cinema booking backend: browse movies and showtimes, hold seats for a few minutes, pay through
Stripe Checkout, and cancel with a full refund before the show. API only — no frontend; explore it
with Swagger UI at <http://localhost:8080/swagger-ui.html>.

## Overview & architecture

**Modular monolith**: one Spring Boot application and one PostgreSQL database, split into modules
that each own their tables, entities and repositories. A module never touches another module's
repository — it calls that module's public service.

```
                ┌──────────────────── client (Swagger UI / Postman) ────────────────────┐
                │                                                                       │
  users ──── movies ──── theaters ──── seatreservation ──── tickets ────────────► Stripe
    │           │            │               │                  │
    └───────────┴────────────┴─── PostgreSQL (one database, Flyway V1–V7) ───────┘
```

| Module            | Responsibility |
| ----------------- | -------------- |
| `users`           | Register / login → JWT, roles `ADMIN` / `USER`, seeded admin, current profile. |
| `movies`          | Movies, genres and schedules (showtimes). `end_time` is computed from the movie duration plus a cleaning buffer; overlapping shows in the same hall are rejected. |
| `theaters`        | Theaters (halls); creating one generates its seat grid (rows × seats per row, `NORMAL` / `VIP`). |
| `seatreservation` | Seat map per showtime, timed seat holds, and a scheduled sweeper that releases expired holds. |
| `tickets`         | Checkout through a `PaymentGateway` port (Stripe implementation), signature-verified webhook, cancellation + refund, admin reports. |
| `security`        | `JwtService`, `JwtAuthenticationFilter`, stateless `SecurityConfig`. |
| `common`          | `ApiError`, `PageResponse`, exception types and the global exception handler. |
| `config`          | `OpenApiConfig` (Swagger metadata), `SchedulingConfig`. |

## Tech stack

Java 21 · Spring Boot 3.5.16 (Web, Data JPA, Validation, Security, Actuator) · Maven (wrapper) ·
PostgreSQL 16 · Flyway · jjwt 0.12 (HS256) · stripe-java 29 · springdoc-openapi 2.9 (Swagger UI) ·
JUnit 5 / Mockito / Testcontainers · Docker & Docker Compose.

## Prerequisites

- **JDK 21** (`java -version` → 21). On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **Docker Desktop** (or another Docker engine) — needed for PostgreSQL, for the Testcontainers
  tests and for the container image.
- Optional: the [Stripe CLI](https://docs.stripe.com/stripe-cli) to exercise real payments.
- No local Maven install required; use `./mvnw`.

## Quick start

Copy the example environment first (optional for local runs — every value has a sane default):

```bash
cp .env.example .env    # then edit secrets; .env is git-ignored
```

### (a) Database in Docker, application from Maven

```bash
docker compose up -d                                   # starts only PostgreSQL (host port 5434)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

- API: <http://localhost:8080>
- Swagger UI: <http://localhost:8080/swagger-ui.html> · OpenAPI JSON: `/v3/api-docs`
- Health: <http://localhost:8080/actuator/health>

Flyway migrations `V1`–`V7` run at startup and the admin user is seeded if missing.

### (b) Everything in Docker

```bash
docker compose --profile app up -d --build --wait      # PostgreSQL + the API image
curl -s localhost:8080/actuator/health                 # {"status":"UP"}
docker compose --profile app down                      # stop (named volume is kept)
```

The `app` service sits behind the `app` compose profile on purpose, so a plain
`docker compose up -d` still starts **only** PostgreSQL. Inside the compose network the app talks to
`postgres:5432`; `5434` is only the host-side mapping for local runs.

Build the image on its own with:

```bash
docker build -t movie-reservation-system:local .
```

Multi-stage: `maven:3.9.11-eclipse-temurin-21` builds the fat jar with `./mvnw -DskipTests package`,
`eclipse-temurin:21-jre` runs it as the non-root user `app`, exposes `8080` and has a
`HEALTHCHECK` that curls `/actuator/health`.

## Configuration

Every setting is an environment variable (`src/main/resources/application.yml` holds the defaults).

| Variable | Default | Meaning |
| -------- | ------- | ------- |
| `DB_URL` | `jdbc:postgresql://localhost:5434/movie_reservation` | JDBC URL. In compose the `app` service overrides it with `jdbc:postgresql://postgres:5432/movie_reservation`. |
| `DB_USERNAME` | `movie` | Database user. |
| `DB_PASSWORD` | `movie` | Database password. |
| `JWT_SECRET` | dev-only placeholder (≥ 32 bytes) | HS256 signing key. Must be at least 32 bytes or the app refuses to start. **Always override outside development.** |
| `JWT_EXPIRATION` | `3600000` | Access-token lifetime in milliseconds (1 hour). |
| `ADMIN_EMAIL` | `admin@cinema.local` | Seeded administrator's email (created at startup when absent). |
| `ADMIN_PASSWORD` | `Admin@12345` | Seeded administrator's password. |
| `ADMIN_NAME` | `System Admin` | Seeded administrator's display name. |
| `STRIPE_SECRET_KEY` | *(empty)* | Stripe API key (`sk_test_…`). Empty ⇒ payments disabled: endpoints that need a provider answer `503`. |
| `STRIPE_WEBHOOK_SECRET` | *(empty)* | Webhook signing secret (`whsec_…`); the raw body is verified with HMAC-SHA256. |
| `STRIPE_CURRENCY` | `usd` | ISO 4217 currency of every Checkout Session and refund. |
| `TICKET_CANCELLATION_CUTOFF_HOURS` | `2` | A PAID ticket can be cancelled (full refund) only until this many hours before the showtime. |
| `TICKET_CHECKOUT_MINUTES` | `30` | Payment window: both the Checkout Session's `expires_at` and the extended seat-hold expiry. Stripe accepts 30–1440; outside that range the app fails to start. |
| `SCHEDULE_BUFFER_MINUTES` | `15` | Cleaning time added after the movie duration when computing a schedule's `end_time`. |
| `SCHEDULE_ZONE` | `UTC` | Business time zone used to interpret `GET /api/schedules?date=yyyy-MM-dd`. |
| `RESERVATION_HOLD_MINUTES` | `10` | How long a seat hold stays valid. |
| `RESERVATION_MAX_SEATS` | `10` | Maximum seats per hold request. |
| `RESERVATION_SWEEP_MS` | `60000` | Interval of the expired-hold sweeper, in milliseconds. |
| `APP_BASE_URL` | `http://localhost:8080` | Absolute base URL used to build Stripe success/cancel return URLs. |

### Seeded admin — change it

On every startup the application creates the administrator if that email is not present yet:

```
email:    admin@cinema.local
password: Admin@12345
```

> **Warning:** these are development credentials. Set `ADMIN_EMAIL` / `ADMIN_PASSWORD` (and a real
> `JWT_SECRET`) before exposing the API anywhere — and change the password of any admin that was
> already seeded with the default.

## Endpoints

`public` = no token · `USER` = any valid bearer token · `ADMIN` = token of a user with role `ADMIN`.

### Authentication & users (`users`)

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `POST /api/auth/register` | public | Create an account (role `USER`) and return a token. |
| `POST /api/auth/login` | public | Exchange email + password for an access token. |
| `GET /api/users/me` | USER | Profile of the token owner. |

### Movies (`movies`)

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `GET /api/movies?genre=&title=&page=&size=&sort=` | public | Paged movie list with filters. |
| `GET /api/movies/{id}` | public | One movie including its genres. |
| `POST /api/movies` | ADMIN | Create a movie. |
| `PUT /api/movies/{id}` | ADMIN | Replace a movie (genre set included). |
| `DELETE /api/movies/{id}` | ADMIN | Delete a movie. |

### Genres (`movies`)

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `GET /api/genres` | public | All genres, sorted by name. |
| `GET /api/genres/{id}` | public | One genre. |
| `POST /api/genres` | ADMIN | Create a genre. |
| `PUT /api/genres/{id}` | ADMIN | Rename a genre. |
| `DELETE /api/genres/{id}` | ADMIN | Delete a genre. |

### Schedules / showtimes (`movies`)

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `GET /api/schedules?movieId=&theaterId=&date=&from=&to=&page=&size=&sort=` | public | Paged showtimes; `date` is a local date in `SCHEDULE_ZONE`, `from`/`to` are ISO-8601 instants compared as `[from, to)`. |
| `GET /api/schedules/{id}` | public | One showtime with movie, theater and prices. |
| `POST /api/schedules` | ADMIN | Create a showtime (`endTime` computed, overlaps rejected). |
| `PUT /api/schedules/{id}` | ADMIN | Replace a showtime (overlap check runs again). |
| `DELETE /api/schedules/{id}` | ADMIN | Delete a showtime. |

### Theaters (`theaters`)

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `GET /api/theaters?page=&size=&sort=` | public | Paged theater list. |
| `GET /api/theaters/{id}` | public | One theater including seat counts. |
| `GET /api/theaters/{id}/seats` | public | Seat grid, ordered by row label then seat number. |
| `POST /api/theaters` | ADMIN | Create a theater and generate its seats. |
| `PUT /api/theaters/{id}` | ADMIN | Replace a theater; seats are regenerated only when the grid changes. |
| `DELETE /api/theaters/{id}` | ADMIN | Delete a theater and its seats. |

### Seat reservations (`seatreservation`)

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `GET /api/schedules/{scheduleId}/seats` | public | Seat map with per-seat price and status `AVAILABLE` / `HELD` / `BOOKED`. |
| `POST /api/seat-reservations` | USER | Hold seats `{scheduleId, seatIds[]}` for `RESERVATION_HOLD_MINUTES`. |
| `GET /api/seat-reservations/me` | USER | The caller's reservations that still occupy a seat. |
| `DELETE /api/seat-reservations/{id}` | USER | Release one of your own holds (an ADMIN may release any). |

### Tickets & payments (`tickets`)

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `POST /api/tickets` | USER | Checkout `{scheduleId}` → `PENDING_PAYMENT` ticket + Stripe `checkoutUrl`. Idempotent per (user, schedule): `201` when created, `200` when an existing pending ticket is returned. `503` when Stripe is not configured. |
| `GET /api/tickets/me` | USER | The caller's tickets, newest first. |
| `GET /api/tickets/{id}` | USER | One ticket (your own; an ADMIN may read any). |
| `DELETE /api/tickets/{id}` | USER | Cancel a ticket; a PAID one is refunded in full if the cutoff has not passed. |
| `POST /api/payments/stripe/webhook` | Stripe signature | Webhook deliveries; public route, authenticated by the `Stripe-Signature` header. |

### Admin reports (`tickets`)

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `GET /api/admin/tickets?status=&scheduleId=&userId=&page=&size=` | ADMIN | Every ticket, newest first. |
| `GET /api/admin/reports/revenue?from=&to=` | ADMIN | Revenue of a window (`from` defaults to 30 days before `to`, `to` to now). |
| `GET /api/admin/reports/occupancy/{scheduleId}` | ADMIN | How full one showtime is. |

### Operations

| Method & path | Auth | Description |
| ------------- | ---- | ----------- |
| `GET /actuator/health` | public | Liveness/readiness (`{"status":"UP"}`); used by the container `HEALTHCHECK`. |
| `GET /swagger-ui.html` | public | Swagger UI. |
| `GET /v3/api-docs` | public | OpenAPI 3 document. |

## Booking flow, end to end

All examples assume `BASE=http://localhost:8080`.

**1 — Register (or log in).**

```bash
BASE=http://localhost:8080

curl -s -X POST "$BASE/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Ada Lovelace","email":"ada@example.com","password":"Str0ng@Pass1"}'

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"Str0ng@Pass1"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
```

The admin token comes from the same endpoint:

```bash
ADMIN_TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@cinema.local","password":"Admin@12345"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
```

**2 — Find a showtime and its free seats.** (An admin creates the catalogue first: theater → movie →
schedule.)

```bash
curl -s "$BASE/api/schedules?page=0&size=5"
curl -s "$BASE/api/schedules/1/seats"     # seatId, rowLabel, seatNumber, type, price, status
```

**3 — Hold the seats** (`HELD` for `RESERVATION_HOLD_MINUTES`, 10 by default):

```bash
curl -s -X POST "$BASE/api/seat-reservations" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"scheduleId":1,"seatIds":[12,13]}'
# → {"reservationIds":[...],"scheduleId":1,"seats":[...],"totalPrice":...,"expiresAt":"..."}
```

**4 — Checkout**: turns every seat you hold in that schedule into a `PENDING_PAYMENT` ticket and
returns the hosted Stripe payment page. The holds are extended to the payment deadline, so the
sweeper cannot free the seats while the buyer is paying.

```bash
curl -s -X POST "$BASE/api/tickets" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"scheduleId":1}'
# → {"ticketId":7,"status":"PENDING_PAYMENT","totalPrice":25.00,"currency":"usd",
#    "checkoutUrl":"https://checkout.stripe.com/c/pay/cs_test_…","expiresAt":"…"}
```

Without `STRIPE_SECRET_KEY` this answers `503` with an `ApiError` — everything up to step 3 still
works.

**5 — Pay; Stripe calls the webhook.** Open `checkoutUrl`, pay with the test card
`4242 4242 4242 4242`. Stripe then POSTs `checkout.session.completed` to:

```bash
# Stripe (or the Stripe CLI) sends this; the signature header is what authenticates it.
curl -s -X POST "$BASE/api/payments/stripe/webhook" \
  -H 'Content-Type: application/json' \
  -H 'Stripe-Signature: t=1700000000,v1=<hmac-sha256-of-"t.payload"-with-STRIPE_WEBHOOK_SECRET>' \
  --data-binary '{"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"cs_test_123"}}}'
```

A verified `checkout.session.completed` marks the ticket **PAID** and its reservations `CONFIRMED`;
a failed or expired session cancels the ticket and releases the seats. An invalid signature is
rejected with `400` and changes nothing.

**6 — Check the result, cancel if needed.**

```bash
curl -s "$BASE/api/tickets/me" -H "Authorization: Bearer $TOKEN"            # status PAID
curl -s -X DELETE "$BASE/api/tickets/7" -H "Authorization: Bearer $TOKEN"   # full refund + seats released
```

If nothing is paid before the deadline, the expiry sweeper releases the holds and the seats become
available again.

## How double booking is prevented

Two layers, and the decisive one is in the database:

1. **Partial unique index** (`V6__create_seat_reservations.sql`):

   ```sql
   CREATE UNIQUE INDEX ux_active_seat_per_schedule
       ON seat_reservations (schedule_id, seat_id)
       WHERE status IN ('HELD', 'CONFIRMED');
   ```

   Only *active* rows take part in the uniqueness scope, so a `RELEASED` (expired or cancelled) hold
   never blocks a later booking of the same seat, while two concurrent attempts to hold seat *S* of
   showtime *X* can never both exist.

2. **One transaction per hold request.** The hold runs inside a single transaction that first checks
   the seat map and then inserts the reservations; if a concurrent transaction wins the race, the
   index raises a constraint violation, which the service translates into `409 Conflict` and the
   whole request is rolled back — no partial hold is ever left behind. Nothing relies on
   application-level locking or on read-then-write timing, which is why the concurrency test can
   fire parallel requests at the same seat and still see exactly one winner.

## Stripe setup (test mode)

1. Create a Stripe account and copy the **test** secret key (`sk_test_…`) from the dashboard.
2. Start the webhook listener — it prints the signing secret (`whsec_…`):

   ```bash
   stripe login
   stripe listen --forward-to localhost:8080/api/payments/stripe/webhook
   ```

3. Put both values into `.env` and restart the app:

   ```dotenv
   STRIPE_SECRET_KEY=sk_test_xxxxxxxxxxxxxxxxxxxxxxxx
   STRIPE_WEBHOOK_SECRET=whsec_xxxxxxxxxxxxxxxxxxxxxxxx
   STRIPE_CURRENCY=usd
   ```

4. Check out a ticket (step 4 above), pay on the returned `checkoutUrl` with test card
   `4242 4242 4242 4242`, any future expiry and any CVC. The CLI forwards the event and the ticket
   flips to `PAID`.

Leaving both variables empty is a supported mode: the app starts, `DisabledPaymentGateway` is used,
and the endpoints that need a provider answer `503` instead of failing with a `500`.

## Running the tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean verify
```

173 tests: unit tests plus `@SpringBootTest` slices that start a **real PostgreSQL 16 via
Testcontainers** — Docker must be running. No test ever calls Stripe over the network: every payment
goes through the `PaymentGateway` port, which is stubbed in tests.

## Project structure

```
movie-reservation-system/
├── Dockerfile                  # multi-stage build (Maven+JDK 21 → JRE 21, non-root, healthcheck)
├── .dockerignore
├── docker-compose.yml          # postgres (always) + app (profile "app")
├── .env.example                # every environment variable with a safe placeholder
├── PLAN.md                     # architecture, schema and phase plan
├── README.md
├── mvnw, mvnw.cmd, .mvn/       # Maven wrapper
├── pom.xml
└── src
    ├── main
    │   ├── java/com/example/moviereservation
    │   │   ├── MovieReservationSystemApplication.java
    │   │   ├── common/             # ApiError, PageResponse, exceptions, global handler
    │   │   ├── config/             # OpenApiConfig, SchedulingConfig
    │   │   ├── movies/             # movies, genres, schedules
    │   │   ├── seatreservation/    # seat map, holds, expiry sweeper
    │   │   ├── security/           # JwtService, JwtAuthenticationFilter, SecurityConfig
    │   │   ├── theaters/           # theaters + seat generation
    │   │   ├── tickets/            # checkout, Stripe gateway, webhook, refunds, reports
    │   │   └── users/              # register/login, profile, admin seeding
    │   └── resources
    │       ├── application.yml
    │       └── db/migration/       # V1__baseline … V7__create_tickets
    └── test/java/com/example/moviereservation
        ├── TestcontainersConfiguration.java   # shared PostgreSQL 16 container
        ├── ApiDocsTest.java, ApplicationContextAndFlywayTest.java
        └── common/ movies/ seatreservation/ security/ theaters/ tickets/ users/
```

## License

Apache License 2.0.
