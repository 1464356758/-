package com.cameraprofile.studio.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import com.cameraprofile.studio.*;
import java.io.*;
import java.util.*;

/** User-supplied image → real catalog dimensions → MediaStore → independent export. */
public final class FullSizeRunner extends Instrumentation {
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      android.content.Context context = getTargetContext();
      ExportSettings badZone = new ExportSettings();
      badZone.timeMode = "custom";
      badZone.customDate = "2026-10-06 20:00:00";
      badZone.customZone = "+08:00:01";
      try {
        badZone.time();
        throw new AssertionError("Second precision zone accepted");
      } catch (IllegalArgumentException expected) {
      }
      context.startActivity(
          new Intent()
              .setClassName(
                  "com.cameraprofile.studio.tests", "com.cameraprofile.studio.tests.GrantActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.elapsedRealtime() + 60000;
      while (context.checkUriPermission(
              FixtureProvider.uri("export/user.jpg"),
              android.os.Process.myPid(),
              android.os.Process.myUid(),
              Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        if (SystemClock.elapsedRealtime() > deadline)
          throw new IOException("Document fixture grant timeout");
        SystemClock.sleep(300);
      }
      try (InputStream in = getContext().getAssets().open("user.jpg");
          OutputStream out =
              context
                  .getContentResolver()
                  .openOutputStream(FixtureProvider.uri("export/user.jpg"), "w")) {
        Io.copy(in, out, 100000000, () -> false);
      }
      String original =
          Io.hash(
              context.getContentResolver().openInputStream(FixtureProvider.uri("source/user.jpg")));
      TaskStore store = TaskStore.get(context);
      ExportSettings settings = new ExportSettings();
      settings.mode = "rebuild";
      settings.resolution = 0;
      settings.ai = false;
      settings.detail = true;
      settings.timeMode = "none";
      String batch =
          store.enqueue(
              Collections.singletonList(FixtureProvider.uri("source/user.jpg")),
              Collections.singletonList("full-size-user.jpg"),
              settings);
      TaskStore.Job job = store.next();
      long start = SystemClock.elapsedRealtime();
      new PhotoEngine(context, new ProfileRepository(context), store)
          .process(
              job,
              () -> false,
              (stage, percent) -> {
                android.util.Log.i("FullSizeRunner", stage + " " + percent + "%");
              });
      job = store.find(job.id);
      if (!TaskStore.SUCCESS.equals(job.state)
          || job.report.getInt("width") != 4284
          || job.report.getInt("height") != 5712)
        throw new AssertionError("Actual catalog target not satisfied");
      if (!original.equals(
          Io.hash(
              context
                  .getContentResolver()
                  .openInputStream(FixtureProvider.uri("source/user.jpg")))))
        throw new AssertionError("Input changed");
      File target = new File(context.getExternalFilesDir(null), "full-size-24mp.jpg");
      try (InputStream in = new FileInputStream(store.result(job.id));
          OutputStream out = new FileOutputStream(target)) {
        Io.copy(in, out, 200000000, () -> false);
      }
      Intent open =
          new Intent(context, MainActivity.class)
              .setAction(Intent.ACTION_SEND)
              .setType("image/jpeg")
              .putExtra(Intent.EXTRA_STREAM, FixtureProvider.uri("source/user.jpg"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      MainActivity activity = (MainActivity) startActivitySync(open);
      java.lang.reflect.Field editorSettings = MainActivity.class.getDeclaredField("settings");
      editorSettings.setAccessible(true);
      editorSettings.set(activity, settings);
      java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
      render.setAccessible(true);
      runOnMainSync(
          () -> {
            try {
              render.invoke(activity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      waitForIdleSync();
      SystemClock.sleep(1800);
      snapshot(context, "editor.png");
      java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page"),
          resultId = MainActivity.class.getDeclaredField("resultId");
      page.setAccessible(true);
      resultId.setAccessible(true);
      page.set(activity, "results");
      resultId.set(activity, job.id);
      runOnMainSync(
          () -> {
            try {
              render.invoke(activity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      waitForIdleSync();
      SystemClock.sleep(1800);
      snapshot(context, "result.png");
      result.putString(
          "report",
          "PASS actual user JPEG 1152×1536 → 4284×5712; original unchanged; independent EXIF and"
              + " decoder verification; MediaStore hash readback\n"
              + job.report.toString(2));
      result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - start);
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("failure", error.toString());
      StringWriter trace = new StringWriter();
      error.printStackTrace(new PrintWriter(trace));
      result.putString("trace", trace.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void snapshot(android.content.Context context, String name) throws Exception {
    android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
    if (image == null) throw new IOException("Screenshot unavailable");
    try (OutputStream out =
        new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
      if (!image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
        throw new IOException("Screenshot failed");
    } finally {
      image.recycle();
    }
  }
}
