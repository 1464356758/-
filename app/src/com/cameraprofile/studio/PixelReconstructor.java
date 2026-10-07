package com.cameraprofile.studio;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Classical image reconstruction, not a neural or generative super-resolution model. Separable
 * Lanczos-3 in linear-light sRGB; antialias on reduction; local range limiting on enlargement;
 * cached rows and bounded CPU worker count.
 */
public final class PixelReconstructor {
  public interface Source {
    void row(int y, int[] pixels) throws Exception;
  }

  public interface Sink {
    void row(int y, int[] pixels) throws Exception;
  }

  public interface Progress {
    void update(int completed, int total) throws Exception;
  }

  public interface Cancel {
    boolean requested();
  }

  interface Work {
    void run(int from, int to) throws Exception;
  }

  static final float[] LINEAR = new float[256];
  static final int[] SRGB = new int[65536];

  static {
    for (int i = 0; i < 256; i++) {
      double v = i / 255.0;
      LINEAR[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
    }
    for (int i = 0; i < 65536; i++) {
      double v = i / 65535.0;
      SRGB[i] =
          (int)
              Math.round(255 * (v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055));
    }
  }

  static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  static int encoded(float v) {
    return SRGB[Math.max(0, Math.min(65535, Math.round(v * 65535)))];
  }

  static double kernel(double x) {
    x = Math.abs(x);
    if (x < 1e-9) return 1;
    if (x >= 3) return 0;
    double p = Math.PI * x;
    return Math.sin(p) * Math.sin(p / 3) / (p * p / 3);
  }

  static final class Axis {
    final int[][] indices;
    final float[][] weights;
    final int[] near;

    Axis(int source, int target) {
      indices = new int[target][];
      weights = new float[target][];
      near = new int[target];
      double scale = Math.min(1, (double) target / source);
      for (int i = 0; i < target; i++) {
        double c = (i + 0.5) * source / target - 0.5;
        near[i] = Math.max(0, Math.min(source - 1, (int) Math.floor(c)));
        int first = (int) Math.ceil(c - 3 / scale), last = (int) Math.floor(c + 3 / scale);
        if (last - first > 512) throw new IllegalArgumentException("缩小比例过大，请先使用较小副本");
        indices[i] = new int[last - first + 1];
        weights[i] = new float[last - first + 1];
        double sum = 0;
        for (int j = first; j <= last; j++) {
          double k = kernel((c - j) * scale);
          indices[i][j - first] = Math.max(0, Math.min(source - 1, j));
          weights[i][j - first] = (float) k;
          sum += k;
        }
        for (int j = 0; j < weights[i].length; j++) weights[i][j] /= (float) sum;
      }
    }
  }

  static void parallel(ExecutorService pool, int workers, int count, Work work) throws Exception {
    if (pool == null) {
      work.run(0, count);
      return;
    }
    ArrayList<Callable<Void>> tasks = new ArrayList<>();
    for (int n = 0; n < workers; n++) {
      final int from = count * n / workers, to = count * (n + 1) / workers;
      tasks.add(
          () -> {
            work.run(from, to);
            return null;
          });
    }
    for (Future<Void> f : pool.invokeAll(tasks))
      try {
        f.get();
      } catch (ExecutionException e) {
        Throwable t = e.getCause();
        if (t instanceof Exception) throw (Exception) t;
        if (t instanceof Error) throw (Error) t;
        throw new IOException(t);
      }
  }

  public static void reconstruct(
      int sw,
      int sh,
      int dw,
      int dh,
      Source source,
      Sink sink,
      Cancel cancel,
      Progress progress,
      int requestedWorkers)
      throws Exception {
    if (sw < 1 || sh < 1 || dw < 1 || dh < 1)
      throw new IllegalArgumentException("invalid dimensions");
    int workers = Math.max(1, Math.min(4, requestedWorkers));
    ExecutorService pool = workers > 1 ? Executors.newFixedThreadPool(workers) : null;
    try {
      Axis ax = new Axis(sw, dw), ay = new Axis(sh, dh);
      LinkedHashMap<Integer, float[]> cache = new LinkedHashMap<>();
      int[] input = new int[sw], out = new int[dw];
      for (int y = 0; y < dh; y++) {
        if (cancel.requested()) throw new InterruptedIOException("已取消像素重建");
        HashSet<Integer> needed = new HashSet<>();
        for (int sy : ay.indices[y]) needed.add(sy);
        Iterator<Integer> it = cache.keySet().iterator();
        while (it.hasNext()) if (!needed.contains(it.next())) it.remove();
        for (int sy : needed) {
          if (cache.containsKey(sy)) continue;
          source.row(sy, input);
          float[] row = new float[dw * 3];
          parallel(
              pool,
              workers,
              dw,
              (from, to) -> {
                for (int x = from; x < to; x++) {
                  int[] ids = ax.indices[x];
                  float[] ws = ax.weights[x];
                  int center = input[ax.near[x]], next = input[Math.min(sw - 1, ax.near[x] + 1)];
                  for (int channel = 0; channel < 3; channel++) {
                    int shift = 16 - 8 * channel;
                    float value = 0;
                    for (int n = 0; n < ids.length; n++)
                      value += LINEAR[(input[ids[n]] >> shift) & 255] * ws[n];
                    if (dw >= sw) {
                      float a = LINEAR[(center >> shift) & 255], b = LINEAR[(next >> shift) & 255];
                      value = clamp(value, Math.min(a, b), Math.max(a, b));
                    }
                    row[x * 3 + channel] = clamp(value, 0, 1);
                  }
                }
              });
          cache.put(sy, row);
        }
        final float[][] rows = new float[ay.indices[y].length][];
        for (int n = 0; n < rows.length; n++) rows[n] = cache.get(ay.indices[y][n]);
        final float[] ws = ay.weights[y];
        final float[] nearA = cache.get(ay.near[y]);
        float[] b = cache.get(Math.min(sh - 1, ay.near[y] + 1));
        final float[] nearB = b == null ? nearA : b;
        parallel(
            pool,
            workers,
            dw,
            (from, to) -> {
              for (int x = from; x < to; x++) {
                int rgb = 0xff000000;
                for (int channel = 0; channel < 3; channel++) {
                  int pos = x * 3 + channel;
                  float value = 0;
                  for (int n = 0; n < rows.length; n++) value += rows[n][pos] * ws[n];
                  if (dh >= sh)
                    value =
                        clamp(
                            value,
                            Math.min(nearA[pos], nearB[pos]),
                            Math.max(nearA[pos], nearB[pos]));
                  rgb |= encoded(clamp(value, 0, 1)) << (16 - 8 * channel);
                }
                out[x] = rgb;
              }
            });
        sink.row(y, out);
        if (y % 32 == 0 || y == dh - 1) progress.update(y + 1, dh);
      }
    } finally {
      if (pool != null) pool.shutdownNow();
    }
  }

  /** Mild thresholded detail enhancement, limited to +/- 2 sRGB levels per channel. */
  public static void detail(int width, int height, Source source, Sink sink, Cancel cancel)
      throws Exception {
    int[] prev = new int[width], curr = new int[width], next = new int[width], out = new int[width];
    source.row(0, curr);
    System.arraycopy(curr, 0, prev, 0, width);
    if (height > 1) source.row(1, next);
    else System.arraycopy(curr, 0, next, 0, width);
    for (int y = 0; y < height; y++) {
      if (cancel.requested()) throw new InterruptedIOException("已取消细节修复");
      for (int x = 0; x < width; x++) {
        int left = Math.max(0, x - 1), right = Math.min(width - 1, x + 1), rgb = 0xff000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
          int c = (curr[x] >> shift) & 255;
          float blur =
              (((prev[left] >> shift) & 255)
                      + 2 * ((prev[x] >> shift) & 255)
                      + ((prev[right] >> shift) & 255)
                      + 2 * ((curr[left] >> shift) & 255)
                      + 4 * c
                      + 2 * ((curr[right] >> shift) & 255)
                      + ((next[left] >> shift) & 255)
                      + 2 * ((next[x] >> shift) & 255)
                      + ((next[right] >> shift) & 255))
                  / 16f;
          float delta = c - blur;
          int corrected = c;
          if (Math.abs(delta) > 2)
            corrected = Math.max(0, Math.min(255, Math.round(c + clamp(delta * 0.15f, -2, 2))));
          rgb |= corrected << shift;
        }
        out[x] = rgb;
      }
      sink.row(y, out);
      int[] reuse = prev;
      prev = curr;
      curr = next;
      next = reuse;
      if (y + 2 < height) source.row(y + 2, next);
      else System.arraycopy(curr, 0, next, 0, width);
    }
  }
}
