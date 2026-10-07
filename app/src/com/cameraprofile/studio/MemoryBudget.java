package com.cameraprofile.studio;

/** Conservative allocation planning; no silent resolution downgrade. */
public final class MemoryBudget {
  public static final long MAX_INPUT_PIXELS = 52000000L;
  public static final long MAX_OUTPUT_PIXELS = 52000000L;

  public static long estimate(int sw, int sh, int dw, int dh, boolean ai) {
    long source = (long) sw * sh * 4;
    long destination = (long) dw * dh * 4;
    long rotatePeak = source * 2;
    long reconstructPeak = source + destination;
    long encodePeak = destination + (long) dw * dh * 3;
    long nativeReserve = (ai ? 100L : 40L) * 1024 * 1024;
    return Math.max(Math.max(rotatePeak, reconstructPeak), encodePeak) + nativeReserve;
  }

  public static void require(int sw, int sh, int dw, int dh, long available, boolean ai) {
    if (sw < 1 || sh < 1 || dw < 1 || dh < 1) throw new IllegalArgumentException("图片尺寸无效");
    if ((long) sw * sh > MAX_INPUT_PIXELS)
      throw new IllegalArgumentException("输入超过 5200 万像素，请选择较小副本");
    if ((long) dw * dh > MAX_OUTPUT_PIXELS)
      throw new IllegalArgumentException("目标超过 5200 万像素，请选择较低尺寸");
    if (estimate(sw, sh, dw, dh, ai) > available)
      throw new IllegalArgumentException("手机内存不足以处理所选尺寸，请选较低尺寸；不会自动缩小");
  }
}
