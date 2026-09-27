package com.example.moviereservation.tickets;

import com.example.moviereservation.common.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Stripe webhook endpoint: where payment results actually come from.
 *
 * <p>A hosted checkout is finished on Stripe's pages, not on this API, so nothing but this endpoint
 * can tell the system that a ticket was paid. It is therefore the only public {@code POST} in the
 * application ({@code SecurityConfig} permits it without a token) and its authentication is the
 * signature, not a JWT.
 *
 * <h2>Raw body</h2>
 * The parameter is a {@link String} annotated with {@link RequestBody}, which makes Spring use
 * {@code StringHttpMessageConverter}: the body arrives byte for byte as Stripe sent it. That is
 * essential, because the signature is an HMAC-SHA256 over {@code "<timestamp>.<body>"} — had the
 * body been bound to an object, Jackson would have reserialised it (key order, spacing, number
 * formatting) and every genuine signature would fail. The JSON is parsed only
 * <strong>after</strong> the signature was accepted, inside {@link StripeWebhookVerifier}.
 * {@code consumes = ALL_VALUE} is set because Stripe sends {@code application/json} but a replay
 * tool may send anything; the content type plays no part in verification.
 *
 * <h2>Status codes</h2>
 * <ul>
 *   <li><strong>400</strong> — missing, malformed, stale or wrong signature, or an unreadable body.
 *       No state is changed. Stripe marks the delivery as failed, which is what should happen to a
 *       delivery this server cannot authenticate.</li>
 *   <li><strong>200</strong> — the delivery was genuine. That includes events this module does not
 *       act on, redeliveries that change nothing and events whose ticket cannot be found: answering
 *       anything else would only make Stripe retry a delivery that will never succeed. The body
 *       says which of those happened.</li>
 * </ul>
 * Unhandled exceptions still surface as 500 through the global handler, so a genuine bug does make
 * Stripe retry the delivery later.
 */
@RestController
@RequestMapping("/api/payments/stripe")
@Tag(name = "Payments", description = "Stripe webhook deliveries (public, authenticated by signature)")
public class PaymentWebhookController {

    /** Header Stripe signs every delivery with. */
    static final String SIGNATURE_HEADER = "Stripe-Signature";

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final StripeWebhookVerifier verifier;
    private final TicketService ticketService;

    public PaymentWebhookController(StripeWebhookVerifier verifier, TicketService ticketService) {
        this.verifier = verifier;
        this.ticketService = ticketService;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "Stripe webhook (public, signature authenticated)",
            description = "Applies checkout.session.completed (ticket PAID, seats CONFIRMED), "
                    + "checkout.session.expired (EXPIRED, seats released), "
                    + "payment_intent.payment_failed (CANCELLED, seats released) and "
                    + "charge.refunded (REFUNDED, seats released). Idempotent: every transition is "
                    + "guarded by the ticket's current status, so a Stripe redelivery changes nothing. "
                    + "Unknown event types are accepted and ignored.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Delivery verified; applied or ignored"),
            @ApiResponse(responseCode = "400",
                    description = "Missing or invalid Stripe-Signature, or an unreadable body; nothing changed",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Map<String, Object> receive(
            @RequestBody(required = false) String payload,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        // required = false on both: a missing body or header is this endpoint's own 400 with an
        // ApiError body, not a framework error page.
        PaymentEvent event = verifier.verifyAndParse(payload, signature);
        log.info("Verified Stripe event {} of type {}", event.id(), event.type());
        String result = ticketService.applyPaymentEvent(event);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("received", true);
        body.put("eventId", event.id());
        body.put("type", event.type());
        body.put("result", result);
        return body;
    }
}
