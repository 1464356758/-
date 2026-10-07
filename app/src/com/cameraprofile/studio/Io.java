package com.cameraprofile.studio;

import java.io.*;
import java.nio.charset.StandardCharsets;
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

  /** Conservative credential-feature check; not a complete signature verifier. */
  public static void guardOtherFormat(File file) throws IOException {
    try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
      if (input.length() < 12) return;
      byte[] header = new byte[12];
      input.readFully(header);
      if (header[0] == (byte) 137 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
        long pos = 8;
        while (pos < input.length()) {
          if (input.length() - pos < 12) throw new IOException("PNG 数据截断");
          input.seek(pos);
          long length = input.readInt() & 0xffffffffL;
          byte[] type = new byte[4];
          input.readFully(type);
          String name = new String(type, StandardCharsets.US_ASCII);
          if (length > input.length() - pos - 12) throw new IOException("PNG 块长度越界");
          if ("caBX".equals(name)) throw new IOException("检测到 PNG 真实性凭证块，已保护原文件");
          pos += length + 12;
          if ("IEND".equals(name)) return;
        }
      } else if (header[0] == 'R'
          && header[1] == 'I'
          && header[2] == 'F'
          && header[3] == 'F'
          && header[8] == 'W'
          && header[9] == 'E'
          && header[10] == 'B'
          && header[11] == 'P') {
        long pos = 12;
        while (pos < input.length()) {
          if (input.length() - pos < 8) throw new IOException("WebP 数据截断");
          input.seek(pos);
          byte[] type = new byte[4];
          input.readFully(type);
          long length = Integer.reverseBytes(input.readInt()) & 0xffffffffL;
          if (length > input.length() - pos - 8) throw new IOException("WebP 块长度越界");
          if ("C2PA".equals(new String(type, StandardCharsets.US_ASCII)))
            throw new IOException("检测到 WebP 真实性凭证块，已保护原文件");
          pos += 8 + length + (length & 1);
        }
      } else if (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
        long pos = 0;
        int boxes = 0;
        byte[] c2pa = {
          (byte) 0xd8,
          (byte) 0xfe,
          (byte) 0xc3,
          (byte) 0xd6,
          0x1b,
          0x0e,
          0x48,
          0x3c,
          (byte) 0x92,
          (byte) 0x97,
          0x58,
          0x28,
          (byte) 0x87,
          0x7e,
          (byte) 0xc4,
          (byte) 0x81
        };
        while (pos < input.length()) {
          if (input.length() - pos < 8 || ++boxes > 10000) throw new IOException("HEIF 容器结构无效");
          input.seek(pos);
          long length = input.readInt() & 0xffffffffL;
          byte[] type = new byte[4];
          input.readFully(type);
          int headerSize = 8;
          if (length == 1) {
            length = input.readLong();
            headerSize = 16;
          }
          if (length == 0) length = input.length() - pos;
          if (length < headerSize || length > input.length() - pos)
            throw new IOException("HEIF 容器长度越界");
          String name = new String(type, StandardCharsets.US_ASCII);
          if ("uuid".equals(name) && length >= headerSize + 16) {
            byte[] uuid = new byte[16];
            input.readFully(uuid);
            if (java.util.Arrays.equals(uuid, c2pa))
              throw new IOException("检测到 HEIF 真实性凭证块，已保护原文件");
          }
          if ("jumb".equals(name)) throw new IOException("检测到 JUMBF 凭证结构，已保护原文件");
          pos += length;
        }
      } else {
        // Unknown system-decodable formats: inspect a bounded header only.
        input.seek(0);
        byte[] bytes = new byte[(int) Math.min(65536, input.length())];
        input.readFully(bytes);
        String text = new String(bytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        if (text.contains("c2pa") || text.contains("contentcredentials"))
          throw new IOException("检测到真实性凭证特征，已保护原文件");
      }
    }
  }
}
