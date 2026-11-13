package com.example.moviereservation.seatreservation;

import com.example.moviereservation.common.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that two concurrent hold requests from the <em>same</em> user for <em>disjoint</em> seats
 * cannot together exceed the per-user seat limit ({@code app.reservation.max-seats-per-user}).
 *
 * <p>Before the advisory-lock fix, both requests would read the same active count (0), both pass
 * the limit check, and both succeed — resulting in more seats than the limit allows. After the fix,
 * the advisory lock serialises hold attempts per (user, schedule) so the second request sees the
 * seats created by the first and is correctly rejected.
 *
 * <p>As in {@link SeatReservationConcurrencyIntegrationTest}, the service is called directly so
 * each thread runs in its own independent transaction through the Spring proxy.
 */
class SeatLimitConcurrencyIntegrationTest extends AbstractSeatReservationIntegrationTest {

    @Autowired
    private SeatReservationService seatReservationService;

    @Test
    void parallelHoldsOfDisjointSeatsByOneUserCannotExceedPerUserLimit() throws Exception {
        String admin = adminToken();
        // 4 rows × 3 seats = 12 seats. maxSeatsPerUser is 8 (from application.yml).
        // Two parallel requests each asking for 5 disjoint seats: 5 + 5 = 10 > 8.
        // Exactly one must succeed and the other must get a 409.
        Show show = createShow(admin, futureStart(), 4, 3);

        int seatsPerRequest = (maxSeatsPerUser / 2) + 1; // e.g. 5 when limit is 8
        assertThat(seatsPerRequest * 2).isGreaterThan(maxSeatsPerUser);
        assertThat(show.seatIds().size()).isGreaterThanOrEqualTo(seatsPerRequest * 2);

        List<Long> batch1 = show.seatIds().subList(0, seatsPerRequest);
        List<Long> batch2 = show.seatIds().subList(seatsPerRequest, seatsPerRequest * 2);

        // Disjoint: no seat appears in both batches.
        assertThat(batch1).doesNotContainAnyElementsOf(batch2);

        TestUser user = registerUser();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (List<Long> batch : List.of(batch1, batch2)) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        seatReservationService.holdSeats(user.id(),
                                new HoldSeatsRequest(show.scheduleId(), batch));
                        successes.incrementAndGet();
                    } catch (ConflictException expected) {
                        conflicts.incrementAndGet();
                    } catch (Throwable throwable) {
                        unexpected.add(throwable);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).as("No unexpected exceptions").isEmpty();
        assertThat(successes.get()).as("Exactly one batch must succeed").isEqualTo(1);
        assertThat(conflicts.get()).as("Exactly one batch must be rejected").isEqualTo(1);

        // The database must hold at most maxSeatsPerUser active rows for this user + schedule.
        Integer activeRows = jdbcTemplate.queryForObject("""
                        select count(*) from seat_reservations
                         where user_id = ? and schedule_id = ? and status in ('HELD', 'CONFIRMED')""",
                Integer.class, user.id(), show.scheduleId());
        assertThat(activeRows)
                .as("Active seats must not exceed the per-user limit")
                .isLessThanOrEqualTo(maxSeatsPerUser);
        assertThat(activeRows).isEqualTo(seatsPerRequest);
    }
}
