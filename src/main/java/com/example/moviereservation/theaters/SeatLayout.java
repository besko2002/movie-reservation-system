package com.example.moviereservation.theaters;

import com.example.moviereservation.common.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The seat grid of a theater: {@code totalRows} rows labelled A, B, C ..., each holding
 * {@code seatsPerRow} seats numbered from 1, where the rows in {@code vipRows} are VIP.
 *
 * <p>This value object is the single place that decides what seats a theater has. Two layouts are
 * equal (record equality) exactly when they produce the same seats, which is what lets
 * {@code TheaterService} skip a seat regeneration on an update that does not touch the grid.
 *
 * @param totalRows   number of rows, 1..26
 * @param seatsPerRow seats in each row, 1..50
 * @param vipRows     row labels that are VIP, upper-case, de-duplicated and sorted
 */
record SeatLayout(int totalRows, int seatsPerRow, List<String> vipRows) {

    /** Highest number of rows that can still be labelled with a single letter. */
    static final int MAX_ROWS = 26;

    /** Highest number of seats allowed in one row. */
    static final int MAX_SEATS_PER_ROW = 50;

    SeatLayout {
        vipRows = vipRows == null ? List.of() : List.copyOf(vipRows);
    }

    /**
     * Builds a validated layout from request values.
     *
     * @param requestedVipRows VIP row labels as sent by the client; case-insensitive, duplicates
     *                         are collapsed and {@code null}/blank entries ignored
     * @throws BadRequestException when the grid is out of range or a VIP row is not in the grid
     */
    static SeatLayout of(int totalRows, int seatsPerRow, List<String> requestedVipRows) {
        if (totalRows < 1 || totalRows > MAX_ROWS) {
            throw new BadRequestException("totalRows must be between 1 and " + MAX_ROWS);
        }
        if (seatsPerRow < 1 || seatsPerRow > MAX_SEATS_PER_ROW) {
            throw new BadRequestException("seatsPerRow must be between 1 and " + MAX_SEATS_PER_ROW);
        }
        Set<String> rowLabels = new LinkedHashSet<>(rowLabels(totalRows));
        Set<String> normalised = new LinkedHashSet<>();
        if (requestedVipRows != null) {
            for (String row : requestedVipRows) {
                if (row == null || row.isBlank()) {
                    continue;
                }
                normalised.add(row.trim().toUpperCase(Locale.ROOT));
            }
        }
        List<String> unknown = normalised.stream().filter(row -> !rowLabels.contains(row)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestException(
                    "vipRows contains row(s) outside the theater grid: %s; rows are %s..%s"
                            .formatted(unknown, "A", lastRowLabel(totalRows)));
        }
        return new SeatLayout(totalRows, seatsPerRow, normalised.stream().sorted().toList());
    }

    /** @return the seats of this grid, attached to {@code theater}, in row then number order */
    List<Seat> generateSeats(Theater theater) {
        Set<String> vip = new LinkedHashSet<>(vipRows);
        List<Seat> generated = new ArrayList<>(totalRows * seatsPerRow);
        for (String rowLabel : rowLabels(totalRows)) {
            SeatType type = vip.contains(rowLabel) ? SeatType.VIP : SeatType.NORMAL;
            for (int number = 1; number <= seatsPerRow; number++) {
                generated.add(new Seat(theater, rowLabel, number, type));
            }
        }
        return generated;
    }

    /** @return the labels A, B, C ... of the first {@code totalRows} rows */
    private static List<String> rowLabels(int totalRows) {
        List<String> labels = new ArrayList<>(totalRows);
        for (int index = 0; index < totalRows; index++) {
            labels.add(String.valueOf((char) ('A' + index)));
        }
        return labels;
    }

    private static String lastRowLabel(int totalRows) {
        return String.valueOf((char) ('A' + totalRows - 1));
    }
}
