import com.cameraprofile.studio.Io;
import java.io.*;
import java.nio.file.*;

public class CredentialTest {
  static byte[] ascii(String value) throws Exception {
    return value.getBytes("US-ASCII");
  }

  static byte[] png(String type, byte[] payload) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    out.writeInt(payload.length);
    out.write(ascii(type));
    out.write(payload);
    out.writeInt(0);
    out.writeInt(0);
    out.write(ascii("IEND"));
    out.writeInt(0);
    return bytes.toByteArray();
  }

  static byte[] webp(String type, byte[] payload) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.write(ascii("RIFF"));
    out.writeInt(Integer.reverseBytes(12 + payload.length + (payload.length & 1)));
    out.write(ascii("WEBP"));
    out.write(ascii(type));
    out.writeInt(Integer.reverseBytes(payload.length));
    out.write(payload);
    if ((payload.length & 1) != 0) out.writeByte(0);
    return bytes.toByteArray();
  }

  static byte[] heif(boolean protectedFile) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(16);
    out.write(ascii("ftyp"));
    out.write(ascii("heic"));
    out.writeInt(0);
    if (protectedFile) {
      out.writeInt(24);
      out.write(ascii("uuid"));
      out.write(
          new byte[] {
            (byte) 0xd8,
            (byte) 0xfe,
            (byte) 0xc3,
            (byte) 0xd6,
            0x1b,
            0x0e,
            0x48,
            0x3c,
            (byte) 0x92,
            (byte) 0x97,
            0x58,
            0x28,
            (byte) 0x87,
            0x7e,
            (byte) 0xc4,
            (byte) 0x81
          });
    } else {
      byte[] data = ascii("compressed pixel entropy contains c2pa jumb");
      out.writeInt(8 + data.length);
      out.write(ascii("mdat"));
      out.write(data);
    }
    return bytes.toByteArray();
  }

  static void check(byte[] bytes, boolean reject, String label) throws Exception {
    File file = Files.createTempFile("credential-test-", ".bin").toFile();
    try {
      Files.write(file.toPath(), bytes);
      try {
        Io.guardOtherFormat(file);
        if (reject) throw new AssertionError(label);
      } catch (IOException error) {
        if (!reject) throw new AssertionError(label, error);
      }
    } finally {
      file.delete();
    }
  }

  public static void main(String[] args) throws Exception {
    byte[] words = ascii("random c2pa jumb pixel bytes");
    check(png("IDAT", words), false, "PNG entropy false positive");
    check(png("caBX", new byte[0]), true, "PNG credential");
    check(webp("VP8 ", words), false, "WebP entropy false positive");
    check(webp("C2PA", new byte[0]), true, "WebP credential");
    check(heif(false), false, "HEIF entropy false positive");
    check(heif(true), true, "HEIF UUID credential");
    byte[] truncated = png("IDAT", words);
    truncated[11] = 127;
    check(truncated, true, "truncated PNG chunk");
    System.out.println(
        "PASS: structured PNG/WebP/HEIF credential guards and compressed-pixel false positives");
  }
}
