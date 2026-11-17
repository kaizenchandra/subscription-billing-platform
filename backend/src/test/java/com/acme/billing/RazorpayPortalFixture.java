package com.acme.billing;
/** Launch with test classes on the classpath. No real Razorpay request is sent. */
public class RazorpayPortalFixture {
 public static void main(String[] args){new org.springframework.boot.builder.SpringApplicationBuilder(Application.class,FakeRazorpayConfiguration.class).run(args);}
}
