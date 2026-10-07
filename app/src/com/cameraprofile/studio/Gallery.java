package com.cameraprofile.studio;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import org.json.*;

/** Publish only after copying and reading back the complete file. */
public final class Gallery {
  public static Uri save(
      Context context,
      TaskStore.Job job,
      File verified,
      JSONObject report,
      TaskStore store,
      PixelReconstructor.Cancel cancel)
      throws Exception {
    ContentResolver resolver = context.getContentResolver();
    Uri destination = null;
    boolean published = false;
    String sha = JpegEngine.hash(verified);
    try {
      if (!job.gallery.isEmpty()) {
        Uri previous = Uri.parse(job.gallery);
        try {
          if (sha.equals(Io.hash(resolver.openInputStream(previous)))) {
            ContentValues finish = new ContentValues();
            finish.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(previous, finish, null, null) == 0)
              throw new IOException("相册文件不可用");
            return previous;
          }
        } catch (Exception unavailable) {
        }
        try {
          if (pendingOwned(context, previous)) resolver.delete(previous, null, null);
        } catch (Exception unavailable) {
        }
      }
      ContentValues values = new ContentValues();
      values.put(MediaStore.Images.Media.DISPLAY_NAME, report.getString("file"));
      values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraProfile");
      values.put(MediaStore.Images.Media.IS_PENDING, 1);
      values.put(MediaStore.Images.Media.WIDTH, report.getInt("width"));
      values.put(MediaStore.Images.Media.HEIGHT, report.getInt("height"));
      // Recover a pending row if the process died between insert and journal write.
      try (Cursor rows =
          resolver.query(
              MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
              new String[] {MediaStore.Images.Media._ID},
              MediaStore.Images.Media.DISPLAY_NAME
                  + "=? AND "
                  + MediaStore.Images.Media.IS_PENDING
                  + "=1 AND "
                  + MediaStore.Images.Media.OWNER_PACKAGE_NAME
                  + "=?",
              new String[] {report.getString("file"), context.getPackageName()},
              null)) {
        if (rows != null && rows.moveToFirst())
          destination =
              ContentUris.withAppendedId(
                  MediaStore.Images.Media.EXTERNAL_CONTENT_URI, rows.getLong(0));
      } catch (Exception unsupported) {
      }
      if (destination == null)
        destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
      if (destination == null) throw new IOException("无法创建相册新副本");
      store.checkpoint(job.id, destination.toString(), sha, verified.length(), report);
      try (InputStream in = new FileInputStream(verified);
          OutputStream out = resolver.openOutputStream(destination, "w")) {
        Io.copy(in, out, 256L * 1024 * 1024, cancel);
      }
      if (cancel.requested()) throw new InterruptedIOException("保存已取消");
      if (!sha.equals(Io.hash(resolver.openInputStream(destination))))
        throw new IOException("相册回读 SHA-256 不一致");
      ContentValues done = new ContentValues();
      done.put(MediaStore.Images.Media.IS_PENDING, 0);
      if (resolver.update(destination, done, null, null) == 0) throw new IOException("相册发布失败");
      published = true;
      return destination;
    } finally {
      if (destination != null && !published) {
        try {
          resolver.delete(destination, null, null);
        } catch (Exception ignored) {
        }
      }
    }
  }

  private static boolean pendingOwned(Context context, Uri uri) {
    try (Cursor row =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {
                  MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.OWNER_PACKAGE_NAME
                },
                null,
                null,
                null)) {
      return row != null
          && row.moveToFirst()
          && row.getInt(0) == 1
          && context.getPackageName().equals(row.getString(1));
    } catch (Exception unavailable) {
      return false;
    }
  }
}
