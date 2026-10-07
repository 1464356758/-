package com.cameraprofile.studio;

import android.content.*;
import android.net.Uri;
import android.provider.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Direct document exports always read back bytes before reporting success. */
public final class Exports {
  private static void requireEmpty(Context context, Uri target) throws Exception {
    try (InputStream in = context.getContentResolver().openInputStream(target)) {
      if (in == null) throw new IOException("无法读取导出目标，不能确认是新的空文件");
      if (in.read() != -1) throw new IOException("请选择新的空文件；App 不覆盖已有文件");
    } catch (FileNotFoundException unreadable) {
      throw new IOException("无法读取导出目标，不能确认是新的空文件；请选择可回读的文件位置", unreadable);
    }
  }

  private static void remove(Context context, Uri target) {
    try {
      DocumentsContract.deleteDocument(context.getContentResolver(), target);
    } catch (Exception ignored) {
    }
  }

  public static void photo(Context context, TaskStore store, TaskStore.Job job, Uri target)
      throws Exception {
    requireEmpty(context, target);
    File file = store.result(job.id);
    if (!TaskStore.SUCCESS.equals(job.state) || !job.sha.equals(JpegEngine.hash(file)))
      throw new IOException("已验证文件不可用");
    try {
      try (InputStream in = new FileInputStream(file);
          OutputStream out = context.getContentResolver().openOutputStream(target, "w")) {
        Io.copy(in, out, 256L * 1024 * 1024, () -> false);
      }
      if (!job.sha.equals(Io.hash(context.getContentResolver().openInputStream(target))))
        throw new IOException("导出文件回读 SHA-256 不一致");
    } catch (Exception error) {
      remove(context, target);
      throw error;
    }
  }

  public static void zip(Context context, TaskStore store, List<TaskStore.Job> jobs, Uri target)
      throws Exception {
    if (jobs.isEmpty()) throw new IOException("没有已验证照片");
    requireEmpty(context, target);
    LinkedHashMap<String, String> expected = new LinkedHashMap<>();
    JSONArray reports = new JSONArray();
    for (TaskStore.Job job : jobs) {
      File file = store.result(job.id);
      if (!TaskStore.SUCCESS.equals(job.state) || !job.sha.equals(JpegEngine.hash(file)))
        throw new IOException("本地照片已经变化，请重新处理");
      String name = job.report.getString("file");
      expected.put(name, job.sha);
      reports.put(job.report);
    }
    byte[] manifest =
        new JSONObject()
            .put("schema_version", 2)
            .put("app_version", "2.2")
            .put("capture_provenance", "SIMULATION")
            .put("files", reports)
            .toString(2)
            .getBytes(StandardCharsets.UTF_8);
    expected.put("verification.json", JpegEngine.hash(manifest));
    try {
      OutputStream document = context.getContentResolver().openOutputStream(target, "w");
      if (document == null) throw new IOException("无法创建 ZIP");
      try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(document, 65536))) {
        zip.setLevel(0); // JPEG is already compressed; avoid wasting device CPU.
        for (TaskStore.Job job : jobs) {
          zip.putNextEntry(new ZipEntry(job.report.getString("file")));
          try (InputStream in = new FileInputStream(store.result(job.id))) {
            Io.copy(in, zip, 256L * 1024 * 1024, () -> false);
          }
          zip.closeEntry();
        }
        zip.putNextEntry(new ZipEntry("verification.json"));
        zip.write(manifest);
        zip.closeEntry();
      }
      HashSet<String> seen = new HashSet<>();
      try (ZipInputStream zip =
          new ZipInputStream(context.getContentResolver().openInputStream(target))) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          String name = entry.getName();
          if (!expected.containsKey(name) || !seen.add(name)) throw new IOException("ZIP 文件清单不一致");
          java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
          byte[] buffer = new byte[65536];
          int n;
          long total = 0;
          while ((n = zip.read(buffer)) != -1) {
            total += n;
            if (total > 256L * 1024 * 1024) throw new IOException("ZIP 条目超出容量");
            digest.update(buffer, 0, n);
          }
          StringBuilder hash = new StringBuilder();
          for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
          if (!hash.toString().equals(expected.get(name))) throw new IOException("ZIP 回读哈希不一致");
          zip.closeEntry(); // ZipInputStream also verifies CRC.
        }
      }
      if (seen.size() != expected.size()) throw new IOException("ZIP 缺少照片或报告");
    } catch (Exception error) {
      remove(context, target);
      throw error;
    }
  }
}
