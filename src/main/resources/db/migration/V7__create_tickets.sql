-- V7: tickets (phase 7).
-- Owned by the tickets module. The user and the schedule are referenced by id only; the module
-- never maps another module's entities.
--
-- Both foreign keys are ON DELETE RESTRICT: a paid ticket is accounting history and must never
-- disappear as a side effect of deleting a user or a showtime.
CREATE TABLE tickets (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  BIGINT         NOT NULL REFERENCES users(id)           ON DELETE RESTRICT,
    schedule_id              BIGINT         NOT NULL REFERENCES movie_schedules(id) ON DELETE RESTRICT,
    -- Snapshot of the sum of the seat price snapshots taken when the seats were held.
    total_price              NUMERIC(10, 2) NOT NULL CHECK (total_price >= 0),
    -- ISO 4217 code the Stripe Checkout Session was created in (app.stripe.currency).
    currency                 VARCHAR(3)     NOT NULL,
    status                   VARCHAR(20)    NOT NULL CHECK (status IN
                                 ('PENDING_PAYMENT', 'PAID', 'CANCELLED', 'REFUNDED', 'EXPIRED')),
    -- UNIQUE: one Checkout Session belongs to exactly one ticket, which is also what makes the
    -- webhook able to find its ticket from the session alone.
    stripe_session_id        VARCHAR(255)   UNIQUE,
    stripe_payment_intent_id VARCHAR(255),
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    paid_at                  TIMESTAMPTZ    NULL,
    cancelled_at             TIMESTAMPTZ    NULL
);

-- "my tickets" and the pending-ticket lookup of the checkout idempotency rule.
CREATE INDEX idx_tickets_user_status ON tickets (user_id, status);

-- Occupancy/revenue reads per showtime (phase 8) and the per-schedule pending lookup.
CREATE INDEX idx_tickets_schedule_status ON tickets (schedule_id, status);

-- charge.refunded / payment_intent.payment_failed only carry the payment intent.
CREATE INDEX idx_tickets_payment_intent ON tickets (stripe_payment_intent_id);

-- The foreign key phase 6 deliberately deferred: seat_reservations.ticket_id could not reference
-- tickets(id) before this table existed. RESTRICT for the same reason as above - the seats of a
-- paid ticket are part of its history.
ALTER TABLE seat_reservations
    ADD CONSTRAINT fk_seat_reservations_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE RESTRICT;
