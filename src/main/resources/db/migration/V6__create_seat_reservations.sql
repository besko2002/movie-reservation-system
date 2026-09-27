-- V6: seat reservations (phase 6).
-- Owned by the seat reservation module. Users, schedules and seats are referenced by id only; the
-- module never maps another module's entities.
--
-- Every foreign key is ON DELETE RESTRICT on purpose: a reservation (and, from phase 7 on, the
-- ticket built from it) must never be destroyed as a side effect of deleting a user, a schedule or
-- a seat grid. The seams ScheduleUsagePolicy / SeatUsagePolicy turn such attempts into a 409
-- before the database has to.
CREATE TABLE seat_reservations (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT         NOT NULL REFERENCES users(id)           ON DELETE RESTRICT,
    schedule_id BIGINT         NOT NULL REFERENCES movie_schedules(id) ON DELETE RESTRICT,
    seat_id     BIGINT         NOT NULL REFERENCES seats(id)           ON DELETE RESTRICT,
    -- Deliberately without a foreign key: the tickets table does not exist yet. Phase 7 creates
    -- tickets and adds the FK (ticket_id -> tickets(id)) in its own migration. Until then the
    -- column is only written by SeatReservationService#confirmHeldSeats.
    ticket_id   BIGINT         NULL,
    price       NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    status      VARCHAR(20)    NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED')),
    -- Only meaningful for HELD rows: the instant the hold stops being valid.
    expires_at  TIMESTAMPTZ    NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- THE core guarantee of this phase: at most one *active* reservation per (schedule, seat).
-- A partial unique index, so RELEASED rows are simply not part of the uniqueness scope and an
-- expired/cancelled hold never blocks a later booking of the same seat.
CREATE UNIQUE INDEX ux_active_seat_per_schedule
    ON seat_reservations (schedule_id, seat_id)
    WHERE status IN ('HELD', 'CONFIRMED');

-- "my reservations / my active holds".
CREATE INDEX idx_seat_reservations_user_status ON seat_reservations (user_id, status);

-- Seat map of a schedule, and the ScheduleUsagePolicy check.
CREATE INDEX idx_seat_reservations_schedule_status ON seat_reservations (schedule_id, status);

-- The expiry sweeper scans exactly this: HELD rows whose expires_at has passed.
CREATE INDEX idx_seat_reservations_status_expires ON seat_reservations (status, expires_at);

-- Reservations belonging to one ticket (phase 7).
CREATE INDEX idx_seat_reservations_ticket ON seat_reservations (ticket_id);
