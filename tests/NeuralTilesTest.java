import com.cameraprofile.studio.*;
import java.io.*;
import java.util.*;

public class NeuralTilesTest {
  static class Model implements NeuralTiles.Model {
    public int inputSize() {
      return 50;
    }

    public int scale() {
      return 4;
    }

    public void infer(float[] input, float[] output) {
      Arrays.fill(output, 255);
    }
  }

  public static void main(String[] args) throws Exception {
    for (int[] size :
        new int[][] {{1, 1, 7, 9}, {69, 73, 213, 279}, {150, 200, 403, 537}, {41, 67, 23, 39}}) {
      int sw = size[0], sh = size[1], dw = size[2], dh = size[3];
      int[] out = new int[dw * dh];
      Arrays.fill(out, 0xff808080);
      boolean[] seen = new boolean[out.length];
      PixelReconstructor.Source src = (y, r) -> Arrays.fill(r, 0xff808080);
      PixelReconstructor.Source dst = (y, r) -> System.arraycopy(out, y * dw, r, 0, dw);
      PixelReconstructor.Sink sink =
          (y, r) -> {
            for (int x = 0; x < dw; x++) {
              if (out[y * dw + x] != r[x]) {
                if (seen[y * dw + x]) throw new AssertionError("overlapping writes");
                seen[y * dw + x] = true;
              }
            }
            System.arraycopy(r, 0, out, y * dw, dw);
          };
      NeuralTiles.apply(
          sw, sh, dw, dh, src, dst, sink, new Model(), 25, () -> false, (done, total) -> {});
      for (int value : out)
        for (int shift : new int[] {0, 8, 16}) {
          int c = (value >> shift) & 255;
          if (c < 128 || c > 132) throw new AssertionError("residual guard");
        }
      int[] identity = out.clone();
      NeuralTiles.apply(sw, sh, dw, dh, src, dst, sink, new Model(), 0, () -> false, (a, b) -> {});
      if (!Arrays.equals(identity, out)) throw new AssertionError("zero strength");
    }
    try {
      NeuralTiles.apply(
          1,
          1,
          7,
          9,
          (y, r) -> {},
          (y, r) -> {},
          (y, r) -> {},
          new Model(),
          25,
          () -> true,
          (a, b) -> {});
      throw new AssertionError("cancel");
    } catch (InterruptedIOException expected) {
    }
    System.out.println(
        "PASS: arbitrary neural target sizes, tiny images, tile partition, bounded strength, zero"
            + " strength and cancellation");
  }
}
