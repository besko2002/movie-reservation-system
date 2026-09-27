package com.example.moviereservation.users;

/**
 * Public view of a user. Never carries the password hash.
 *
 * @param id    user id
 * @param name  display name
 * @param email email address (normalised to lower case)
 * @param role  {@link Role}
 */
public record UserDto(Long id, String name, String email, Role role) {

    static UserDto from(User user) {
        return new UserDto(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
