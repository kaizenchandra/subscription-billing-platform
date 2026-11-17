package com.acme.billing;
import java.time.*;
import java.math.*;
public final class CalendarPolicy {
 private CalendarPolicy(){}
 public static Instant boundary(LocalDateTime anchor, ZoneId zone, int months, long cycle){
  if(months<1 || cycle<0) throw new IllegalArgumentException("Invalid recurrence");
  return anchor.plusMonths(Math.multiplyExact(months,cycle)).atZone(zone).toInstant();
 }
 public static long prorate(long amount, Instant start, Instant end, Instant change){
  if(amount<0 || !end.isAfter(start) || change.isBefore(start) || change.isAfter(end)) throw new IllegalArgumentException("Invalid proration interval");
  return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(Duration.between(change,end).toSeconds())).divide(BigDecimal.valueOf(Duration.between(start,end).toSeconds()),0,RoundingMode.HALF_EVEN).longValueExact();
 }
}
