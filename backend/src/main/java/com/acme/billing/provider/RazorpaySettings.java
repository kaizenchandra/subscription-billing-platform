package com.acme.billing.provider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public final class RazorpaySettings {
 public final boolean enabled;
 private final String keyId,keySecret,webhookSecret,previousWebhookSecret,accountId;
 public RazorpaySettings(@Value("${billing.razorpay.enabled:false}") boolean enabled,
  @Value("${billing.razorpay.key-id:}") String keyId,@Value("${billing.razorpay.key-secret:}") String keySecret,
  @Value("${billing.razorpay.webhook-secret:}") String webhookSecret,@Value("${billing.razorpay.previous-webhook-secret:}") String previousWebhookSecret,
  @Value("${billing.razorpay.account-id:}") String accountId){
  this.enabled=enabled;this.keyId=keyId;this.keySecret=keySecret;this.webhookSecret=webhookSecret;this.previousWebhookSecret=previousWebhookSecret;this.accountId=accountId;
  if(enabled && (!keyId.matches("rzp_test_[A-Za-z0-9]+") || keySecret.isBlank() || webhookSecret.length()<16 || !accountId.matches("acc_[A-Za-z0-9]+")))throw new IllegalArgumentException("Razorpay requires test-mode credentials, account ID and a webhook secret of at least 16 characters");
 }
 public String publicKey(){return keyId;}
 String secret(){return keySecret;} public String account(){return accountId;}
 public boolean webhookValid(byte[] body,String signature){return enabled&&(RazorpaySignatures.valid(body,signature,webhookSecret)||(!previousWebhookSecret.isBlank()&&RazorpaySignatures.valid(body,signature,previousWebhookSecret)));}
 public boolean checkoutValid(String order,String payment,String signature){return enabled&&RazorpaySignatures.valid((order+"|"+payment).getBytes(java.nio.charset.StandardCharsets.UTF_8),signature,keySecret);}
}
