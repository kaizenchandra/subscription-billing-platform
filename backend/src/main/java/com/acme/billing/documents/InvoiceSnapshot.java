package com.acme.billing.documents;
import java.util.List;
public record InvoiceSnapshot(String id,long number,String issuer,String account,String issuedAt,String periodStart,String periodEnd,String currency,long total,String kind,List<Line> lines){
 public record Line(String description,String price,String periodStart,String periodEnd,long amount){}
}
