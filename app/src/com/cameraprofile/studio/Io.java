package com.cameraprofile.studio;

import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

public final class Io {
  public static long copy(
      InputStream input, OutputStream output, long limit, PixelReconstructor.Cancel cancel)
      throws IOException {
    if (input == null || output == null) throw new IOException("无法打开文件");
    byte[] buffer = new byte[65536];
    int n;
    long total = 0;
    while ((n = input.read(buffer)) != -1) {
      if (cancel.requested()) throw new InterruptedIOException("处理已取消");
      total += n;
      if (total > limit) throw new IOException("文件超过本版容量上限");
      output.write(buffer, 0, n);
    }
    return total;
  }

  public static byte[] read(InputStream input, int limit) throws IOException {
    try (InputStream in = input;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      copy(in, out, limit, () -> false);
      return out.toByteArray();
    }
  }

  public static String hash(InputStream input) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = input) {
      if (in == null) throw new IOException("无法回读保存文件");
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    StringBuilder text = new StringBuilder();
    for (byte n : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", n & 255));
    return text.toString();
  }

  public static boolean jpeg(File file) throws IOException {
    try (InputStream in = new FileInputStream(file)) {
      return in.read() == 255 && in.read() == 216;
    }
  }

}
