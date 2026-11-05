package com.example.moviereservation.tickets;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-sensitive landing pages for Stripe's browser redirect. Webhooks determine payment state. */
@RestController
public class PaymentReturnController {

    @GetMapping(value = "/payment/success", produces = MediaType.TEXT_HTML_VALUE)
    public String success() {
        return "<h1>Payment submitted</h1><p>Return to the app to see your ticket status.</p>";
    }

    @GetMapping(value = "/payment/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public String cancel() {
        return "<h1>Payment cancelled</h1><p>Return to the app to manage your ticket.</p>";
    }
}
