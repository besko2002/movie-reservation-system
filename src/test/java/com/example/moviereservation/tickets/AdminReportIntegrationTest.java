package com.example.moviereservation.tickets;

import com.example.moviereservation.tickets.TicketApiFixtures.Show;
import com.example.moviereservation.tickets.TicketApiFixtures.TestUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8: {@code GET /api/admin/reports/revenue}, {@code GET /api/admin/reports/occupancy/{id}} and
 * {@code GET /api/admin/tickets}.
 *
 * <p>Tickets are produced exactly the way production does — a hold, a checkout, and a genuinely
 * signed {@code checkout.session.completed} delivery ({@code pendingTicket} / {@code payTicket} of
 * {@link AbstractTicketIntegrationTest}), a refund through {@code DELETE /api/tickets/{id}}.
 *
 * <p>Isolation inside the shared database: every test creates its own showtime and users, and the
 * revenue assertions use a window that starts at the instant the test began, so only the payments of
 * that very test can fall into it.
 */
class AdminReportIntegrationTest extends AbstractTicketIntegrationTest {

    private static final String REVENUE_PATH = "/api/admin/reports/revenue";
    private static final String OCCUPANCY_PATH = "/api/admin/reports/occupancy/";
    private static final String ADMIN_TICKETS_PATH = "/api/admin/tickets";

    /** The business zone the daily breakdown is grouped in; the assertions read it back with it. */
    @Value("${app.schedule.zone}")
    private String scheduleZone;

    // --- revenue -------------------------------------------------------------------------------

    @Test
    @DisplayName("revenue sums only PAID tickets of the window; refunded and pending ones stay out")
    void revenueCountsOnlyPaidTicketsInsideTheWindow() throws Exception {
        String adminToken = fixtures.adminToken();
        Show show = fixtures.createShow(adminToken);
        TestUser firstBuyer = fixtures.registerUser();
        TestUser secondBuyer = fixtures.registerUser();
        TestUser refundedBuyer = fixtures.registerUser();
        TestUser pendingBuyer = fixtures.registerUser();

        // The window opens before anything of this test is paid, so no other test's money can leak in.
        OffsetDateTime windowStart = OffsetDateTime.now();
        OffsetDateTime windowEnd = windowStart.plusDays(1);

        // 2 paid: a normal seat (10.00) and a VIP seat (25.00) -> 35.00 gross.
        long firstPaid = pendingTicket(firstBuyer.token(), show.scheduleId(), List.of(show.seat(3)));
        payTicket(firstPaid);
        long secondPaid = pendingTicket(secondBuyer.token(), show.scheduleId(), List.of(show.seat(0)));
        payTicket(secondPaid);

        // 1 paid and then refunded (10.00): money that came in and was given back again.
        long refunded = pendingTicket(refundedBuyer.token(), show.scheduleId(), List.of(show.seat(4)));
        payTicket(refunded);
        mockMvc.perform(delete("/api/tickets/" + refunded)
                        .header("Authorization", TicketApiFixtures.bearer(refundedBuyer.token())))
                .andExpect(status().isNoContent());
        assertThat(ticketStatus(refunded)).isEqualTo("REFUNDED");

        // 1 never paid: a pending ticket is not revenue at all.
        long pending = pendingTicket(pendingBuyer.token(), show.scheduleId(), List.of(show.seat(5)));
        assertThat(ticketStatus(pending)).isEqualTo("PENDING_PAYMENT");

        JsonNode report = revenue(adminToken, windowStart, windowEnd);

        assertThat(report.path("paidTickets").asLong()).isEqualTo(2);
        assertThat(amount(report, "grossRevenue")).isEqualByComparingTo("35.00");
        assertThat(report.path("refundedTickets").asLong()).isEqualTo(1);
        assertThat(amount(report, "refundedAmount")).isEqualByComparingTo("10.00");
        // netRevenue == grossRevenue: the refunded ticket is not PAID anymore, so it was never added.
        assertThat(amount(report, "netRevenue")).isEqualByComparingTo("35.00");
        assertThat(report.path("currency").asText()).isNotBlank();
        assertThat(report.path("from").asText()).isNotBlank();
        assertThat(report.path("to").asText()).isNotBlank();

        // byMovie: only this test's movie is inside the window.
        JsonNode byMovie = report.path("byMovie");
        assertThat(byMovie).hasSize(1);
        assertThat(byMovie.get(0).path("movieId").asLong()).isEqualTo(show.movieId());
        assertThat(byMovie.get(0).path("title").asText()).isNotBlank();
        assertThat(byMovie.get(0).path("tickets").asLong()).isEqualTo(2);
        assertThat(new BigDecimal(byMovie.get(0).path("revenue").asText())).isEqualByComparingTo("35.00");

        // byDay: the same money, grouped by the business day the payment landed on.
        JsonNode byDay = report.path("byDay");
        assertThat(byDay).isNotEmpty();
        BigDecimal dailySum = BigDecimal.ZERO;
        long dailyTickets = 0;
        List<String> days = new ArrayList<>();
        for (JsonNode day : byDay) {
            dailySum = dailySum.add(new BigDecimal(day.path("revenue").asText()));
            dailyTickets += day.path("tickets").asLong();
            days.add(day.path("date").asText());
        }
        assertThat(dailySum).isEqualByComparingTo("35.00");
        assertThat(dailyTickets).isEqualTo(2);
        assertThat(days).contains(businessDayOfPayment(firstPaid).toString());
    }

