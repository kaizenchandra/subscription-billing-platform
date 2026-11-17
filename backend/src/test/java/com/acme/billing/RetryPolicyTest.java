package com.acme.billing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.UUID;
class RetryPolicyTest {
 @Test void exponentialJitterIsBoundedAndRepeatable(){
  var p=RetryPolicy.standard();var id=UUID.fromString("00000000-0000-0000-0000-000000000001");var now=Instant.EPOCH;
  var first=p.next(id,1,now,Duration.ZERO);assertEquals(first,p.next(id,1,now,Duration.ZERO));
  assertTrue(!first.isBefore(now.plus(Duration.ofHours(6))));assertTrue(first.isBefore(now.plus(Duration.ofHours(6)).plusSeconds(4320)));
  assertTrue(p.next(id,2,now,Duration.ZERO).isAfter(first));
  assertTrue(!p.next(id,1,now,Duration.ofDays(1)).isBefore(now.plus(Duration.ofDays(1))));
 }
 @Test void rejectsUnboundedPolicies(){assertThrows(IllegalArgumentException.class,()->new RetryPolicy(9,Duration.ofSeconds(1),Duration.ofDays(1),Duration.ofDays(2)));assertThrows(IllegalArgumentException.class,()->new RetryPolicy(4,Duration.ZERO,Duration.ofDays(1),Duration.ofDays(2)));}
}
