package com.acme.billing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
class CalendarPolicyTest {
 @Test void monthEndPreservesAnchor(){var a=LocalDateTime.parse("2024-01-31T10:00:00");assertEquals(Instant.parse("2024-02-29T10:00:00Z"),CalendarPolicy.boundary(a,ZoneOffset.UTC,1,1));assertEquals(Instant.parse("2024-03-31T10:00:00Z"),CalendarPolicy.boundary(a,ZoneOffset.UTC,1,2));}
 @Test void annualLeap(){assertEquals(Instant.parse("2025-02-28T00:00:00Z"),CalendarPolicy.boundary(LocalDateTime.parse("2024-02-29T00:00:00"),ZoneOffset.UTC,12,1));}
 @Test void quarterly(){assertEquals(Instant.parse("2025-04-30T00:00:00Z"),CalendarPolicy.boundary(LocalDateTime.parse("2025-01-31T00:00:00"),ZoneOffset.UTC,3,1));}
 @Test void dstGapMovesForward(){assertEquals(Instant.parse("2025-03-09T07:30:00Z"),CalendarPolicy.boundary(LocalDateTime.parse("2025-02-09T02:30:00"),ZoneId.of("America/New_York"),1,1));}
 @Test void halfPeriod(){var a=Instant.EPOCH;var end=a.plusSeconds(100);assertEquals(50000,CalendarPolicy.prorate(149900,a,end,a.plusSeconds(50))-CalendarPolicy.prorate(49900,a,end,a.plusSeconds(50)));}
 @Test void edges(){var a=Instant.EPOCH;var e=a.plusSeconds(100);assertEquals(49900,CalendarPolicy.prorate(49900,a,e,a));assertEquals(0,CalendarPolicy.prorate(49900,a,e,e));assertThrows(IllegalArgumentException.class,()->CalendarPolicy.prorate(100,a,e,e.plusSeconds(1)));}
}
