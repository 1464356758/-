package com.cameraprofile.studio;

import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import java.io.*;
import java.util.*;
import org.json.*;

/** Verification describes bytes and requested parameters; it never certifies capture. */
public final class Verification {
  public static JSONObject expected(JpegEngine.Metadata m) throws JSONException {
    JSONObject tags = new JSONObject();
    tags.put("Make", m.make).put("Model", m.model).put("Software", JpegEngine.SOFTWARE);
    tags.put("ImageDescription", JpegEngine.DESCRIPTION).put("Orientation", m.orientation);
    tags.put("PixelXDimension", m.width)
        .put("PixelYDimension", m.height)
        .put("ColorSpace", m.colorSpace);
    if (m.aperture != null) {
      tags.put("FNumber", m.aperture);
      tags.put("ApertureValue", 2 * Math.log(m.aperture) / Math.log(2));
    }
    if (m.equivalent != null) tags.put("FocalLengthIn35mmFilm", m.equivalent);
    if (m.focal != null) tags.put("FocalLength", m.focal);
    if (m.lensModel != null) tags.put("LensModel", m.lensModel);
    if (m.date != null) {
      tags.put("DateTime", m.date).put("DateTimeOriginal", m.date).put("DateTimeDigitized", m.date);
      if (m.zone != null)
        tags.put("OffsetTime", m.zone)
            .put("OffsetTimeOriginal", m.zone)
            .put("OffsetTimeDigitized", m.zone);
    }
    if (m.iso != null) tags.put("ISOSpeedRatings", m.iso);
    if (m.exposureSeconds != null) {
      tags.put("ExposureTime", m.exposureSeconds);
      tags.put("ShutterSpeedValue", -Math.log(m.exposureSeconds) / Math.log(2));
    }
    if (m.iso != null && m.exposureSeconds != null) tags.put("ExposureProgram", 1);
    if (m.exposureBias != null) tags.put("ExposureBiasValue", m.exposureBias);
    if (m.whiteBalance != null) tags.put("WhiteBalance", m.whiteBalance);
    return tags;
  }

  public static void verify(File file, JSONObject tags) throws Exception {
    JpegFiles.preflight(file, () -> false);
    ExifInterface exif = new ExifInterface(file);
    Map<String, Object> actualTags = ExifReader.read(file);
    Iterator<String> keys = tags.keys();
    while (keys.hasNext()) {
      String tag = keys.next();
      Object expected = tags.get(tag);
      if (!actualTags.containsKey(tag)) throw new IOException("元数据回读缺少 " + tag);
      if (expected instanceof Number) {
        double number = ((Number) expected).doubleValue();
        Object value = actualTags.get(tag);
        double actual = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        double tolerance =
            "ExposureTime".equals(tag)
                ? Math.max(0.0000001, Math.abs(number) * 0.0001)
                : Math.max(0.0001, Math.abs(number) * 0.0001);
        if (!Double.isFinite(actual) || Math.abs(actual - number) > tolerance)
          throw new IOException("元数据回读不一致：" + tag);
      } else if (!expected.toString().equals(actualTags.get(tag)))
        throw new IOException("元数据回读不一致：" + tag);
    }
    for (String tag :
        new String[] {
          "GPSLatitude",
          "GPSLongitude",
          "GPSAltitude",
          "GPSProcessingMethod",
          "GPSDateStamp",
          "GPSTimeStamp",
          "GPSInfo",
          "Artist",
          "Copyright",
          "UserComment",
          "MakerNote"
        })
      if (actualTags.containsKey(tag) || exif.getAttribute(tag) != null)
        throw new IOException("隐私字段未清除：" + tag);
    if (exif.hasThumbnail()) throw new IOException("旧缩略图未清除");
    for (String tag :
        new String[] {"DateTimeOriginal", "DateTimeDigitized", "ISOSpeedRatings", "ExposureTime"})
      if (!tags.has(tag) && actualTags.containsKey(tag)) throw new IOException("发现未配置字段：" + tag);
    // Also independently confirm broadly supported tags with Android's decoder.
    for (String tag :
        new String[] {
          "Make",
          "Model",
          "Software",
          "Orientation",
          "FNumber",
          "PixelXDimension",
          "PixelYDimension"
        }) {
      if (!tags.has(tag)) continue;
      String actual = exif.getAttribute(tag);
      if (actual == null) throw new IOException("系统 EXIF 解析缺少 " + tag);
      if (tags.get(tag) instanceof Number) {
        double expected = tags.getDouble(tag);
        double numeric = exif.getAttributeDouble(tag, Double.NaN);
        if (!Double.isFinite(numeric) || Math.abs(numeric - expected) > 0.001)
          throw new IOException("系统 EXIF 回读不一致：" + tag);
      } else if (!tags.getString(tag).equals(actual)) throw new IOException("系统 EXIF 回读不一致：" + tag);
    }
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
    if (bounds.outWidth != tags.getInt("PixelXDimension")
        || bounds.outHeight != tags.getInt("PixelYDimension"))
      throw new IOException("JPEG 实际像素尺寸与 EXIF 不一致");
    BitmapFactory.Options decode = new BitmapFactory.Options();
    decode.inSampleSize = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / decode.inSampleSize > 512)
      decode.inSampleSize *= 2;
    android.graphics.Bitmap sample = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
    if (sample == null) throw new IOException("导出 JPEG 不能正常解码");
    sample.recycle();
  }
}
