package com.acme.billing;

import java.time.*;
import java.util.UUID;

/** Values are snapshotted when dunning starts. No random timing in financial tests. */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration retryWindow, Duration gracePeriod) {
    public RetryPolicy {
        if(maxAttempts<1 || maxAttempts>8 || baseDelay.isNegative() || baseDelay.isZero()
            || baseDelay.compareTo(Duration.ofDays(2))>0 || retryWindow.compareTo(Duration.ofDays(30))>0 || gracePeriod.compareTo(Duration.ofDays(90))>0
            || retryWindow.compareTo(baseDelay)<0 || gracePeriod.compareTo(retryWindow)<0)
            throw new IllegalArgumentException("Invalid retry policy bounds");
    }
    public static RetryPolicy standard(){return new RetryPolicy(4,Duration.ofHours(6),Duration.ofDays(7),Duration.ofDays(10));}
    public Instant next(UUID payment,int completedAttempts,Instant now,Duration retryAfter){
        return next(payment,completedAttempts,now,retryAfter,baseDelay.toSeconds());
    }
    public Instant next(UUID payment,int completedAttempts,Instant now,Duration retryAfter,long snapshotBaseSeconds){
        long base=Math.min(snapshotBaseSeconds* (1L << Math.min(completedAttempts-1,7)),Duration.ofDays(2).toSeconds());
        long jitter=Math.floorMod(payment.hashCode()+completedAttempts*31L,Math.max(1,base/5));
        return now.plusSeconds(Math.max(base+jitter,retryAfter.toSeconds()));
    }
}
