package com.example.moviereservation.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only controller: every endpoint triggers one branch of {@link GlobalExceptionHandler}. */
@RestController
@RequestMapping("/test-errors")
class ExceptionTestController {

    @GetMapping("/not-found")
    String notFound() {
        throw new ResourceNotFoundException("Movie", 42);
    }

    @GetMapping("/conflict")
    String conflict() {
        throw new ConflictException("Seat A1 is already booked");
    }

    @GetMapping("/bad-request")
    String badRequest() {
        throw new BadRequestException("startTime must be in the future");
    }

    @GetMapping("/boom")
    String boom() {
        throw new IllegalStateException("super secret internal failure at com.example.Internal.line42");
    }

    @GetMapping("/typed/{id}")
    String typed(@PathVariable long id) {
        return String.valueOf(id);
    }

    @PostMapping("/validate")
    String validate(@Valid @RequestBody SamplePayload payload) {
        return payload.title();
    }

    record SamplePayload(@NotBlank(message = "title must not be blank") String title,
                         @Min(value = 1, message = "seats must be at least 1") int seats) {
    }
}
