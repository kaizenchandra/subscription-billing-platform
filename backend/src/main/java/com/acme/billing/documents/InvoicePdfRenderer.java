package com.acme.billing.documents;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

@Component
public class InvoicePdfRenderer {
 private static final PDType1Font REGULAR=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
 private static final PDType1Font BOLD=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
 public byte[] render(InvoiceSnapshot invoice){
  try(var document=new PDDocument();var output=new ByteArrayOutputStream()){
   var layout=new Layout(document,invoice);
   layout.page();
   layout.paragraph("BILLED ACCOUNT",BOLD,9,507);
   layout.paragraph(invoice.account(),REGULAR,10,507);
   layout.gap(12);
   layout.paragraph("ISSUED (UTC)  "+invoice.issuedAt(),REGULAR,9,507);
   layout.paragraph("PERIOD START  "+invoice.periodStart(),REGULAR,9,507);
   layout.paragraph("PERIOD END    "+invoice.periodEnd(),REGULAR,9,507);
   layout.gap(20);layout.tableHeader();
   for(var line:invoice.lines()){
    var description=layout.wrap(line.description(),REGULAR,10,355);
    var price=layout.wrap("Price version: "+line.price(),REGULAR,8,355);
    float rowHeight=description.size()*15+price.size()*12+42;
    layout.ensure(rowHeight<=400?rowHeight:55);
    float top=layout.y;
    layout.right(551,top,BOLD,10,money(line.amount(),invoice.currency()));
    for(String text:description){layout.ensure(24);layout.text(44,layout.y,REGULAR,10,text,0.1f,0.18f,0.19f);layout.y-=15;}
    for(String text:price){layout.ensure(20);layout.text(44,layout.y,REGULAR,8,text,0.35f,0.43f,0.42f);layout.y-=12;}
    layout.ensure(35);layout.text(44,layout.y,REGULAR,8,line.periodStart()+" to "+line.periodEnd(),0.35f,0.43f,0.42f);layout.y-=18;
    layout.rule(layout.y+3);layout.y-=16;
   }
   layout.ensure(175);layout.gap(12);
   layout.text(44,layout.y,BOLD,10,"FINALIZED TOTAL",0.1f,0.18f,0.19f);layout.right(551,layout.y,BOLD,18,money(invoice.total(),invoice.currency()));layout.y-=32;
   layout.paragraph("Tax calculation is not configured. Current charges use the zero-tax demo model.",REGULAR,8,507);
   layout.paragraph("This original invoice records finalized charges only. Payment allocations, credits and refunds are available in the billing portal and do not modify this document.",REGULAR,8,507);
   layout.paragraph("Legal billing address and tax identifiers have not been configured. This sandbox template is not a jurisdiction-certified tax invoice.",REGULAR,8,507);
   layout.close();
   int count=document.getNumberOfPages();
   for(int page=0;page<count;page++)try(var stream=new PDPageContentStream(document,document.getPage(page),PDPageContentStream.AppendMode.APPEND,true)){
    stream.setNonStrokingColor(0.35f,0.43f,0.42f);stream.beginText();stream.setFont(REGULAR,8);stream.newLineAtOffset(44,34);stream.showText("INV-"+invoice.number()+"  |  Original charges  |  Page "+(page+1)+" of "+count);stream.endText();
   }
   document.getDocumentInformation().setTitle("Invoice INV-"+invoice.number());document.getDocumentInformation().setCreator("Accrual invoice template v1");document.save(output);
   byte[] bytes=output.toByteArray();if(bytes.length>DocumentStorage.MAX_BYTES)throw new IllegalStateException("Invoice PDF exceeds size limit");return bytes;
  }catch(IOException e){throw new IllegalStateException("Invoice PDF rendering failed");}
 }
 public static String money(long minor,String currency){int scale=Currency.getInstance(currency).getDefaultFractionDigits();if(scale<0)throw new IllegalArgumentException("Unsupported currency");return currency+" "+BigDecimal.valueOf(minor,scale).toPlainString();}
 private static String ascii(String text){var result=new StringBuilder();for(int c:text.codePoints().toArray()){if(c>=32&&c<=126)result.appendCodePoint(c);else if(c<32)result.append(' ');else result.append(String.format("\\u{%04X}",c));}return result.toString();}
 private static final class Layout implements AutoCloseable {
  private final PDDocument doc;private final InvoiceSnapshot invoice;private PDPageContentStream stream;private float y;
  Layout(PDDocument doc,InvoiceSnapshot invoice){this.doc=doc;this.invoice=invoice;}
  void page()throws IOException{
   if(doc.getNumberOfPages()>=64)throw new IllegalStateException("Invoice exceeds 64-page layout limit");
   if(stream!=null)stream.close();var page=new PDPage(PDRectangle.A4);doc.addPage(page);stream=new PDPageContentStream(doc,page);
   stream.setNonStrokingColor(0.06f,0.19f,0.19f);stream.addRect(0,722,595,120);stream.fill();
   text(44,802,BOLD,19,"ACCRUAL",1,1,1);text(44,779,REGULAR,9,"SUBSCRIPTION BILLING",0.7f,0.87f,0.81f);
   String number="INV-"+invoice.number();text(551-BOLD.getStringWidth(number)/1000*17,802,BOLD,17,number,1,1,1);text(44,750,REGULAR,9,invoice.kind().equals("PRORATION")?"PRORATION INVOICE":"INVOICE",1,1,1);
   y=695;paragraph(invoice.issuer(),BOLD,11,507);paragraph("Reference: "+invoice.id(),REGULAR,8,507);gap(16);
  }
  void ensure(float height)throws IOException{if(y-height<72){page();tableHeader();}}
  void gap(float height){y-=height;}
  void paragraph(String text,PDFont font,float size,float width)throws IOException{for(String row:wrap(text,font,size,width)){if(y<85)page();text(44,y,font,size,row,0.22f,0.31f,0.30f);y-=size+5;}}
  List<String> wrap(String value,PDFont font,float size,float width)throws IOException{
   List<String> rows=new ArrayList<>();var row=new StringBuilder();for(char c:ascii(value).toCharArray()){
    if(font.getStringWidth(row.toString()+c)/1000*size>width&&!row.isEmpty()){rows.add(row.toString());row.setLength(0);}row.append(c);
   }if(!row.isEmpty())rows.add(row.toString());return rows;
  }
  void tableHeader()throws IOException{text(44,y,BOLD,9,"DESCRIPTION / SERVICE PERIOD",0.18f,0.38f,0.33f);right(551,y,BOLD,9,"AMOUNT");y-=15;rule(y+4);y-=15;}
  void rule(float at)throws IOException{stream.setStrokingColor(0.82f,0.88f,0.85f);stream.moveTo(44,at);stream.lineTo(551,at);stream.stroke();}
  void right(float x,float at,PDFont font,float size,String value)throws IOException{String text=ascii(value);text(x-font.getStringWidth(text)/1000*size,at,font,size,text,0.10f,0.23f,0.20f);}
  void text(float x,float at,PDFont font,float size,String value,float r,float g,float b)throws IOException{stream.setNonStrokingColor(r,g,b);stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,at);stream.showText(ascii(value));stream.endText();}
  public void close()throws IOException{if(stream!=null){stream.close();stream=null;}}
 }
}
