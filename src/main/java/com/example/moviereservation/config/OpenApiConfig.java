package com.example.moviereservation.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc / OpenAPI document metadata: title, description of the booking flow, the JWT bearer
 * scheme every protected operation uses, and a description per tag (the tag names match the
 * {@code @Tag} annotations on the controllers, so springdoc merges them instead of adding new ones).
 * Swagger UI is served at {@code /swagger-ui.html} (see springdoc.swagger-ui.path).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    private static final String DESCRIPTION = """
            REST API of a cinema booking system: browse movies and showtimes, hold seats for a few
            minutes and pay for them through Stripe Checkout.

            ## Booking flow
            1. `POST /api/auth/register` then `POST /api/auth/login` — returns the JWT you paste into
               **Authorize** below (header `Authorization: Bearer <accessToken>`).
            2. `GET /api/schedules` → pick a showtime, then `GET /api/schedules/{scheduleId}/seats`
               for its seat map (`AVAILABLE` / `HELD` / `BOOKED`, with the price of each seat).
            3. `POST /api/seat-reservations` — **hold** the chosen seats. The hold expires after
               `app.reservation.hold-minutes` (10 by default); a background sweeper releases it.
            4. `POST /api/tickets` — **checkout**: turns the seats you hold in that schedule into a
               `PENDING_PAYMENT` ticket and returns the hosted Stripe `checkoutUrl`. The holds are
               extended to the payment deadline, so the seats cannot be swept away while you pay.
               The call is idempotent per (user, schedule).
            5. Stripe calls `POST /api/payments/stripe/webhook` — the **webhook** body is verified
               with an HMAC-SHA256 signature. `checkout.session.completed` marks the ticket **PAID**
               and its seat reservations `CONFIRMED`; a failed or expired session cancels the ticket
               and releases the seats.
            6. `DELETE /api/tickets/{id}` cancels a PAID ticket and refunds it in full through
               Stripe, as long as the showtime is more than `app.ticket.cancellation-cutoff-hours`
               away.

            Double booking is impossible: a partial unique index on
            `seat_reservations(schedule_id, seat_id) WHERE status IN ('HELD','CONFIRMED')` makes the
            database reject the second concurrent hold of the same seat.

            ## Authentication
            Public: registration, login, the movie/genre/theater/schedule `GET` endpoints, the seat
            map, `/actuator/health` and this documentation. Everything else needs a bearer token;
            `/api/admin/**` and the write endpoints of the catalogue need role `ADMIN`.

            ## Payments
            Stripe is optional. With no `STRIPE_SECRET_KEY` configured, the API starts normally and
            the endpoints that need a payment provider answer `503 Service Unavailable` with an
            `ApiError` body.
            """;

    @Bean
    public OpenAPI movieReservationOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .name(BEARER_SCHEME)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Paste the accessToken returned by /api/auth/login")))
                // Applied to every operation; public endpoints opt out with @SecurityRequirements.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .info(new Info()
                        .title("Movie Reservation System API")
                        .description(DESCRIPTION)
                        .version("v1")
                        .contact(new Contact()
                                .name("Movie Reservation System")
                                .url("https://github.com/example/movie-reservation-system"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                // Mutable on purpose: springdoc appends the controller-derived tags to this list.
                .tags(new ArrayList<>(documentedTags()));
    }

    /**
     * springdoc also derives a tag from every controller's {@code @Tag} annotation and appends it
     * when it is not byte-for-byte equal to a tag already in the document, which would show each
     * group twice in Swagger UI. This customizer runs after the document is assembled and keeps one
     * entry per tag name — the richer description declared here — in the declared order.
     */
    @Bean
    public OpenApiCustomizer uniqueTagsCustomizer() {
        return openApi -> {
            List<Tag> present = openApi.getTags() == null ? List.of() : openApi.getTags();
            Set<String> names = new LinkedHashSet<>();
            present.forEach(tag -> names.add(tag.getName()));

            List<Tag> merged = new ArrayList<>();
            for (Tag documented : documentedTags()) {
                if (names.remove(documented.getName())) {
                    merged.add(documented);
                }
            }
            // Any tag only a controller knows about keeps its own description.
            for (String remaining : names) {
                present.stream()
                        .filter(tag -> remaining.equals(tag.getName()))
                        .findFirst()
                        .ifPresent(merged::add);
            }
            openApi.setTags(merged);
        };
    }

    private static List<Tag> documentedTags() {
        return List.of(
                        new Tag().name("Authentication")
                                .description("Registration and login. Login returns the JWT access token "
                                        + "every protected endpoint expects in the Authorization header."),
                        new Tag().name("Users")
                                .description("Profile of the authenticated token owner."),
                        new Tag().name("Movies")
                                .description("Movie catalogue: public paged browsing with genre and title "
                                        + "filters, ADMIN create / replace / delete."),
                        new Tag().name("Genres")
                                .description("Movie genres: public read, ADMIN create / rename / delete."),
                        new Tag().name("Schedules")
                                .description("Showtimes of a movie in a theater with base and VIP prices. "
                                        + "Public read with date, movie and theater filters; ADMIN writes, "
                                        + "which compute endTime and reject overlapping shows in one hall."),
                        new Tag().name("Theaters")
                                .description("Theaters (halls) and their generated seat grids. Public read, "
                                        + "ADMIN writes; changing the grid regenerates the seats."),
                        new Tag().name("Seat reservations")
                                .description("Seat map of a showtime (public) and timed seat holds of the "
                                        + "authenticated user — step 2 and 3 of the booking flow."),
                        new Tag().name("Tickets")
                                .description("Checkout of held seats through Stripe, the caller's tickets, "
                                        + "and cancellation with a full refund before the cutoff."),
                        new Tag().name("Payments")
                                .description("Stripe webhook deliveries. Public but authenticated by the "
                                        + "Stripe signature header, not by a JWT; this is what moves a "
                                        + "ticket to PAID."),
                        new Tag().name("Admin reports")
                                .description("ADMIN only: every ticket, revenue over a time window and "
                                        + "occupancy of a single showtime."));
    }
}
