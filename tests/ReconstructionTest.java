import com.cameraprofile.studio.*;
import java.io.*;
import java.util.*;

public class ReconstructionTest {
  static int[] resize(int[] in, int sw, int sh, int dw, int dh, int workers) throws Exception {
    int[] out = new int[dw * dh];
    PixelReconstructor.reconstruct(
        sw,
        sh,
        dw,
        dh,
        (y, r) -> System.arraycopy(in, y * sw, r, 0, sw),
        (y, r) -> System.arraycopy(r, 0, out, y * dw, dw),
        () -> false,
        (a, b) -> {},
        workers);
    return out;
  }

  public static void main(String[] args) throws Exception {
    Random random = new Random(37);
    int[] in = new int[17 * 23];
    for (int i = 0; i < in.length; i++) in[i] = 0xff000000 | random.nextInt(1 << 24);
    if (!Arrays.equals(in, resize(in, 17, 23, 17, 23, 1))) throw new AssertionError("identity");
    for (int c : new int[] {0xff000000, 0xffffffff, 0xff123456}) {
      int[] flat = new int[12];
      Arrays.fill(flat, c);
      for (int n : resize(flat, 3, 4, 27, 35, 4))
        if (n != c) throw new AssertionError("flat color");
    }
    int[] one = resize(new int[] {0xff91827a}, 1, 1, 11, 13, 4);
    for (int n : one) if (n != 0xff91827a) throw new AssertionError("one pixel");
    int[] single = resize(in, 17, 23, 43, 57, 1), multi = resize(in, 17, 23, 43, 57, 4);
    if (!Arrays.equals(single, multi)) throw new AssertionError("worker nondeterminism");
    int[] mix = resize(new int[] {0xff000000, 0xffffffff}, 2, 1, 3, 1, 1);
    int center = mix[1] & 255;
    if (center < 185 || center > 190) throw new AssertionError("linear light" + center);
    int[] checker = new int[64 * 64];
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 64; x++)
        checker[y * 64 + x] = ((x + y) % 2 == 0) ? 0xff000000 : 0xffffffff;
    int[] small = resize(checker, 64, 64, 8, 8, 1);
    for (int y = 2; y < 6; y++)
      for (int x = 2; x < 6; x++)
        if (Math.abs((small[y * 8 + x] & 255) - 188) > 2) throw new AssertionError("antialias");
    boolean cancelled = false;
    try {
      PixelReconstructor.reconstruct(
          17, 23, 43, 57, (y, r) -> {}, (y, r) -> {}, () -> true, (a, b) -> {}, 4);
    } catch (InterruptedIOException e) {
      cancelled = true;
    }
    if (!cancelled) throw new AssertionError("cancel");
    int[] detail = single.clone();
    PixelReconstructor.detail(
        43,
        57,
        (y, r) -> System.arraycopy(detail, y * 43, r, 0, 43),
        (y, r) -> System.arraycopy(r, 0, detail, y * 43, 43),
        () -> false);
    for (int i = 0; i < detail.length; i++)
      for (int shift : new int[] {0, 8, 16})
        if (Math.abs(((detail[i] >> shift) & 255) - ((single[i] >> shift) & 255)) > 2)
          throw new AssertionError("detail guard");
    ResolutionPlan p = new ResolutionPlan(1500, 2000, 4032, 3024);
    if (p.width != 3024 || p.height != 4032 || p.padded())
      throw new AssertionError("non2x exact target");
    p = new ResolutionPlan(1000, 1000, 4032, 3024);
    if (!p.padded() || p.contentWidth != 3024 || p.contentHeight != 3024 || p.left != 504)
      throw new AssertionError("aspect fit");
    p = new ResolutionPlan(2000, 1500, 4032, 3024);
    if (p.width != 4032 || p.height != 3024 || p.padded()) throw new AssertionError("landscape");
    System.out.println(
        "PASS: identity, flat fields, tiny image, linear light, antialias, parallel determinism,"
            + " cancellation, bounded detail, exact targets and aspect fit");
  }
}
