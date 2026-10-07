package com.cameraprofile.studio;

import java.io.*;

/**
 * Fixed-shape neural tiles mapped into any requested output dimensions. Neural residuals are
 * bounded, and feather back to the classical reconstruction near tile boundaries. There is no
 * unbounded canvas of intermediate 4x pixels.
 */
public final class NeuralTiles {
  public interface Model {
    int inputSize();

    int scale();

    void infer(float[] input, float[] output) throws Exception;
  }

  public static int tileCount(int width, int height, int tileSize) {
    int core = tileSize - 16;
    if (core < 1) throw new IllegalArgumentException("AI 输入尺寸无效");
    return ((width + core - 1) / core) * ((height + core - 1) / core);
  }

  public static void apply(
      int sw,
      int sh,
      int dw,
      int dh,
      PixelReconstructor.Source source,
      PixelReconstructor.Source base,
      PixelReconstructor.Sink sink,
      Model model,
      int strength,
      PixelReconstructor.Cancel cancel,
      PixelReconstructor.Progress progress)
      throws Exception {
    if (sw < 1 || sh < 1 || dw < 1 || dh < 1 || strength < 0 || strength > 100)
      throw new IllegalArgumentException("AI 重建参数无效");
    if (strength == 0) return;
    int size = model.inputSize(),
        scale = model.scale(),
        pad = 8,
        core = size - 2 * pad,
        large = size * scale;
    if (core < 1 || scale < 1 || large > 4096) throw new IllegalArgumentException("AI 模型形状无效");
    float[] input = new float[size * size * 3], output = new float[large * large * 3];
    int[] sourceRow = new int[sw], baseRow = new int[dw];
    int total = tileCount(sw, sh, size), done = 0;
    for (int ty = 0; ty < sh; ty += core)
      for (int tx = 0; tx < sw; tx += core) {
        if (cancel.requested()) throw new InterruptedIOException("AI 重建已取消");
        for (int y = 0; y < size; y++) {
          source.row(Math.max(0, Math.min(sh - 1, ty + y - pad)), sourceRow);
          for (int x = 0; x < size; x++) {
            int pixel = sourceRow[Math.max(0, Math.min(sw - 1, tx + x - pad))],
                pos = (y * size + x) * 3;
            input[pos] = (pixel >> 16) & 255;
            input[pos + 1] = (pixel >> 8) & 255;
            input[pos + 2] = pixel & 255;
          }
        }
        model.infer(input, output);
        if (cancel.requested()) throw new InterruptedIOException("AI 重建已取消");
        int right = Math.min(sw, tx + core), bottom = Math.min(sh, ty + core);
        int firstX = (int) ((long) tx * dw / sw), lastX = (int) ((long) right * dw / sw);
        int firstY = (int) ((long) ty * dh / sh), lastY = (int) ((long) bottom * dh / sh);
        for (int y = firstY; y < lastY; y++) {
          base.row(y, baseRow);
          double sy = (y + 0.5) * sh / dh - 0.5;
          float fy = (float) ((sy - ty + pad + 0.5) * scale - 0.5);
          float featherY = feather(sy, ty, bottom, sh);
          for (int x = firstX; x < lastX; x++) {
            double sx = (x + 0.5) * sw / dw - 0.5;
            float fx = (float) ((sx - tx + pad + 0.5) * scale - 0.5);
            float weight = strength / 100f * featherY * feather(sx, tx, right, sw);
            int pixel = baseRow[x], rgb = 0xff000000;
            for (int channel = 0; channel < 3; channel++) {
              int shift = 16 - channel * 8, value = (pixel >> shift) & 255;
              float predicted = sample(output, large, fx, fy, channel);
              if (!Float.isFinite(predicted)) throw new IOException("AI 模型输出非有限数值");
              float residual = Math.max(-16, Math.min(16, predicted - value));
              int corrected = Math.max(0, Math.min(255, Math.round(value + residual * weight)));
              rgb |= corrected << shift;
            }
            baseRow[x] = rgb;
          }
          sink.row(y, baseRow);
        }
        progress.update(++done, total);
      }
  }

  private static float feather(double position, int start, int end, int full) {
    double distance = 4;
    if (start > 0) distance = Math.min(distance, position - start + 0.5);
    if (end < full) distance = Math.min(distance, end - position - 0.5);
    return (float) Math.max(0, Math.min(1, distance / 4));
  }

  private static float sample(float[] pixels, int width, float x, float y, int channel) {
    x = Math.max(0, Math.min(width - 1, x));
    y = Math.max(0, Math.min(width - 1, y));
    int x0 = (int) Math.floor(x),
        y0 = (int) Math.floor(y),
        x1 = Math.min(width - 1, x0 + 1),
        y1 = Math.min(width - 1, y0 + 1);
    float wx = x - x0, wy = y - y0;
    float top =
        pixels[(y0 * width + x0) * 3 + channel] * (1 - wx)
            + pixels[(y0 * width + x1) * 3 + channel] * wx;
    float bottom =
        pixels[(y1 * width + x0) * 3 + channel] * (1 - wx)
            + pixels[(y1 * width + x1) * 3 + channel] * wx;
    return top * (1 - wy) + bottom * wy;
  }
}
