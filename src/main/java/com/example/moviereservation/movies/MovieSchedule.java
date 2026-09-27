package com.example.moviereservation.movies;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A showtime: one {@link Movie} playing in one theater between {@code startTime} and
 * {@code endTime}, with the prices charged for NORMAL and VIP seats.
 *
 * <p>The theater is deliberately stored as a plain id: the movies module must not reach into the
 * theaters module's entities. Theater data is read through {@code TheaterService}.
 *
 * <p>{@code endTime} is never supplied by a client; {@link ScheduleService} computes it as
 * {@code startTime + movie.durationMinutes + cleaning buffer}, so every overlap check already
 * includes the cleaning time between two showtimes.
 */
@Entity
@Table(name = "movie_schedules")
public class MovieSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @Column(name = "theater_id", nullable = false)
    private Long theaterId;

    @Column(name = "start_time", nullable = false)
    private OffsetDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private OffsetDateTime endTime;

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "vip_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal vipPrice;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected MovieSchedule() {
        // for JPA
    }

    public MovieSchedule(Movie movie, Long theaterId, OffsetDateTime startTime, OffsetDateTime endTime,
                         BigDecimal basePrice, BigDecimal vipPrice) {
        this.movie = movie;
        this.theaterId = theaterId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.basePrice = basePrice;
        this.vipPrice = vipPrice;
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

    public Movie getMovie() {
        return movie;
    }

    public void setMovie(Movie movie) {
        this.movie = movie;
    }

    public Long getTheaterId() {
        return theaterId;
    }

    public void setTheaterId(Long theaterId) {
        this.theaterId = theaterId;
    }

    public OffsetDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(OffsetDateTime startTime) {
        this.startTime = startTime;
    }

    public OffsetDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(OffsetDateTime endTime) {
        this.endTime = endTime;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public BigDecimal getVipPrice() {
        return vipPrice;
    }

    public void setVipPrice(BigDecimal vipPrice) {
        this.vipPrice = vipPrice;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
