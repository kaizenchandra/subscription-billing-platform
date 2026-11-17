package com.acme.billing.documents;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.util.*;
import java.nio.file.*;
class InvoicePdfRendererTest {
 static InvoiceSnapshot snapshot(List<InvoiceSnapshot.Line> lines,long total){return new InvoiceSnapshot("00000000-0000-0000-0000-000000000001",42,"Accrual sandbox billing","Customer-123","2026-11-17T00:00:00Z","2026-11-01T00:00:00Z","2026-12-01T00:00:00Z","INR",total,"PRORATION",lines);}
 static InvoiceSnapshot.Line line(String label,long amount){return new InvoiceSnapshot.Line(label,"professional-v1","2026-11-01T00:00:00Z","2026-12-01T00:00:00Z",amount);}
 @Test void exactMoneyAndSignedAdjustmentLinesRenderAndExtract() throws Exception {
  byte[] bytes=new InvoicePdfRenderer().render(snapshot(List.of(line("Unused old plan credit",-24950),line("Remaining new plan charge",74950)),50000));
  try(var doc=Loader.loadPDF(bytes)){String text=new PDFTextStripper().getText(doc);assertTrue(text.contains("INR -249.50"));assertTrue(text.contains("INR 749.50"));assertTrue(text.contains("INR 500.00"));assertTrue(text.contains("FINALIZED TOTAL"));assertEquals(1,doc.getNumberOfPages());
   Path directory=Path.of("target/pdf-qa");Files.createDirectories(directory);Files.write(directory.resolve("invoice-proration.pdf"),bytes);javax.imageio.ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(0,120),"png",directory.resolve("invoice-proration.png").toFile());
  }
  assertEquals("JPY 1",InvoicePdfRenderer.money(1,"JPY"));assertEquals("KWD 0.001",InvoicePdfRenderer.money(1,"KWD"));
 }
 @Test void multipageAndLongDescriptionsPreserveEveryLine() throws Exception {
  var lines=new ArrayList<InvoiceSnapshot.Line>();lines.add(line("Extended description ".repeat(90),100));for(int n=0;n<80;n++)lines.add(line("Unique service line "+n,100));
  byte[] bytes=new InvoicePdfRenderer().render(snapshot(lines,8100));try(var doc=Loader.loadPDF(bytes)){String text=new PDFTextStripper().getText(doc);assertTrue(doc.getNumberOfPages()>2);for(int n=0;n<80;n++)assertTrue(text.contains("Unique service line "+n));assertTrue(text.contains("INR 81.00"));
   Path directory=Path.of("target/pdf-qa");Files.createDirectories(directory);Files.write(directory.resolve("invoice-multipage.pdf"),bytes);var render=new PDFRenderer(doc);javax.imageio.ImageIO.write(render.renderImageWithDPI(0,100),"png",directory.resolve("invoice-multipage-first.png").toFile());javax.imageio.ImageIO.write(render.renderImageWithDPI(doc.getNumberOfPages()-1,100),"png",directory.resolve("invoice-multipage-last.png").toFile());
  }
 }
 @Test void nonAsciiIdentifiersAreEscapedRatherThanSilentlyDropped() throws Exception {
  byte[] bytes=new InvoicePdfRenderer().render(snapshot(List.of(line("Seat for \u03bb",100)),100));try(var doc=Loader.loadPDF(bytes)){assertTrue(new PDFTextStripper().getText(doc).contains("\\u{03BB}"));}
 }
}
