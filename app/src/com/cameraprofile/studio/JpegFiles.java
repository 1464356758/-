package com.cameraprofile.studio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;

/** Streaming container processing: JPEG entropy never occupies a second image array. */
public final class JpegFiles {
  private static void walk(
      File source,
      OutputStream output,
      byte[] exif,
      MessageDigest coding,
      PixelReconstructor.Cancel cancel)
      throws Exception {
    try (PushbackInputStream in =
        new PushbackInputStream(new BufferedInputStream(new FileInputStream(source), 65536), 2)) {
      if (in.read() != 255 || in.read() != 216) throw new IOException("文件不是有效 JPEG");
      emit(output, coding, new byte[] {(byte) 255, (byte) 216});
      if (output != null && exif != null) {
        output.write(
            new byte[] {
              (byte) 255, (byte) 225, (byte) ((exif.length + 2) >> 8), (byte) (exif.length + 2)
            });
        output.write(exif);
      }
      boolean frame = false, scan = false;
      int segments = 0;
      while (true) {
        if (cancel.requested()) throw new InterruptedIOException("处理已取消");
        if (in.read() != 255) throw new IOException("JPEG 标记损坏或文件截断");
        int marker;
        do {
          marker = in.read();
        } while (marker == 255);
        if (marker == 217) {
          if (!frame || !scan) throw new IOException("JPEG 缺少图像数据");
          emit(output, coding, new byte[] {(byte) 255, (byte) 217});
          return;
        }
        if (marker <= 0 || marker == 216 || marker == 1 || marker >= 208 && marker <= 215)
          throw new IOException("JPEG 标记位置非法");
        int high = in.read(), low = in.read();
        if (high < 0 || low < 0) throw new IOException("JPEG 文件截断");
        int length = (high << 8) | low;
        if (length < 2) throw new IOException("JPEG 段长度非法");
        byte[] data = new byte[length - 2];
        int read = 0;
        while (read < data.length) {
          int count = in.read(data, read, data.length - read);
          if (count < 0) throw new IOException("JPEG 段截断");
          read += count;
        }
        if (++segments > 10000) throw new IOException("JPEG 段数量异常");
        boolean app = marker >= 224 && marker <= 239;
        String header = app ? new String(data, StandardCharsets.ISO_8859_1) : "";
        String lower = header.toLowerCase(Locale.ROOT);
        if (app
            && (marker == 235
                || lower.contains("c2pa")
                || lower.contains("contentcredentials")
                || lower.contains("content credentials")))
          throw new IOException("检测到真实性凭证，本版保护文件并停止处理");
        boolean keep =
            !app && marker != 254
                || marker == 226 && header.startsWith("ICC_PROFILE\0")
                || marker == 238 && header.startsWith("Adobe");
        if (keep) {
          emit(output, coding, new byte[] {(byte) 255, (byte) marker, (byte) high, (byte) low});
          emit(output, coding, data);
        }
        if (marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204)
          frame = true;
        if (marker == 218) {
          if (!frame) throw new IOException("JPEG 图像帧缺失");
          scan = true;
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(65536);
          while (true) {
            int n = in.read();
            if (n < 0) throw new IOException("JPEG 扫描数据截断");
            if (n == 255) {
              int m = in.read();
              while (m == 255) m = in.read();
              if (m < 0) throw new IOException("JPEG 扫描数据截断");
              if (m == 0 || m >= 208 && m <= 215) {
                buffer.write(255);
                buffer.write(m);
              } else {
                in.unread(m);
                in.unread(255);
                emit(output, coding, buffer.toByteArray());
                break;
              }
            } else buffer.write(n);
            if (buffer.size() >= 65536) {
              if (cancel.requested()) throw new InterruptedIOException("处理已取消");
              emit(output, coding, buffer.toByteArray());
              buffer.reset();
            }
          }
        }
      }
    }
  }

  private static void emit(OutputStream out, MessageDigest digest, byte[] bytes)
      throws IOException {
    if (out != null) out.write(bytes);
    if (digest != null) digest.update(bytes);
  }

  public static void preflight(File input, PixelReconstructor.Cancel cancel) throws Exception {
    walk(input, null, null, null, cancel);
  }

  public static String codingHash(File input, PixelReconstructor.Cancel cancel) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    walk(input, null, null, digest, cancel);
    StringBuilder text = new StringBuilder();
    for (byte b : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 255));
    return text.toString();
  }

  public static void rebuild(File input, File output, byte[] exif, PixelReconstructor.Cancel cancel)
      throws Exception {
    if (exif.length > 65533) throw new IOException("EXIF 超出容量");
    try (FileOutputStream file = new FileOutputStream(output);
        BufferedOutputStream out = new BufferedOutputStream(file, 65536)) {
      walk(input, out, exif, null, cancel);
      out.flush();
      file.getFD().sync();
    }
  }
}
