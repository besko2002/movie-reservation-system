package com.example.moviereservation.tickets;

/**
 * Hand written Stripe event bodies for the webhook tests.
 *
 * <p>They are plain JSON strings, not SDK objects, because the endpoint verifies and parses the
 * <em>raw</em> body itself: a test that built an object and let Jackson serialise it would not prove
 * that the byte for byte body is what gets signed and read.
 */
final class StripeEvents {

    private StripeEvents() {
    }

    /** A completed hosted checkout: the buyer paid. */
    static String checkoutSessionCompleted(String eventId, String sessionId, long ticketId, String paymentIntentId) {
        return checkoutSessionCompleted(eventId, sessionId, ticketId, paymentIntentId, 3500L, "usd");
    }

    /** A completed hosted checkout with the amount and currency the ticket actually carries. */
    static String checkoutSessionCompleted(String eventId, String sessionId, long ticketId,
                                           String paymentIntentId, long amountTotal, String currency) {
        return """
                {"id":"%s","object":"event","type":"checkout.session.completed","data":{"object":{\
                "id":"%s","object":"checkout.session","client_reference_id":"%d","payment_status":"paid",\
                "payment_intent":"%s","amount_total":%d,"currency":"%s",\
                "metadata":{"ticketId":"%d","userId":"1","scheduleId":"1"}}}}"""
                .formatted(eventId, sessionId, ticketId, paymentIntentId, amountTotal, currency, ticketId);
    }

    /**
     * A completed hosted checkout whose payload carries no payment intent at all. Nothing can be
     * refunded from such a delivery, so it is the "manual follow-up" corner of the webhook.
     */
    static String checkoutSessionCompletedWithoutPaymentIntent(String eventId, String sessionId, long ticketId) {
        return checkoutSessionCompletedWithoutPaymentIntent(eventId, sessionId, ticketId, 3500L, "usd");
    }

    /** Variant with the real ticket amount, so the only missing field is the payment intent. */
    static String checkoutSessionCompletedWithoutPaymentIntent(String eventId, String sessionId, long ticketId,
                                                              long amountTotal, String currency) {
        return """
                {"id":"%s","object":"event","type":"checkout.session.completed","data":{"object":{\
                "id":"%s","object":"checkout.session","client_reference_id":"%d","payment_status":"paid",\
                "amount_total":%d,"currency":"%s",\
                "metadata":{"ticketId":"%d","userId":"1","scheduleId":"1"}}}}"""
                .formatted(eventId, sessionId, ticketId, amountTotal, currency, ticketId);
    }

    /** The hosted checkout was never paid and is now dead. */
    static String checkoutSessionExpired(String eventId, String sessionId, long ticketId) {
        return """
                {"id":"%s","object":"event","type":"checkout.session.expired","data":{"object":{\
                "id":"%s","object":"checkout.session","client_reference_id":"%d","payment_status":"unpaid",\
                "metadata":{"ticketId":"%d"}}}}"""
                .formatted(eventId, sessionId, ticketId, ticketId);
    }

    /** The payment attempt failed for good. */
    static String paymentIntentFailed(String eventId, String paymentIntentId, long ticketId) {
        return """
                {"id":"%s","object":"event","type":"payment_intent.payment_failed","data":{"object":{\
                "id":"%s","object":"payment_intent","status":"requires_payment_method",\
                "metadata":{"ticketId":"%d"}}}}"""
                .formatted(eventId, paymentIntentId, ticketId);
    }

    /** Money was given back - possibly from the Stripe dashboard, without this API's DELETE. */
    static String chargeRefunded(String eventId, String paymentIntentId, long ticketId) {
        return """
                {"id":"%s","object":"event","type":"charge.refunded","data":{"object":{\
                "id":"ch_test_%d","object":"charge","refunded":true,"payment_intent":"%s",\
                "metadata":{"ticketId":"%d"}}}}"""
                .formatted(eventId, ticketId, paymentIntentId, ticketId);
    }

    /** A refund event carrying how much was charged and how much has been refunded so far. */
    static String chargeRefunded(String eventId, String paymentIntentId, long ticketId,
                                 long amount, long amountRefunded) {
        return """
                {"id":"%s","object":"event","type":"charge.refunded","data":{"object":{\
                "id":"ch_test_%d","object":"charge","refunded":%s,"payment_intent":"%s",\
                "amount":%d,"amount_refunded":%d,\
                "metadata":{"ticketId":"%d"}}}}"""
                .formatted(eventId, ticketId, amountRefunded >= amount, paymentIntentId,
                        amount, amountRefunded, ticketId);
    }

    /** An event type this module does not act on. */
    static String unknownType(String eventId) {
        return """
                {"id":"%s","object":"event","type":"customer.subscription.updated","data":{"object":{\
                "id":"sub_test_1","object":"subscription","status":"active"}}}"""
                .formatted(eventId);
    }
}
