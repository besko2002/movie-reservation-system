-- Record each refund delta independently: a ticket may receive several partial refunds on
-- different days. Reporting the final status/amount on the ticket would rewrite past reports.
CREATE TABLE ticket_refunds (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE RESTRICT,
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ticket_refunds_ticket ON ticket_refunds (ticket_id);
CREATE INDEX idx_ticket_refunds_occurred_at ON ticket_refunds (occurred_at);
CREATE INDEX idx_tickets_paid_at ON tickets (paid_at) WHERE paid_at IS NOT NULL;

-- Existing fully refunded tickets predate the ledger. Their cancellation time is the best
-- available historical refund time; rows with no timestamp use the migration time.
INSERT INTO ticket_refunds (ticket_id, amount, occurred_at)
SELECT id, total_price, COALESCE(cancelled_at, now())
FROM tickets WHERE status = 'REFUNDED' AND total_price > 0;
