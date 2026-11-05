package com.example.moviereservation.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Module-internal persistence for {@link User}; other modules go through {@link UserService}. */
interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Modifying
    @Query("update User u set u.tokenVersion = u.tokenVersion + 1 where u.id = :id")
    void revokeTokens(@Param("id") Long id);
}
