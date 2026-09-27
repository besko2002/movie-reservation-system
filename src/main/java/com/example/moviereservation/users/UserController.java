package com.example.moviereservation.users;

import com.example.moviereservation.common.ApiError;
import com.example.moviereservation.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated user endpoints. */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Profile of the authenticated user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Current user profile")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile of the token owner"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public UserDto me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return userService.getById(principal.id());
    }
}
