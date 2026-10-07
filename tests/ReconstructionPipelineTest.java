import com.cameraprofile.studio.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

public class ReconstructionPipelineTest {
  public static void main(String[] args) throws Exception {
    int sw = 1152, sh = 1536;
    BufferedImage src = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < sh; y++)
      for (int x = 0; x < sw; x++) {
        int r = x * 255 / (sw - 1), g = y * 255 / (sh - 1), b = ((x / 12 + y / 12) % 2) * 120 + 60;
        src.setRGB(x, y, (r << 16) | (g << 8) | b);
      }
    ResolutionPlan plan = new ResolutionPlan(sw, sh, 4032, 3024);
    BufferedImage out = new BufferedImage(plan.width, plan.height, BufferedImage.TYPE_INT_RGB);
    long start = System.nanoTime();
    PixelReconstructor.reconstruct(
        sw,
        sh,
        plan.width,
        plan.height,
        (y, r) -> src.getRGB(0, y, sw, 1, r, 0, sw),
        (y, r) -> out.setRGB(0, y, plan.width, 1, r, 0, plan.width),
        () -> false,
        (a, b) -> {},
        4);
    PixelReconstructor.detail(
        plan.width,
        plan.height,
        (y, r) -> out.getRGB(0, y, plan.width, 1, r, 0, plan.width),
        (y, r) -> out.setRGB(0, y, plan.width, 1, r, 0, plan.width),
        () -> false);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    ImageIO.write(out, "jpg", encoded);
    byte[] result =
        JpegEngine.rebuild(
            encoded.toByteArray(),
            JpegEngine.exif(
                "Apple",
                "iPhone 18 Pro Max",
                1,
                plan.width,
                plan.height,
                "2026:10:06 19:00:00",
                "+08:00",
                1.48,
                24,
                null,
                null));
    Files.write(Paths.get("tests/reconstructed-12mp.jpg"), result);
    ByteArrayOutputStream archive = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(archive)) {
      z.putNextEntry(new ZipEntry("PROFILE_test.jpg"));
      z.write(result);
      z.closeEntry();
    }
    try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
      if (z.getNextEntry() == null) throw new AssertionError();
      ByteArrayOutputStream back = new ByteArrayOutputStream();
      byte[] buffer = new byte[32768];
      int n;
      while ((n = z.read(buffer)) != -1) back.write(buffer, 0, n);
      if (!JpegEngine.hash(result).equals(JpegEngine.hash(back.toByteArray())))
        throw new AssertionError("zip hash");
    }
    System.out.println(
        "PASS: 1152x1536 -> "
            + plan.width
            + "x"
            + plan.height
            + ", JPEG + EXIF + ZIP byte identity; desktop elapsed "
            + ((System.nanoTime() - start) / 1000000)
            + "ms");
  }
}
