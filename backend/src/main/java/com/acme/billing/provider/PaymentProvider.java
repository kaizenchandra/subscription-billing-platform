package com.acme.billing.provider;

public interface PaymentProvider {
    record Capabilities(boolean hostedCheckout,boolean automaticRecurring,boolean refunds,boolean setupWithoutCharge){}
    record Order(String id,long amount,String currency,String receipt){}
    record Payment(String id,String order,long amount,String currency,String status,boolean captured,String method,String last4){}
    Capabilities capabilities();
    Order createOrder(long amount,String currency,String receipt);
    Order fetchOrder(String orderId);
    Payment fetchPayment(String paymentId);
}
