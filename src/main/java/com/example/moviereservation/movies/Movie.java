package com.example.moviereservation.movies;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** A movie in the catalogue, with the set of genres it belongs to. */
@Entity
@Table(name = "movies")
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    // columnDefinition keeps Hibernate's ddl-auto=validate in step with the TEXT column in V3.
    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "poster_url", length = 500)
    private String posterUrl;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "movie_genres",
            joinColumns = @JoinColumn(name = "movie_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new LinkedHashSet<>();

    protected Movie() {
        // for JPA
    }

    public Movie(String title, String description, String posterUrl, int durationMinutes, LocalDate releaseDate) {
        this.title = title;
        this.description = description;
        this.posterUrl = posterUrl;
        this.durationMinutes = durationMinutes;
        this.releaseDate = releaseDate;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public void setPosterUrl(String posterUrl) {
        this.posterUrl = posterUrl;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(LocalDate releaseDate) {
        this.releaseDate = releaseDate;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public Set<Genre> getGenres() {
        return genres;
    }

    /** Replaces the whole genre set, keeping the managed collection instance. */
    public void replaceGenres(Set<Genre> newGenres) {
        genres.clear();
        genres.addAll(newGenres);
    }
}
