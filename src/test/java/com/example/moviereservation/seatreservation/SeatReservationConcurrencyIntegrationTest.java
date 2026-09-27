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
 * The safety test of phase 6: many users trying to hold the <em>same</em> seat at the same moment
 * against the real PostgreSQL of {@link com.example.moviereservation.TestcontainersConfiguration}.
 *
 * <p>The service is called directly instead of through MockMvc, because that is what puts each
 * thread in its own transaction: {@code holdSeats} is {@code @Transactional} and is invoked through
 * the Spring proxy from a thread that has no transaction of its own, so every attempt is an
 * independent transaction competing for the partial unique index
 * {@code ux_active_seat_per_schedule}.
 */
class SeatReservationConcurrencyIntegrationTest extends AbstractSeatReservationIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private SeatReservationService seatReservationService;

    @Test
    void onlyOneOfEightConcurrentHoldsOfTheSameSeatSucceeds() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        long seatId = show.seat(0);

        List<TestUser> users = new java.util.ArrayList<>();
        for (int index = 0; index < THREADS; index++) {
            users.add(registerUser());
        }

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (TestUser user : users) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        seatReservationService.holdSeats(user.id(),
                                new HoldSeatsRequest(show.scheduleId(), List.of(seatId)));
                        successes.incrementAndGet();
                    } catch (ConflictException expected) {
                        // Either the pre-check SELECT saw the winner, or the unique index rejected
                        // this transaction; both are the documented 409.
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

        assertThat(unexpected).isEmpty();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(THREADS - 1);

        // The database itself holds exactly one active row for that (schedule, seat).
        Integer activeRows = jdbcTemplate.queryForObject("""
                        select count(*) from seat_reservations
                         where schedule_id = ? and seat_id = ? and status in ('HELD', 'CONFIRMED')""",
                Integer.class, show.scheduleId(), seatId);
        assertThat(activeRows).isEqualTo(1);

        // And the losers left nothing behind at all.
        Integer allRows = jdbcTemplate.queryForObject(
                "select count(*) from seat_reservations where schedule_id = ? and seat_id = ?",
                Integer.class, show.scheduleId(), seatId);
        assertThat(allRows).isEqualTo(1);

        assertThat(seatStatus(show.scheduleId(), seatId)).isEqualTo("HELD");
    }
}
