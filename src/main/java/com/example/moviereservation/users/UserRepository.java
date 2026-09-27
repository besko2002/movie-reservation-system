package com.example.moviereservation.users;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Module-internal persistence for {@link User}; other modules go through {@link UserService}. */
interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
