# Movie Reservation System — Plan

## Architecture
**Modular Monolith**: one Spring Boot app, one PostgreSQL database, split into
5 modules that mirror the service diagram:

```
                 ┌──────────────── Movie Tickets App (client) ────────────────┐
                 │                                                            │
   Users Service   Theaters Service   Seats Reservation Service   Movies Service   Tickets Service ── Stripe
        │                 │                     │                      │                │
        └─────────────────┴──────────── PostgreSQL (one DB) ───────────┴────────────────┘
```

Module rules:
- Each module owns its tables, entities and repositories.
- A module never uses another module's repository; it calls that module's public service interface.
- Internal dependencies (dashed arrows in the diagram):
  - Theaters <-> Movies (a schedule needs a movie and a theater)
  - Seats Reservation <-> Tickets (a ticket is built from held seats)
  - Tickets -> Movies (schedule info and price)

## Stack
Java 21, Spring Boot 3.x, **Maven**, Spring Web, Spring Data JPA, PostgreSQL 16, Flyway,
Spring Security + JWT (jjwt), Validation, Lombok, MapStruct, springdoc-openapi,
stripe-java, JUnit 5 / Mockito / Testcontainers, Docker Compose.

## Modules

### 1. Users Service
- Register / login -> JWT. Roles: ADMIN, USER. Seeded admin.
- Current user profile.

### 2. Theaters Service
- Admin CRUD for theaters (halls).
- Creating a theater generates its seats (rows x seats per row, seat type NORMAL/VIP).

### 3. Movies Service
- Admin CRUD for movies and genres; public browse + filter by genre.
- Movie Schedules (showtimes): movie + theater + start time + price.
  end_time = start_time + duration; no overlapping schedules in the same theater.

### 4. Seats Reservation Service
- Seat map for a schedule (available / held / booked).
- Hold seats for a user for 10 minutes (status HELD, expires_at).
- Scheduled job releases expired holds.
- Double booking is prevented by a DB partial unique index (see schema).

### 5. Tickets Service
- Create ticket from the user's held seats -> Stripe Checkout Session -> return payment URL.
- Stripe webhook (`checkout.session.completed`) -> ticket PAID, seats CONFIRMED.
- Payment failed / session expired -> ticket CANCELLED, seats RELEASED.
- User: list my tickets, cancel an upcoming PAID ticket -> full Stripe refund
  (Refund API on the payment intent), ticket REFUNDED, seats RELEASED.
  Cancel allowed only before the schedule starts (cutoff configurable, e.g. 2 hours).
- Admin reports: all tickets, occupancy per schedule, revenue.

## Booking flow
1. User opens a schedule -> `GET /api/schedules/{id}/seats`.
2. User selects seats -> `POST /api/seat-reservations` -> seats HELD for 10 min.
3. User checks out -> `POST /api/tickets` -> ticket PENDING_PAYMENT + Stripe checkout URL.
4. User pays on Stripe -> Stripe calls `POST /api/payments/stripe/webhook`.
5. Webhook verified -> ticket PAID, seat reservations CONFIRMED.
6. If nothing is paid before expiry -> scheduler releases the seats.
7. User cancels before cutoff -> `DELETE /api/tickets/{id}` -> Stripe refund -> ticket REFUNDED, seats RELEASED.

## Schema (Flyway)
```
-- users
users(id, name, email UNIQUE, password_hash, role, created_at)

-- theaters
theaters(id, name, location, total_rows, seats_per_row)
seats(id, theater_id, row_label, seat_number, type)    -- UNIQUE(theater_id,row_label,seat_number)

-- movies
genres(id, name UNIQUE)
movies(id, title, description, poster_url, duration_minutes, release_date)
movie_genres(movie_id, genre_id)
movie_schedules(id, movie_id, theater_id, start_time, end_time, base_price, vip_price)
        -- seat price = base_price for NORMAL seats, vip_price for VIP seats (vip_price > base_price)

-- tickets
tickets(id, user_id, schedule_id, total_price, status, stripe_session_id,
        stripe_payment_intent_id, created_at)
        -- status: PENDING_PAYMENT | PAID | CANCELLED | REFUNDED

-- seat reservation
seat_reservations(id, user_id, schedule_id, seat_id, ticket_id NULL, price,
                  status, expires_at, created_at)
        -- status: HELD | CONFIRMED | RELEASED
        -- CREATE UNIQUE INDEX ux_active_seat ON seat_reservations(schedule_id, seat_id)
        --   WHERE status IN ('HELD','CONFIRMED');   <- prevents double booking
```

## API
```
Users
POST   /api/auth/register                          public
POST   /api/auth/login                             public
GET    /api/users/me                               USER

Theaters
GET    /api/theaters                               public
POST/PUT/DELETE /api/theaters/**                   ADMIN

Movies
GET    /api/movies?genre=&page=                    public
GET    /api/movies/{id}                            public
POST/PUT/DELETE /api/movies/**                     ADMIN
CRUD   /api/genres                                 ADMIN
GET    /api/schedules?date=&movieId=               public
POST/PUT/DELETE /api/schedules/**                  ADMIN

Seats Reservation
GET    /api/schedules/{id}/seats                   public
POST   /api/seat-reservations                      USER  {scheduleId, seatIds[]}
DELETE /api/seat-reservations/{id}                 USER  (release own hold)

Tickets
POST   /api/tickets                                USER  {scheduleId} -> {ticketId, checkoutUrl}
GET    /api/tickets/me                             USER
DELETE /api/tickets/{id}                           USER  (upcoming only)
POST   /api/payments/stripe/webhook                Stripe (signature verified)
GET    /api/admin/tickets                          ADMIN
GET    /api/admin/reports/revenue?from=&to=        ADMIN
GET    /api/admin/reports/occupancy/{scheduleId}   ADMIN
```

## Packages
```
com.example.moviereservation
├── users/              (controller, service, repository, entity, dto)
├── theaters/
├── movies/             (movies, genres, schedules)
├── seatreservation/
├── tickets/            (tickets, payment/stripe, reports)
├── security/           (JwtService, JwtAuthenticationFilter, SecurityConfig)
├── config/             (OpenApiConfig, StripeConfig)
└── common/             (exceptions, GlobalExceptionHandler, ApiError)
```

## Config (env vars, never hard-coded)
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION`,
`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `APP_BASE_URL`.

## Phases
1. Setup: Spring Initializr (Maven), docker-compose (Postgres), Flyway V1, app connects.
2. Users + Security: register/login, JWT filter, seeded admin, tests.
3. Movies: movies + genres CRUD.
4. Theaters: theaters + seat generation.
5. Movie Schedules: CRUD + overlap check.
6. Seats Reservation: seat map, hold, expiry scheduler, concurrency test.
7. Tickets + Stripe: checkout session, webhook, cancel + refund (test with Stripe CLI).
8. Reports.
9. Polish: Swagger, README, Dockerfile.

## Decisions
- Architecture: Modular Monolith, one PostgreSQL database.
- Build tool: Maven.
- Cancel a paid ticket -> full refund through Stripe (before cutoff).
- VIP seats cost more: each schedule has `base_price` and `vip_price`.
- API only, no frontend (tested via Swagger UI / Postman).
