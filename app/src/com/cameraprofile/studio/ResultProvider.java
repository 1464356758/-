package com.cameraprofile.studio;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only, per-URI grants for app-owned verified images. No path traversal. */
public final class ResultProvider extends ContentProvider {
  public static final String AUTHORITY = "com.cameraprofile.studio.results";

  public static Uri uri(String id) {
    return Uri.parse("content://" + AUTHORITY + "/photo/" + id);
  }

  public boolean onCreate() {
    return true;
  }

  private TaskStore.Job job(Uri uri) throws FileNotFoundException {
    if (!AUTHORITY.equals(uri.getAuthority())
        || uri.getPathSegments().size() != 2
        || !"photo".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException("文件地址无效");
    try {
      TaskStore.Job job = TaskStore.get(getContext()).find(uri.getLastPathSegment());
      if (job == null || !TaskStore.SUCCESS.equals(job.state))
        throw new FileNotFoundException("文件不可分享");
      return job;
    } catch (Exception e) {
      throw new FileNotFoundException("已验证文件不可用");
    }
  }

  public String getType(Uri uri) {
    return "image/jpeg";
  }

  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      TaskStore.Job job = job(uri);
      String[] columns =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor cursor = new MatrixCursor(columns);
      Object[] row = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = job.report.optString("file");
        else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = job.bytes;
      }
      cursor.addRow(row);
      return cursor;
    } catch (Exception unavailable) {
      return null;
    }
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("只允许读取");
    TaskStore.Job job = job(uri);
    try {
      return ParcelFileDescriptor.open(
          TaskStore.get(getContext()).result(job.id), ParcelFileDescriptor.MODE_READ_ONLY);
    } catch (Exception unavailable) {
      throw new FileNotFoundException("文件不存在");
    }
  }

  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException("只读");
  }

  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException("只读");
  }

  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException("只读");
  }
}
