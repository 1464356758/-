import com.cameraprofile.studio.JpegEngine;
import java.nio.file.*;
import java.util.*;

public class EngineTest {
  public static void main(String[] args) throws Exception {
    for (int bad : new int[] {0, -1, 9, 65535})
      if (JpegEngine.normalizeOrientation(bad) != 1) throw new AssertionError();
    for (int valid = 1; valid <= 8; valid++)
      if (JpegEngine.normalizeOrientation(valid) != valid) throw new AssertionError();
    byte[] in = Files.readAllBytes(Paths.get(args[0]));
    byte[] out =
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Apple", "iPhone 15", 6, 120, 80, "2026:10:06 18:00:00", "+08:00", 1.6, 26));
    if (!Arrays.equals(JpegEngine.imagePayload(in), JpegEngine.imagePayload(out)))
      throw new AssertionError();
    Files.write(Paths.get(args[1]), out);
    byte[] cred = new byte[in.length + 8];
    System.arraycopy(in, 0, cred, 0, 2);
    byte[] app = {(byte) 255, (byte) 235, 0, 6, 'c', '2', 'p', 'a'};
    System.arraycopy(app, 0, cred, 2, 8);
    System.arraycopy(in, 2, cred, 10, in.length - 2);
    if (!JpegEngine.credentials(cred)) throw new AssertionError();
    try {
      JpegEngine.rebuild(cred, new byte[0]);
      throw new AssertionError();
    } catch (java.io.IOException expected) {
    }
    try {
      JpegEngine.segments(new byte[] {1, 2, 3});
      throw new AssertionError();
    } catch (java.io.IOException expected) {
    }
    System.out.println("PASS: payload identity, credentials rejection, invalid input rejection");
  }
}
