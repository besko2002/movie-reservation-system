package com.example.moviereservation.seatreservation;

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
 * One seat of one schedule, reserved for one user.
 *
 * <p>The user, the schedule and the seat are stored as plain ids: this module must not map the
 * users, movies or theaters module's entities. Their data is read through their public services
 * ({@code UserService}, {@code ScheduleService}, {@code TheaterService}).
 *
 * <p>{@code price} is a snapshot of what the schedule charged for this seat category when the hold
 * was created, so a later price change on the schedule never rewrites an existing reservation.
 *
 * <p>Uniqueness is <strong>not</strong> declared here: the database owns it through the partial
 * unique index {@code ux_active_seat_per_schedule ON (schedule_id, seat_id) WHERE status IN
 * ('HELD','CONFIRMED')}, which JPA cannot express. A violation surfaces as a
 * {@code DataIntegrityViolationException} and is translated into a 409 by
 * {@link SeatReservationService}.
 */
@Entity
@Table(name = "seat_reservations")
public class SeatReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    /**
     * The ticket this row is being paid with (phase 7). It is stamped while the row is still HELD,
     * when the checkout is opened, so the ticket owns exactly the seats it was priced for;
     * {@code fk_seat_reservations_ticket} references {@code tickets(id)}.
     */
    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    /** When a {@link ReservationStatus#HELD} row stops being valid; {@code null} otherwise. */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected SeatReservation() {
        // for JPA
    }

    /** Creates a fresh hold that expires at {@code expiresAt}. */
    public SeatReservation(Long userId, Long scheduleId, Long seatId, BigDecimal price, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.scheduleId = scheduleId;
        this.seatId = seatId;
        this.price = price;
        this.status = ReservationStatus.HELD;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /**
     * Whether this row still occupies its seat at {@code now}: CONFIRMED always does, HELD only
     * until it expires. This is the lazy half of expiry — an expired hold is reported as free even
     * when the sweeper has not run yet.
     *
     * @param now instant to judge the hold against
     * @return whether the seat is taken at that instant
     */
    public boolean isActiveAt(OffsetDateTime now) {
        if (status == ReservationStatus.CONFIRMED) {
            return true;
        }
        return status == ReservationStatus.HELD && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** @return whether this is a HELD row whose {@code expiresAt} has passed at {@code now} */
    public boolean isExpiredHold(OffsetDateTime now) {
        return status == ReservationStatus.HELD && expiresAt != null && !expiresAt.isAfter(now);
    }

    /**
     * Pushes the expiry of a HELD row further into the future. Used when a checkout is opened: the
     * hold has to outlive the payment page it is being paid through.
     *
     * @param newExpiresAt the new expiry; a value that would shorten the hold is ignored, and so is
     *                     a row that is not HELD anymore
     */
    void extendTo(OffsetDateTime newExpiresAt) {
        if (status != ReservationStatus.HELD || newExpiresAt == null) {
            return;
        }
        if (expiresAt == null || newExpiresAt.isAfter(expiresAt)) {
            this.expiresAt = newExpiresAt;
        }
    }

    /**
     * Binds this hold to the ticket it is being paid with, while it stays HELD.
     *
     * <p>That is what makes a ticket pay for a fixed set of seats: only the rows carrying the ticket
     * id are confirmed when the payment lands, so seats the buyer holds <em>besides</em> them can
     * never ride along on that payment.
     *
     * @param ticketId ticket the hold now belongs to; a row that is not HELD anymore is left alone
     */
    void attachToTicket(Long ticketId) {
        if (status != ReservationStatus.HELD || ticketId == null) {
            return;
        }
        this.ticketId = ticketId;
    }

    void release() {
        this.status = ReservationStatus.RELEASED;
        this.expiresAt = null;
    }

    void confirm(Long ticketId) {
        this.status = ReservationStatus.CONFIRMED;
        this.ticketId = ticketId;
        this.expiresAt = null;
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

    public Long getSeatId() {
        return seatId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
