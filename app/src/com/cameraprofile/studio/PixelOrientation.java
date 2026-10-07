package com.cameraprofile.studio;

/** EXIF orientation transforms source image edges to destination image edges. */
public final class PixelOrientation {
  public static float[] matrix(int o, int w, int h) {
    switch (JpegEngine.normalizeOrientation(o)) {
      case 2:
        return new float[] {-1, 0, w, 0, 1, 0, 0, 0, 1};
      case 3:
        return new float[] {-1, 0, w, 0, -1, h, 0, 0, 1};
      case 4:
        return new float[] {1, 0, 0, 0, -1, h, 0, 0, 1};
      case 5:
        return new float[] {0, 1, 0, 1, 0, 0, 0, 0, 1};
      case 6:
        return new float[] {0, -1, h, 1, 0, 0, 0, 0, 1};
      case 7:
        return new float[] {0, -1, h, -1, 0, w, 0, 0, 1};
      case 8:
        return new float[] {0, 1, 0, -1, 0, w, 0, 0, 1};
      default:
        return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    }
  }
}
