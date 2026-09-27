package com.example.moviereservation.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExceptionTestController.class)
@Import({ExceptionTestController.class, GlobalExceptionHandler.class})
// Phase 2 added Spring Security to the classpath: this slice tests the exception mapping only,
// so the security filter chain is left out instead of authenticating these test-only endpoints.
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void resourceNotFoundReturns404ApiError() throws Exception {
        mockMvc.perform(get("/test-errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Movie not found with id 42"))
                .andExpect(jsonPath("$.path").value("/test-errors/not-found"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void conflictReturns409ApiError() throws Exception {
        mockMvc.perform(get("/test-errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Seat A1 is already booked"))
                .andExpect(jsonPath("$.path").value("/test-errors/conflict"));
    }

    @Test
    void badRequestReturns400ApiError() throws Exception {
        mockMvc.perform(get("/test-errors/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("startTime must be in the future"))
                .andExpect(jsonPath("$.path").value("/test-errors/bad-request"));
    }

    @Test
    void validationFailureReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test-errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \",\"seats\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/test-errors/validate"))
                .andExpect(jsonPath("$.fieldErrors.title").value("title must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.seats").value("seats must be at least 1"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/test-errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.path").value("/test-errors/validate"));
    }

    @Test
    void typeMismatchReturns400() throws Exception {
        mockMvc.perform(get("/test-errors/typed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'id'"));
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(post("/test-errors/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/test-errors/boom"))
                .andExpect(content().string(not(containsString("super secret internal failure"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at com.example"))));
    }
}
