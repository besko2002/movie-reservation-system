package com.example.moviereservation.movies;

import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.theaters.TheaterUsagePolicy;
import org.springframework.stereotype.Component;

/**
 * Movies-module implementation of the theaters module's deletion seam
 * {@link TheaterUsagePolicy}: a theater that still has showtimes must not be deleted.
 *
 * <p>This is the only place where "theater deletion" and "schedule" meet. The theaters module keeps
 * depending on its own port only, and this adapter answers through {@link ScheduleService} — the
 * public API of the movies module — so no repository or entity crosses a module boundary.
 */
@Component
class ScheduleTheaterUsagePolicy implements TheaterUsagePolicy {

    private final ScheduleService scheduleService;

    ScheduleTheaterUsagePolicy(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Override
    public void checkTheaterMayBeDeleted(Long theaterId) {
        long schedules = scheduleService.countSchedulesForTheater(theaterId);
        if (schedules > 0) {
            throw new ConflictException(
                    ("Theater %d still has %d schedule(s); delete or move them before deleting the theater")
                            .formatted(theaterId, schedules));
        }
    }
}
