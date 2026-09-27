package com.example.moviereservation.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One purchase: the seats a user held in one schedule, turned into a payable unit.
 *
 * <p>The user and the schedule are stored as plain ids — this module must not map the users,
 * movies or seat reservation module's entities. Their data is read through their public services
 * ({@code UserService}, {@code ScheduleService}, {@code SeatReservationService}).
 *
 * <p>{@code totalPrice} is a snapshot of the sum of the seat price snapshots taken when the seats
 * were held, so a later price change on the schedule never rewrites an existing ticket. The seats
 * themselves live in {@code seat_reservations}; they point back here through
 * {@code seat_reservations.ticket_id} (the foreign key added by {@code V7__create_tickets.sql}).
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status;

    /** Stripe Checkout Session this ticket is paid with; unique, so the webhook can find it. */
    @Column(name = "stripe_session_id", length = 255)
    private String stripeSessionId;

    /** Stripe PaymentIntent behind the session; needed to refund and set once the payment lands. */
    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    protected Ticket() {
        // for JPA
    }

    /** Creates a ticket that waits for its payment. */
    Ticket(Long userId, Long scheduleId, BigDecimal totalPrice, String currency) {
        this.userId = userId;
        this.scheduleId = scheduleId;
        this.totalPrice = totalPrice;
        this.currency = currency;
        this.status = TicketStatus.PENDING_PAYMENT;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** @return whether that user owns this ticket */
    boolean isOwnedBy(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }

    void markPaid(String paymentIntentId, OffsetDateTime paidAt) {
        this.status = TicketStatus.PAID;
        this.paidAt = paidAt;
        if (paymentIntentId != null) {
            this.stripePaymentIntentId = paymentIntentId;
        }
    }

    void markCancelled(OffsetDateTime cancelledAt) {
        this.status = TicketStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    void markExpired(OffsetDateTime cancelledAt) {
        this.status = TicketStatus.EXPIRED;
        this.cancelledAt = cancelledAt;
    }

    void markRefunded(OffsetDateTime cancelledAt) {
        this.status = TicketStatus.REFUNDED;
        this.cancelledAt = cancelledAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public String getStripeSessionId() {
        return stripeSessionId;
    }

    void setStripeSessionId(String stripeSessionId) {
        this.stripeSessionId = stripeSessionId;
    }

    public String getStripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    void setStripePaymentIntentId(String stripePaymentIntentId) {
        this.stripePaymentIntentId = stripePaymentIntentId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }
}
