package com.example.moviereservation.theaters;

/**
 * One seat of a theater.
 *
 * @param id         seat id, referenced by seat reservations in later phases
 * @param rowLabel   row letter, A..Z
 * @param seatNumber seat number inside the row, starting at 1
 * @param type       NORMAL or VIP
 */
public record SeatDto(
        Long id,
        String rowLabel,
        int seatNumber,
        SeatType type
) {

    static SeatDto from(Seat seat) {
        return new SeatDto(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(), seat.getType());
    }
}
