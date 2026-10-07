import com.cameraprofile.studio.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ContainerTest {
  static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
  }

  public static void main(String[] args) throws Exception {
    for (String name :
        new String[] {"input.jpg", "progressive.jpg", "progressive-late-metadata.jpg", "late-credential.jpg"}) {
      File in = new File("tests/" + name), out = new File("tests/stream-" + name);
      JpegEngine.Metadata m = new JpegEngine.Metadata();
      m.make = "TEST";
      m.model = "TEST FIXTURE";
      m.width = 120;
      m.height = 80;
      m.aperture = 2.8;
      m.iso = 400;
      m.exposureSeconds = 1 / 32000.0;
      m.exposureBias = -0.3;
      m.whiteBalance = 0;
      m.date = "2026:10:06 20:00:00";
      m.zone = "+08:00";
      JpegFiles.rebuild(in, out, JpegEngine.exif(m), () -> false);
      check(
          JpegFiles.codingHash(in, () -> false).equals(JpegFiles.codingHash(out, () -> false)),
          "coding identity " + name);
      Map<String, Object> tags = ExifReader.read(out);
      check(tags.get("Software").equals(JpegEngine.SOFTWARE), "software");
      check(
          Math.abs(((Number) tags.get("ExposureTime")).doubleValue() - 1 / 32000.0) < 1e-12,
          "exact reciprocal shutter");
      check(
          Math.abs(((Number) tags.get("ExposureBiasValue")).doubleValue() + 0.3) < 1e-12,
          "signed rational");
      check(tags.get("OffsetTimeOriginal").equals("+08:00"), "offset");
      check(!JpegEngine.credentials(Files.readAllBytes(out.toPath())), "old credentials omitted");
    }
    for (String name : new String[] {"truncated.jpg"}) {
      try {
        JpegFiles.preflight(new File("tests/" + name), () -> false);
        throw new AssertionError("accepted " + name);
      } catch (IOException expected) {
      }
    }
    try {
      JpegFiles.rebuild(
          new File("tests/input.jpg"), new File("tests/cancelled.jpg"), new byte[0], () -> true);
      throw new AssertionError("cancel");
    } catch (InterruptedIOException expected) {
    }
    try {
      MemoryBudget.require(1500, 2000, 3024, 4032, 1024, true);
      throw new AssertionError("memory guard");
    } catch (IllegalArgumentException expected) {
    }
    try {
      MemoryBudget.require(100, 100, 11648, 8736, 8L << 30, false);
      throw new AssertionError("cap");
    } catch (IllegalArgumentException expected) {
    }
    MemoryBudget.require(1500, 2000, 3024, 4032, 512L << 20, true);
    System.out.println(
        "PASS: streaming baseline/progressive rebuild, late metadata, exact exposure, signed"
            + " values, credentials, truncation, cancellation and memory gates");
  }
}
