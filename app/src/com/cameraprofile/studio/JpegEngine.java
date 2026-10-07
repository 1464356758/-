package com.cameraprofile.studio;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Strict JPEG rebuild, including ordinary metadata between progressive scans. */
public final class JpegEngine {
  public static final String SOFTWARE = "Camera Profile Studio 2.2";
  public static final String DESCRIPTION = "Device profile simulation; not proof of capture";

  public static int normalizeOrientation(int value) {
    return value >= 1 && value <= 8 ? value : 1;
  }

  public static final class Segment {
    public final int marker;
    public final byte[] raw;

    Segment(int marker, byte[] raw) {
      this.marker = marker;
      this.raw = raw;
    }
  }

  public static List<Segment> segments(byte[] bytes) throws IOException {
    if (bytes.length < 4 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216)
      throw new IOException("文件不是有效 JPEG");
    ArrayList<Segment> result = new ArrayList<>();
    int p = 2;
    boolean frame = false, scan = false;
    while (p < bytes.length) {
      int start = p;
      if ((bytes[p++] & 255) != 255) throw new IOException("JPEG 标记损坏");
      while (p < bytes.length && (bytes[p] & 255) == 255) p++;
      if (p == bytes.length) throw new IOException("JPEG 文件截断");
      int marker = bytes[p++] & 255;
      if (marker == 217) {
        if (!frame || !scan) throw new IOException("JPEG 缺少图像数据");
        result.add(new Segment(marker, new byte[] {(byte) 255, (byte) 217}));
        return result; // Trailing bytes are never copied.
      }
      if (marker == 0 || marker == 216 || marker == 1 || (marker >= 208 && marker <= 215))
        throw new IOException("JPEG 标记位置非法");
      if (p + 2 > bytes.length) throw new IOException("JPEG 文件截断");
      int length = ((bytes[p] & 255) << 8) | (bytes[p + 1] & 255);
      if (length < 2 || length > bytes.length - p) throw new IOException("JPEG 段长度非法");
      p += length;
      if (marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204)
        frame = true;
      if (marker == 218) {
        if (!frame) throw new IOException("JPEG 图像帧缺失");
        scan = true;
        while (p < bytes.length) {
          if ((bytes[p] & 255) != 255) {
            p++;
            continue;
          }
          int markerStart = p, q = p + 1;
          while (q < bytes.length && (bytes[q] & 255) == 255) q++;
          if (q == bytes.length) throw new IOException("JPEG 扫描数据截断");
          int m = bytes[q] & 255;
          if (m == 0 || (m >= 208 && m <= 215)) {
            p = q + 1;
            continue;
          }
          p = markerStart;
          break;
        }
      }
      result.add(new Segment(marker, Arrays.copyOfRange(bytes, start, p)));
      if (result.size() > 10000) throw new IOException("JPEG 段数量异常");
    }
    throw new IOException("JPEG 缺少结束标记");
  }

  private static boolean keep(Segment segment) {
    if (segment.marker < 224 || segment.marker > 239) return segment.marker != 254;
    String raw = new String(segment.raw, StandardCharsets.ISO_8859_1);
    return segment.marker == 226 && raw.contains("ICC_PROFILE\0")
        || segment.marker == 238 && raw.contains("Adobe");
  }

  public static boolean credentials(byte[] bytes) throws IOException {
    for (Segment segment : segments(bytes)) {
      if (segment.marker < 224 || segment.marker > 239) continue;
      String text = new String(segment.raw, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
      if (segment.marker == 235
          || text.contains("c2pa")
          || text.contains("contentcredentials")
          || text.contains("content credentials")) return true;
    }
    return false;
  }

  public static byte[] imagePayload(byte[] bytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Segment segment : segments(bytes)) if (keep(segment)) out.write(segment.raw);
    return out.toByteArray();
  }

