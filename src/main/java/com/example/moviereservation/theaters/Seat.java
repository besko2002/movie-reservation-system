package com.example.moviereservation.theaters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One physical seat of a theater, identified by its row label and number inside that row.
 *
 * <p>Seats are never created by hand: they are generated from the theater grid (see
 * {@link SeatLayout}). Later phases reference a seat from a seat reservation, which is why seats
 * are replaced only through {@link Theater#replaceSeats(java.util.List)}.
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theater_id", nullable = false)
    private Theater theater;

    @Column(name = "row_label", nullable = false, length = 2)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private SeatType type;

    protected Seat() {
        // for JPA
    }

    Seat(Theater theater, String rowLabel, int seatNumber, SeatType type) {
        this.theater = theater;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.type = type;
    }

    public Long getId() {
        return id;
    }

    public Theater getTheater() {
        return theater;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatType getType() {
        return type;
    }
}
