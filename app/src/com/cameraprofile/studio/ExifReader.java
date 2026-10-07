package com.cameraprofile.studio;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Independent bounded TIFF reader for our exported files, including Android 10 offset-time tags.
 */
public final class ExifReader {
  private static final Map<Integer, String> TAGS = new HashMap<>();

  static {
    int[] ids = {
      270, 271, 272, 274, 305, 306, 315, 33432, 34853, 33434, 33437, 34850, 34855, 36864, 36867,
      36868, 36880, 36881, 36882, 37377, 37378, 37380, 37386, 37500, 37510, 40961, 40962, 40963,
      41987, 41989, 42036
    };
    String[] names = {
      "ImageDescription",
      "Make",
      "Model",
      "Orientation",
      "Software",
      "DateTime",
      "Artist",
      "Copyright",
      "GPSInfo",
      "ExposureTime",
      "FNumber",
      "ExposureProgram",
      "ISOSpeedRatings",
      "ExifVersion",
      "DateTimeOriginal",
      "DateTimeDigitized",
      "OffsetTime",
      "OffsetTimeOriginal",
      "OffsetTimeDigitized",
      "ShutterSpeedValue",
      "ApertureValue",
      "ExposureBiasValue",
      "FocalLength",
      "MakerNote",
      "UserComment",
      "ColorSpace",
      "PixelXDimension",
      "PixelYDimension",
      "WhiteBalance",
      "FocalLengthIn35mmFilm",
      "LensModel"
    };
    for (int i = 0; i < ids.length; i++) TAGS.put(ids[i], names[i]);
  }

  public static Map<String, Object> read(File file) throws IOException {
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      if (in.readUnsignedShort() != 0xffd8) throw new IOException("JPEG 文件无效");
      while (true) {
        if (in.readUnsignedByte() != 255) throw new IOException("JPEG 标记无效");
        int marker;
        do {
          marker = in.readUnsignedByte();
        } while (marker == 255);
        if (marker == 218 || marker == 217) throw new IOException("导出 JPEG 缺少 EXIF");
        int length = in.readUnsignedShort();
        if (length < 2) throw new IOException("JPEG 段长度无效");
        byte[] data = new byte[length - 2];
        in.readFully(data);
        if (marker == 225
            && data.length >= 14
            && data[0] == 'E'
            && data[1] == 'x'
            && data[2] == 'i'
            && data[3] == 'f'
            && data[4] == 0
            && data[5] == 0) {
          byte[] tiff = Arrays.copyOfRange(data, 6, data.length);
          ByteBuffer buffer = ByteBuffer.wrap(tiff);
          if (tiff[0] == 'I' && tiff[1] == 'I') buffer.order(ByteOrder.LITTLE_ENDIAN);
          else if (tiff[0] == 'M' && tiff[1] == 'M') buffer.order(ByteOrder.BIG_ENDIAN);
          else throw new IOException("TIFF 字节序无效");
          if ((buffer.getShort(2) & 65535) != 42) throw new IOException("TIFF 头无效");
          HashMap<String, Object> tags = new HashMap<>();
          Set<Integer> seen = new HashSet<>();
          readIfd(buffer, offset(buffer.getInt(4), tiff.length), tags, seen, 0);
          return tags;
        }
      }
    } catch (EOFException truncated) {
      throw new IOException("EXIF 文件截断", truncated);
    }
  }

  private static int offset(int value, int capacity) throws IOException {
    if (value < 0 || value > capacity - 2) throw new IOException("EXIF 偏移越界");
    return value;
  }

  private static void readIfd(
      ByteBuffer b, int pos, Map<String, Object> tags, Set<Integer> seen, int depth)
      throws IOException {
    if (depth > 1 || !seen.add(pos)) throw new IOException("EXIF IFD 循环");
    int count = b.getShort(pos) & 65535;
    if (count > 1000 || (long) pos + 2 + count * 12 + 4 > b.capacity())
      throw new IOException("EXIF IFD 越界");
    int next = b.getInt(pos + 2 + count * 12);
    if (next != 0) throw new IOException("导出文件包含旧缩略图 IFD");
    for (int i = 0; i < count; i++) {
      int row = pos + 2 + i * 12, id = b.getShort(row) & 65535, type = b.getShort(row + 2) & 65535;
      int n = b.getInt(row + 4);
      if (n < 0 || n > 65533) throw new IOException("EXIF 字段长度无效");
      int unit =
          type == 1 || type == 2 || type == 7
              ? 1
              : type == 3 ? 2 : type == 4 || type == 9 ? 4 : type == 5 || type == 10 ? 8 : 0;
      if (unit == 0) throw new IOException("EXIF 字段类型无效");
      long bytes = (long) n * unit;
      int at = bytes <= 4 ? row + 8 : offset(b.getInt(row + 8), b.capacity());
      if (bytes > b.capacity() - at) throw new IOException("EXIF 数据越界");
      if (id == 34665) {
        readIfd(b, offset(b.getInt(row + 8), b.capacity()), tags, seen, depth + 1);
        continue;
      }
      String name = TAGS.get(id);
      if (name == null) continue;
      Object value;
      if (type == 2) {
        byte[] text = new byte[n];
        for (int j = 0; j < n; j++) text[j] = b.get(at + j);
        int length = n;
        while (length > 0 && text[length - 1] == 0) length--;
        value = new String(text, 0, length, StandardCharsets.US_ASCII);
      } else if (type == 3 && n == 1) value = b.getShort(at) & 65535;
      else if (type == 4 && n == 1) value = b.getInt(at) & 0xffffffffL;
      else if ((type == 5 || type == 10) && n == 1) {
        long numerator = type == 5 ? b.getInt(at) & 0xffffffffL : b.getInt(at);
        long denominator = type == 5 ? b.getInt(at + 4) & 0xffffffffL : b.getInt(at + 4);
        if (denominator == 0) throw new IOException("EXIF 有零分母");
        value = (double) numerator / denominator;
      } else {
        byte[] data = new byte[(int) bytes];
        for (int j = 0; j < data.length; j++) data[j] = b.get(at + j);
        value = data;
      }
      if (tags.put(name, value) != null) throw new IOException("EXIF 字段重复：" + name);
    }
  }
}
