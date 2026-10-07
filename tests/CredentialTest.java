import com.cameraprofile.studio.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Metadata is not an admission rule. Test real JPEG marker positions and exact input preservation. */
public class CredentialTest {
  public static void main(String[] args) throws Exception {
    byte[] original = Files.readAllBytes(Paths.get("tests/input.jpg"));
    byte[] exif = JpegEngine.exif("TEST", "PIXEL COPY", 1, 120, 80, null, null, null, null);
    for (int marker : new int[] {225, 235, 237}) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      byte[] payload = "synthetic c2pa contentcredentials".getBytes("US-ASCII");
      out.write(original, 0, 2); out.writeByte(255); out.writeByte(marker);
      out.writeShort(payload.length + 2); out.write(payload);
      out.write(original, 2, original.length - 2);
      Path input = Files.createTempFile("metadata-input-", ".jpg");
      Path output = Files.createTempFile("metadata-output-", ".jpg");
      try {
        byte[] inputBytes = bytes.toByteArray();
        Files.write(input, inputBytes);
        JpegFiles.preflight(input.toFile(), () -> false);
        JpegFiles.rebuild(input.toFile(), output.toFile(), exif, () -> false);
        byte[] result = Files.readAllBytes(output);
        if (JpegEngine.credentials(result)) throw new AssertionError("old marker copied");
        if (!Arrays.equals(JpegEngine.imagePayload(original), JpegEngine.imagePayload(result)))
          throw new AssertionError("coding changed");
        if (!Arrays.equals(inputBytes, Files.readAllBytes(input))) throw new AssertionError("input modified");
      } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }
    for (int[] size : new int[][] {{65,49},{49,65},{1,1},{137,137},{4032,3024},{3024,4032}}) {
      ResolutionPlan plan = ResolutionPlan.original(size[0], size[1]);
      if (plan.width != size[0] || plan.height != size[1] || plan.padded()
          || plan.left != 0 || plan.top != 0) throw new AssertionError("original dimensions");
    }
    System.out.println("PASS: metadata never blocks JPEG, old credentials omitted, input unchanged, exact original-size plans");
  }
}