  public static byte[] rebuild(byte[] jpeg, byte[] exif) throws IOException {
    if (exif.length > 65533) throw new IOException("EXIF 超出 JPEG 段容量");
    ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + exif.length);
    out.write(255);
    out.write(216);
    out.write(255);
    out.write(225);
    int length = exif.length + 2;
    out.write(length >> 8);
    out.write(length & 255);
    out.write(exif);
    for (Segment segment : segments(jpeg)) if (keep(segment)) out.write(segment.raw);
    return out.toByteArray();
  }

  /** Editable simulation values, never a camera capture assertion. */
  public static final class Metadata {
    public String make, model, date, zone, lensModel;
    public int orientation = 1, width, height, colorSpace = 1;
    public Double aperture, focal, exposureSeconds, exposureBias;
    public Integer equivalent, iso, whiteBalance;

    public void validate() {
      if (make == null || model == null || make.length() > 160 || model.length() > 160)
        throw new IllegalArgumentException("设备字段无效");
      if (orientation < 1 || orientation > 8 || width < 1 || height < 1)
        throw new IllegalArgumentException("方向或尺寸无效");
      if (aperture != null && (!Double.isFinite(aperture) || aperture < 1 || aperture > 128))
        throw new IllegalArgumentException("光圈无效");
      if (focal != null && (!Double.isFinite(focal) || focal <= 0 || focal > 10000))
        throw new IllegalArgumentException("焦距无效");
      if (iso != null && (iso < 1 || iso > 65535)) throw new IllegalArgumentException("ISO 无效");
      if (exposureSeconds != null
          && (!Double.isFinite(exposureSeconds)
              || exposureSeconds < 0.000001
              || exposureSeconds > 3600)) throw new IllegalArgumentException("快门无效");
      if (exposureBias != null && (!Double.isFinite(exposureBias) || Math.abs(exposureBias) > 5))
        throw new IllegalArgumentException("曝光补偿无效");
      if (whiteBalance != null && whiteBalance != 0 && whiteBalance != 1)
        throw new IllegalArgumentException("白平衡无效");
      if (equivalent != null && (equivalent < 1 || equivalent > 65535))
        throw new IllegalArgumentException("等效焦距无效");
      if (date != null && !date.matches("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}"))
        throw new IllegalArgumentException("日期格式无效");
      if (zone != null && !zone.matches("[+-](?:0\\d|1[0-4]):[0-5]\\d"))
        throw new IllegalArgumentException("时区格式无效");
    }
  }

  private static final class Tag {
    final int id, type, count;
    byte[] data;

    Tag(int id, int type, int count, byte[] data) {
      this.id = id;
      this.type = type;
      this.count = count;
      this.data = data;
    }
  }

  private static byte[] number(int n, int size) {
    ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    if (size == 2) b.putShort((short) n);
    else b.putInt(n);
    return b.array();
  }

  private static Tag ascii(int id, String text) {
    byte[] bytes = (text + "\0").getBytes(StandardCharsets.US_ASCII);
    return new Tag(id, 2, bytes.length, bytes);
  }

  private static Tag shortTag(int id, int n) {
    return new Tag(id, 3, 1, number(n, 2));
  }

  private static Tag longTag(int id, int n) {
    return new Tag(id, 4, 1, number(n, 4));
  }

  private static Tag rational(int id, double value, boolean signed) {
    int denominator = Math.abs(value) < 1 ? 1000000 : 10000;
    int numerator = (int) Math.round(value * denominator);
    if (value > 0 && value < 1 && Math.abs(value - 1.0 / Math.round(1.0 / value)) < 1e-12) {
      numerator = 1;
      denominator = (int) Math.round(1.0 / value);
    }
    int a = Math.abs(numerator), b = denominator;
    while (b != 0) {
      int next = a % b;
      a = b;
      b = next;
    }
    int gcd = Math.max(1, a);
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(numerator / gcd).putInt(denominator / gcd);
    return new Tag(id, signed ? 10 : 5, 1, buffer.array());
  }

  private static int tableSize(List<Tag> tags) {
    return 2 + 12 * tags.size() + 4;
  }

  private static void table(ByteBuffer buffer, int pos, List<Tag> tags, int[] extra) {
    tags.sort(Comparator.comparingInt(tag -> tag.id));
    buffer.position(pos);
    buffer.putShort((short) tags.size());
    for (Tag tag : tags) {
      buffer.putShort((short) tag.id).putShort((short) tag.type).putInt(tag.count);
      if (tag.data.length <= 4) {
        buffer.put(tag.data);
        for (int i = tag.data.length; i < 4; i++) buffer.put((byte) 0);
      } else {
        buffer.putInt(extra[0]);
        int save = buffer.position();
        buffer.position(extra[0]);
        buffer.put(tag.data);
        extra[0] += tag.data.length;
        if ((extra[0] & 1) != 0) extra[0]++;
        buffer.position(save);
      }
    }
    buffer.putInt(0);
  }

  public static byte[] exif(Metadata m) throws IOException {
    m.validate();
    List<Tag> primary = new ArrayList<>(), detail = new ArrayList<>();
    primary.add(ascii(271, m.make));
    primary.add(ascii(272, m.model));
    primary.add(shortTag(274, m.orientation));
    primary.add(ascii(305, SOFTWARE));
    primary.add(ascii(270, DESCRIPTION));
    if (m.date != null) {
      primary.add(ascii(306, m.date));
      detail.add(ascii(36867, m.date));
      detail.add(ascii(36868, m.date));
      if (m.zone != null) {
        detail.add(ascii(36880, m.zone));
        detail.add(ascii(36881, m.zone));
        detail.add(ascii(36882, m.zone));
      }
    }
    detail.add(new Tag(36864, 7, 4, "0232".getBytes(StandardCharsets.US_ASCII)));
    if (m.colorSpace == 1) detail.add(new Tag(37121, 7, 4, new byte[] {1, 2, 3, 0}));
    detail.add(longTag(40962, m.width));
    detail.add(longTag(40963, m.height));
    detail.add(shortTag(40961, m.colorSpace));
    if (m.aperture != null) {
      detail.add(rational(33437, m.aperture, false));
      detail.add(rational(37378, 2 * Math.log(m.aperture) / Math.log(2), false));
    }
    if (m.equivalent != null) detail.add(shortTag(41989, m.equivalent));
    if (m.focal != null) detail.add(rational(37386, m.focal, false));
    if (m.lensModel != null) detail.add(ascii(42036, m.lensModel));
    if (m.iso != null) detail.add(shortTag(34855, m.iso));
    if (m.exposureSeconds != null) {
      detail.add(rational(33434, m.exposureSeconds, false));
      detail.add(rational(37377, -Math.log(m.exposureSeconds) / Math.log(2), true));
    }
    if (m.iso != null && m.exposureSeconds != null) detail.add(shortTag(34850, 1));
    if (m.exposureBias != null) detail.add(rational(37380, m.exposureBias, true));
    if (m.whiteBalance != null) detail.add(shortTag(41987, m.whiteBalance));
    primary.add(longTag(34665, 0));
    int detailPos = 8 + tableSize(primary);
    for (Tag tag : primary) if (tag.id == 34665) tag.data = number(detailPos, 4);
    int[] extra = {detailPos + tableSize(detail)};
    ByteBuffer buffer = ByteBuffer.allocate(65533).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
    try {
      table(buffer, 8, primary, extra);
      table(buffer, detailPos, detail, extra);
    } catch (RuntimeException e) {
      throw new IOException("EXIF 字段超出容量", e);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
    out.write(buffer.array(), 0, extra[0]);
    return out.toByteArray();
  }

  public static byte[] exif(
      String make,
      String model,
      int orientation,
      int width,
      int height,
      String date,
      String zone,
      Double aperture,
      Integer equivalent)
      throws IOException {
    return exif(
        make, model, orientation, width, height, date, zone, aperture, equivalent, null, null);
  }

  public static byte[] exif(
      String make,
      String model,
      int orientation,
      int width,
      int height,
      String date,
      String zone,
      Double aperture,
      Integer equivalent,
      Double focal,
      String lens)
      throws IOException {
    Metadata m = new Metadata();
    m.make = make;
    m.model = model;
    m.orientation = orientation;
    m.width = width;
    m.height = height;
    m.date = date;
    m.zone = zone;
    m.aperture = aperture;
    m.equivalent = equivalent;
    m.focal = focal;
    m.lensModel = lens;
    return exif(m);
  }

  public static String hash(byte[] bytes) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  public static String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    return hex(digest.digest());
  }

  private static String hex(byte[] bytes) {
    char[] chars = new char[bytes.length * 2];
    String alphabet = "0123456789abcdef";
    for (int i = 0; i < bytes.length; i++) {
      int n = bytes[i] & 255;
      chars[i * 2] = alphabet.charAt(n >> 4);
      chars[i * 2 + 1] = alphabet.charAt(n & 15);
    }
    return new String(chars);
  }
}
