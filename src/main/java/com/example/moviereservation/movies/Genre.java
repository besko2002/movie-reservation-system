package com.example.moviereservation.movies;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/** A movie genre. Names are unique, stored trimmed and compared case-insensitively. */
@Entity
@Table(name = "genres")
public class Genre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 60, unique = true)
    private String name;

    protected Genre() {
        // for JPA
    }

    public Genre(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // Identity by persistent id only; two unsaved genres are never equal.
        return other instanceof Genre genre && id != null && id.equals(genre.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
