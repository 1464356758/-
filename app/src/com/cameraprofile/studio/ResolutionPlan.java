package com.cameraprofile.studio;

/** Fit to a selected output canvas without stretching or cropping. */
public final class ResolutionPlan {
  public final int width, height, contentWidth, contentHeight, left, top;

  public ResolutionPlan(int sw, int sh, int landscapeWidth, int landscapeHeight) {
    if (sw < 1 || sh < 1 || landscapeWidth < 1 || landscapeHeight < 1)
      throw new IllegalArgumentException("invalid dimensions");
    width = sw < sh ? landscapeHeight : landscapeWidth;
    height = sw < sh ? landscapeWidth : landscapeHeight;
    double scale = Math.min((double) width / sw, (double) height / sh);
    contentWidth = Math.max(1, Math.min(width, (int) Math.round(sw * scale)));
    contentHeight = Math.max(1, Math.min(height, (int) Math.round(sh * scale)));
    left = (width - contentWidth) / 2;
    top = (height - contentHeight) / 2;
  }

  public boolean padded() {
    return contentWidth != width || contentHeight != height;
  }
}