    @Test
    @DisplayName("a paid ticket outside the window is not revenue")
    void revenueExcludesPaymentsOutsideTheWindow() throws Exception {
        String adminToken = fixtures.adminToken();
        Show show = fixtures.createShow(adminToken);
        TestUser buyer = fixtures.registerUser();

        OffsetDateTime now = OffsetDateTime.now();
        long ticketId = pendingTicket(buyer.token(), show.scheduleId(), List.of(show.seat(3)));
        payTicket(ticketId);
        assertThat(ticketStatus(ticketId)).isEqualTo("PAID");

        // A window that closed before the payment landed.
        JsonNode report = revenue(adminToken, now.minusDays(2), now.minusDays(1));

        assertThat(report.path("paidTickets").asLong()).isZero();
        assertThat(amount(report, "grossRevenue")).isEqualByComparingTo("0.00");
        assertThat(amount(report, "netRevenue")).isEqualByComparingTo("0.00");
        assertThat(report.path("refundedTickets").asLong()).isZero();
        assertThat(amount(report, "refundedAmount")).isEqualByComparingTo("0.00");
        assertThat(report.path("byMovie")).isEmpty();
        assertThat(report.path("byDay")).isEmpty();
    }

    @Test
    @DisplayName("revenue with from after to is a 400")
    void revenueRejectsInvertedWindow() throws Exception {
        String adminToken = fixtures.adminToken();
        OffsetDateTime now = OffsetDateTime.now();

        mockMvc.perform(get(REVENUE_PATH)
                        .header("Authorization", TicketApiFixtures.bearer(adminToken))
                        .param("from", TicketApiFixtures.iso(now))
                        .param("to", TicketApiFixtures.iso(now.minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(REVENUE_PATH));
    }

    @Test
    @DisplayName("revenue without bounds defaults to the last 30 days and answers 200")
    void revenueDefaultsToLastThirtyDays() throws Exception {
        String adminToken = fixtures.adminToken();

        JsonNode report = revenue(adminToken, null, null);

        OffsetDateTime from = OffsetDateTime.parse(report.path("from").asText());
        OffsetDateTime to = OffsetDateTime.parse(report.path("to").asText());
        assertThat(from).isBefore(to);
        assertThat(java.time.Duration.between(from, to).toDays()).isEqualTo(30);
        assertThat(amount(report, "grossRevenue")).isEqualByComparingTo(amount(report, "netRevenue"));
    }

    // --- occupancy -----------------------------------------------------------------------------

    @Test
    @DisplayName("occupancy reports capacity, booked, held and available seats with the percentage")
    void occupancyCountsBookedHeldAndAvailableSeats() throws Exception {
        String adminToken = fixtures.adminToken();
        // The fixture hall is 2 rows x 3 seats = 6 seats (A VIP, B normal).
        Show show = fixtures.createShow(adminToken);
        TestUser buyer = fixtures.registerUser();
        TestUser holder = fixtures.registerUser();

        long paid = pendingTicket(buyer.token(), show.scheduleId(), List.of(show.seat(3)));
        payTicket(paid);
        assertThat(reservationStatusesOfTicket(paid)).containsExactly("CONFIRMED");
        fixtures.holdSeats(holder.token(), show.scheduleId(), List.of(show.seat(4)));

        mockMvc.perform(get(OCCUPANCY_PATH + show.scheduleId())
                        .header("Authorization", TicketApiFixtures.bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(show.scheduleId()))
                .andExpect(jsonPath("$.movieTitle").isNotEmpty())
                .andExpect(jsonPath("$.theaterName").isNotEmpty())
                .andExpect(jsonPath("$.startTime").isNotEmpty())
                .andExpect(jsonPath("$.capacity").value(6))
                .andExpect(jsonPath("$.bookedSeats").value(1))
                .andExpect(jsonPath("$.heldSeats").value(1))
                .andExpect(jsonPath("$.availableSeats").value(4))
                // 1 of 6 sold; the hold is reported but is not a sale.
                .andExpect(jsonPath("$.occupancyPercent").value(16.67))
                .andExpect(jsonPath("$.revenue").value(10.00));
    }

    @Test
    @DisplayName("occupancy of an unknown schedule is a 404")
    void occupancyOfUnknownScheduleIsNotFound() throws Exception {
        String adminToken = fixtures.adminToken();

        mockMvc.perform(get(OCCUPANCY_PATH + 999_999_999L)
                        .header("Authorization", TicketApiFixtures.bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // --- admin ticket list ---------------------------------------------------------------------

    @Test
    @DisplayName("/api/admin/tickets lists every ticket newest first, filters by status and paginates")
    void adminTicketListFiltersAndPaginates() throws Exception {
        String adminToken = fixtures.adminToken();
        Show show = fixtures.createShow(adminToken);
        TestUser buyer = fixtures.registerUser();
        TestUser secondUser = fixtures.registerUser();
        TestUser thirdUser = fixtures.registerUser();

        long paid = pendingTicket(buyer.token(), show.scheduleId(), List.of(show.seat(0)));
        payTicket(paid);
        long firstPending = pendingTicket(secondUser.token(), show.scheduleId(), List.of(show.seat(1)));
        long secondPending = pendingTicket(thirdUser.token(), show.scheduleId(), List.of(show.seat(2)));

        // Unfiltered except for this test's schedule: newest first.
        JsonNode all = adminTickets(adminToken, "scheduleId", String.valueOf(show.scheduleId()));
        assertThat(all.path("totalElements").asLong()).isEqualTo(3);
        assertThat(all.path("content")).hasSize(3);
        assertThat(all.path("content").get(0).path("id").asLong()).isEqualTo(secondPending);
        assertThat(all.path("content").get(1).path("id").asLong()).isEqualTo(firstPending);
        assertThat(all.path("content").get(2).path("id").asLong()).isEqualTo(paid);
        // Full ticket view: the seats travel with it.
        assertThat(all.path("content").get(2).path("seats")).hasSize(1);
        // A request without a size gets the documented default page size.
        assertThat(all.path("size").asInt()).isEqualTo(20);

        // status filter
        JsonNode pendingOnly = adminTickets(adminToken,
                "scheduleId", String.valueOf(show.scheduleId()), "status", "PENDING_PAYMENT");
        assertThat(pendingOnly.path("totalElements").asLong()).isEqualTo(2);
        for (JsonNode ticket : pendingOnly.path("content")) {
            assertThat(ticket.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        }
        JsonNode paidOnly = adminTickets(adminToken,
                "scheduleId", String.valueOf(show.scheduleId()), "status", "PAID");
        assertThat(paidOnly.path("totalElements").asLong()).isEqualTo(1);
        assertThat(paidOnly.path("content").get(0).path("id").asLong()).isEqualTo(paid);

        // userId filter
        JsonNode ofBuyer = adminTickets(adminToken,
                "scheduleId", String.valueOf(show.scheduleId()), "userId", String.valueOf(buyer.id()));
        assertThat(ofBuyer.path("totalElements").asLong()).isEqualTo(1);
        assertThat(ofBuyer.path("content").get(0).path("userId").asLong()).isEqualTo(buyer.id());

        // paging
        JsonNode firstPage = adminTickets(adminToken,
                "scheduleId", String.valueOf(show.scheduleId()), "size", "1", "page", "0");
        assertThat(firstPage.path("content")).hasSize(1);
        assertThat(firstPage.path("content").get(0).path("id").asLong()).isEqualTo(secondPending);
        assertThat(firstPage.path("page").asInt()).isZero();
        assertThat(firstPage.path("size").asInt()).isEqualTo(1);
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(3);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(3);
        assertThat(firstPage.path("last").asBoolean()).isFalse();

        JsonNode lastPage = adminTickets(adminToken,
                "scheduleId", String.valueOf(show.scheduleId()), "size", "1", "page", "2");
        assertThat(lastPage.path("content").get(0).path("id").asLong()).isEqualTo(paid);
        assertThat(lastPage.path("last").asBoolean()).isTrue();

        // An oversized page size is clamped instead of rejected.
        JsonNode clamped = adminTickets(adminToken,
                "scheduleId", String.valueOf(show.scheduleId()), "size", "500");
        assertThat(clamped.path("size").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("/api/admin/tickets with an unknown status is a 400")
    void adminTicketListRejectsUnknownStatus() throws Exception {
        String adminToken = fixtures.adminToken();

        mockMvc.perform(get(ADMIN_TICKETS_PATH)
                        .header("Authorization", TicketApiFixtures.bearer(adminToken))
                        .param("status", "ALMOST_PAID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ALMOST_PAID")));
    }

    // --- authorisation -------------------------------------------------------------------------

    @Test
    @DisplayName("a USER token gets 403 on every admin report endpoint")
    void userTokenIsForbiddenEverywhere() throws Exception {
        String adminToken = fixtures.adminToken();
        Show show = fixtures.createShow(adminToken);
        TestUser user = fixtures.registerUser();

        for (String path : List.of(REVENUE_PATH, OCCUPANCY_PATH + show.scheduleId(), ADMIN_TICKETS_PATH)) {
            mockMvc.perform(get(path).header("Authorization", TicketApiFixtures.bearer(user.token())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    @DisplayName("an anonymous caller gets 401 on every admin report endpoint")
    void anonymousIsUnauthorizedEverywhere() throws Exception {
        String adminToken = fixtures.adminToken();
        Show show = fixtures.createShow(adminToken);

        for (String path : List.of(REVENUE_PATH, OCCUPANCY_PATH + show.scheduleId(), ADMIN_TICKETS_PATH)) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private JsonNode revenue(String token, OffsetDateTime from, OffsetDateTime to) throws Exception {
        var request = get(REVENUE_PATH).header("Authorization", TicketApiFixtures.bearer(token));
        if (from != null) {
            request = request.param("from", TicketApiFixtures.iso(from));
        }
        if (to != null) {
            request = request.param("to", TicketApiFixtures.iso(to));
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** @param params alternating name/value pairs */
    private JsonNode adminTickets(String token, String... params) throws Exception {
        var request = get(ADMIN_TICKETS_PATH).header("Authorization", TicketApiFixtures.bearer(token));
        for (int index = 0; index + 1 < params.length; index += 2) {
            request = request.param(params[index], params[index + 1]);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static BigDecimal amount(JsonNode report, String field) {
        return new BigDecimal(report.path(field).asText());
    }

    /**
     * The calendar day the report groups a payment under, read back from the database in the same
     * business zone the report uses, so the assertion cannot break when a test runs across midnight.
     */
    private LocalDate businessDayOfPayment(long ticketId) {
        return jdbcTemplate.queryForObject(
                "SELECT cast((paid_at at time zone ?) as date) FROM tickets WHERE id = ?",
                LocalDate.class, scheduleZone, ticketId);
    }
}
