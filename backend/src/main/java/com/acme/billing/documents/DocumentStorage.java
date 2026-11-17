package com.acme.billing.documents;
public interface DocumentStorage {
 int MAX_BYTES=2097152;
 void put(String key,byte[] bytes);
 byte[] get(String key);
 static void validateKey(String key){if(!key.matches("invoices/[a-f0-9-]{36}/v1/[a-f0-9]{64}\\.pdf"))throw new IllegalArgumentException("Invalid document object key");}
}
