package com.example.moviereservation.tickets;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

interface TicketRefundRepository extends JpaRepository<TicketRefund, Long> {

    @Query("select sum(r.amount) from TicketRefund r where r.ticketId = :ticketId")
    BigDecimal sumForTicket(@Param("ticketId") Long ticketId);

    @Query("""
            select new com.example.moviereservation.tickets.RevenueTotals(count(distinct r.ticketId), sum(r.amount))
              from TicketRefund r
             where r.occurredAt >= :from and r.occurredAt < :to
            """)
    RevenueTotals totalsInWindow(@Param("from") OffsetDateTime from,
                                 @Param("to") OffsetDateTime to);
}
