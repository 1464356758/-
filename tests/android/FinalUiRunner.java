package com.cameraprofile.studio.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.cameraprofile.studio.*;
import java.io.*;

/** Final APK Activity → PROCESS → foreground service, plus native UI captures. */
public final class FinalUiRunner extends Instrumentation {
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  Button find(View view, String text) {
    if (view instanceof Button && text.equals(((Button) view).getText().toString()))
      return (Button) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        Button found = find(((ViewGroup) view).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  void snapshot(Context context, String name) throws Exception {
    // Software-emulated Android sometimes displays a System UI ANR dialog.
    // Dismiss that infrastructure dialog only; an App ANR is not ignored.
    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
    if (root != null
        && !root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty())
      for (AccessibilityNodeInfo wait : root.findAccessibilityNodeInfosByText("Wait"))
        wait.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    SystemClock.sleep(500);
    android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
    if (image == null) throw new IOException("Screenshot unavailable");
    try (OutputStream out =
        new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
      image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      image.recycle();
    }
  }

  boolean hasImage(View view) {
    if (view instanceof ImageView) return ((ImageView) view).getDrawable() != null;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        if (hasImage(((ViewGroup) view).getChildAt(i))) return true;
    return false;
  }

  void awaitPreview(MainActivity activity) {
    long deadline = SystemClock.elapsedRealtime() + 30000;
    boolean[] ready = {false};
    while (SystemClock.elapsedRealtime() < deadline) {
      runOnMainSync(() -> ready[0] = hasImage(activity.getWindow().getDecorView()));
      if (ready[0]) return;
      SystemClock.sleep(200);
    }
    throw new AssertionError("Native photo preview never loaded");
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      Context context = getTargetContext();
      context.startActivity(
          new Intent()
              .setClassName(
                  "com.cameraprofile.studio.tests", "com.cameraprofile.studio.tests.GrantActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.elapsedRealtime() + 60000;
      while (context.checkUriPermission(
              FixtureProvider.uri("source/user.jpg"),
              android.os.Process.myPid(),
              android.os.Process.myUid(),
              Intent.FLAG_GRANT_READ_URI_PERMISSION)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        if (SystemClock.elapsedRealtime() > deadline)
          throw new IOException("Fixture grant timeout");
        SystemClock.sleep(300);
      }
      TaskStore store = TaskStore.get(context);
      TaskStore.Job large = null;
      for (TaskStore.Job job : store.snapshot())
        if (job.report != null
            && job.report.optInt("width") == 4284
            && TaskStore.SUCCESS.equals(job.state)) large = job;
      if (large == null) throw new AssertionError("24MP native result missing");
      try (OutputStream out =
          context
              .getContentResolver()
              .openOutputStream(FixtureProvider.uri("export/unreadable.jpg"), "wt")) {
        out.write("ORIGINAL".getBytes("US-ASCII"));
      }
      String before =
          Io.hash(
              context
                  .getContentResolver()
                  .openInputStream(FixtureProvider.uri("source/unreadable.jpg")));
      try {
        Exports.photo(context, store, large, FixtureProvider.uri("export/unreadable.jpg"));
        throw new AssertionError("Unreadable existing target was overwritten");
      } catch (IOException expected) {
      }
      if (!before.equals(
          Io.hash(
              context
                  .getContentResolver()
                  .openInputStream(FixtureProvider.uri("source/unreadable.jpg")))))
        throw new AssertionError("Existing target changed");
      try (OutputStream out =
          context
              .getContentResolver()
              .openOutputStream(FixtureProvider.uri("export/regression.jpg"), "wt")) {}
      Exports.photo(context, store, large, FixtureProvider.uri("export/regression.jpg"));
      try (OutputStream out =
          context
              .getContentResolver()
              .openOutputStream(FixtureProvider.uri("export/regression.zip"), "wt")) {}
      Exports.zip(
          context,
          store,
          java.util.Collections.singletonList(large),
          FixtureProvider.uri("export/regression.zip"));
      Intent open =
          new Intent(context, MainActivity.class)
              .setAction(Intent.ACTION_SEND)
              .setType("image/jpeg")
              .putExtra(Intent.EXTRA_STREAM, FixtureProvider.uri("source/user.jpg"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      MainActivity activity = (MainActivity) startActivitySync(open);
      java.lang.reflect.Field settingsField = MainActivity.class.getDeclaredField("settings"),
          page = MainActivity.class.getDeclaredField("page"),
          id = MainActivity.class.getDeclaredField("resultId");
      settingsField.setAccessible(true);
      page.setAccessible(true);
      id.setAccessible(true);
      ExportSettings settings = new ExportSettings();
      settings.timeMode = "none";
      settingsField.set(activity, settings);
      java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
      render.setAccessible(true);
      Runnable redraw =
          () -> {
            try {
              render.invoke(activity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          };
      runOnMainSync(redraw);
      waitForIdleSync();
      SystemClock.sleep(1000);
      awaitPreview(activity);
      snapshot(context, "editor-final.png");
      settings.mode = "metadata";
      runOnMainSync(
          () -> {
            redraw.run();
            Button process = find(activity.getWindow().getDecorView(), "处理并保存新副本");
            if (process == null) throw new AssertionError("PROCESS missing");
            process.performClick();
          });
      deadline = SystemClock.elapsedRealtime() + 120000;
      while (SystemClock.elapsedRealtime() < deadline) {
        boolean pending = ProcessingService.active;
        for (TaskStore.Job job : store.snapshot())
          if (TaskStore.RUNNING.equals(job.state) || TaskStore.QUEUED.equals(job.state))
            pending = true;
        if (!pending) break;
        SystemClock.sleep(250);
      }
      TaskStore.Job last = store.snapshot().get(store.snapshot().size() - 1);
      if (!TaskStore.SUCCESS.equals(last.state) || ProcessingService.active)
        throw new AssertionError("Final foreground service did not finish");
      page.set(activity, "results");
      id.set(activity, large.id);
      runOnMainSync(redraw);
      waitForIdleSync();
      SystemClock.sleep(1000);
      awaitPreview(activity);
      snapshot(context, "result-final.png");
      page.set(activity, "home");
      runOnMainSync(redraw);
      waitForIdleSync();
      snapshot(context, "home-final.png");
      result.putString(
          "report",
          "PASS final signed APK: imported URI → native PROCESS button → foreground service →"
              + " verified MediaStore result; service stops; 24MP result remains available;"
              + " editor/results/home rendered; unreadable existing target protected; direct 24MP"
              + " JPEG and ZIP exports verified");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("failure", error.toString());
      StringWriter trace = new StringWriter();
      error.printStackTrace(new PrintWriter(trace));
      result.putString("trace", trace.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
