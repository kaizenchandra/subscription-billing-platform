package com.acme.billing.provider;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
public final class RazorpaySignatures {
 private RazorpaySignatures(){}
 public static boolean valid(byte[] body,String signature,String secret){
  if(signature==null || !signature.matches("[0-9a-fA-F]{64}") || secret.isBlank())return false;
  try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature));}catch(Exception e){throw new IllegalStateException("Signature verification unavailable");}
 }
}
