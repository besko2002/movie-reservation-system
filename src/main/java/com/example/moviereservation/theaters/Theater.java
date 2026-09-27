package com.example.moviereservation.theaters;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A theater (hall) and the seat grid it owns.
 *
 * <p>The theater is the aggregate root of its seats: they are created with it, cascade with it and
 * are removed with it (the {@code seats.theater_id} foreign key also cascades in the database).
 */
@Entity
@Table(name = "theaters")
public class Theater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "seats_per_row", nullable = false)
    private int seatsPerRow;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(
            mappedBy = "theater",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("rowLabel ASC, seatNumber ASC")
    private List<Seat> seats = new ArrayList<>();

    protected Theater() {
        // for JPA
    }

    public Theater(String name, String location, int totalRows, int seatsPerRow) {
        this.name = name;
        this.location = location;
        this.totalRows = totalRows;
        this.seatsPerRow = seatsPerRow;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getSeatsPerRow() {
        return seatsPerRow;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<Seat> getSeats() {
        return seats;
    }

    /**
     * Drops every seat of this theater.
     *
     * <p>The managed collection instance is kept, so {@code orphanRemoval} deletes the previous
     * seats. The caller must flush between this and {@link #applyLayout(SeatLayout)}: Hibernate
     * orders inserts before deletes, which would otherwise break the
     * {@code (theater_id, row_label, seat_number)} unique constraint while a row label is reused.
     */
    void clearSeats() {
        seats.clear();
    }

    /**
     * Adopts {@code layout} as the grid of this theater and generates the matching seats.
     *
     * <p>Only ever called on a theater with no seats (a fresh one, or right after
     * {@link #clearSeats()} was flushed).
     */
    void applyLayout(SeatLayout layout) {
        this.totalRows = layout.totalRows();
        this.seatsPerRow = layout.seatsPerRow();
        seats.addAll(layout.generateSeats(this));
    }

    /** @return the grid this theater is currently laid out with, including its VIP rows */
    SeatLayout currentLayout() {
        List<String> vipRows = seats.stream()
                .filter(seat -> seat.getType() == SeatType.VIP)
                .map(Seat::getRowLabel)
                .distinct()
                .sorted()
                .toList();
        return new SeatLayout(totalRows, seatsPerRow, vipRows);
    }
}
