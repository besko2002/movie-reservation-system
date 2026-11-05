package com.example.moviereservation.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One positive refund delta, not the cumulative amount reported by Stripe. */
@Entity
@Table(name = "ticket_refunds")
class TicketRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected TicketRefund() {
    }

    TicketRefund(Long ticketId, BigDecimal amount, OffsetDateTime occurredAt) {
        this.ticketId = ticketId;
        this.amount = amount;
        this.occurredAt = occurredAt;
    }
}
