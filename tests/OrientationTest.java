import com.cameraprofile.studio.PixelOrientation;

public class OrientationTest {
  public static void main(String[] args) {
    int[][] expected = {
      {1, 2, 3, 4, 5, 6},
      {3, 2, 1, 6, 5, 4},
      {6, 5, 4, 3, 2, 1},
      {4, 5, 6, 1, 2, 3},
      {1, 4, 2, 5, 3, 6},
      {4, 1, 5, 2, 6, 3},
      {6, 3, 5, 2, 4, 1},
      {3, 6, 2, 5, 1, 4}
    };
    for (int o = 1; o <= 8; o++) {
      int w = o >= 5 ? 2 : 3, h = o >= 5 ? 3 : 2;
      int[] out = new int[6];
      float[] m = PixelOrientation.matrix(o, 3, 2);
      for (int y = 0; y < 2; y++)
        for (int x = 0; x < 3; x++) {
          float sx = x + 0.5f, sy = y + 0.5f;
          int dx = (int) (m[0] * sx + m[1] * sy + m[2]), dy = (int) (m[3] * sx + m[4] * sy + m[5]);
          if (dx < 0 || dx >= w || dy < 0 || dy >= h) throw new AssertionError("bounds" + o);
          out[dy * w + dx] = y * 3 + x + 1;
        }
      if (!java.util.Arrays.equals(out, expected[o - 1]))
        throw new AssertionError("orientation" + o);
    }
    System.out.println("PASS: all eight EXIF orientations mapped correctly");
  }
}
