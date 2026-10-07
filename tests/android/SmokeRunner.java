package com.cameraprofile.studio.tests;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.cameraprofile.studio.*;
import java.io.*;
import java.util.*;
import org.json.*;

public final class SmokeRunner extends Instrumentation {
  final StringBuilder log = new StringBuilder();
  Context context;
  TaskStore store;
  ProfileRepository full, small;

  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  void ok(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    log.append("PASS ").append(name).append('\n');
    Bundle update = new Bundle();
    update.putString("checkpoint", name);
    sendStatus(0, update);
  }

  File fixture(String id) throws Exception {
    File f = new File(context.getCacheDir(), "runtime-fixtures/source/" + id);
    f.getParentFile().mkdirs();
    return f;
  }

  File export(String id) throws Exception {
    try (OutputStream out =
        context.getContentResolver().openOutputStream(FixtureProvider.uri("export/" + id), "wt")) {}
    return new File(context.getCacheDir(), id);
  }

  void bitmap(String name, Bitmap.CompressFormat format, boolean transparent) throws Exception {
    Bitmap b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
    for (int y = 0; y < 48; y++)
      for (int x = 0; x < 64; x++)
        b.setPixel(
            x,
            y,
            (transparent && x < 12 ? 0 : 0xff000000)
                | ((x * 3) << 16)
                | ((y * 4) << 8)
                | ((x + y) * 2));
    try (OutputStream out = new FileOutputStream(fixture(name))) {
      b.compress(format, 100, out);
    }
    b.recycle();
  }

  ExportSettings settings() {
    ExportSettings s = new ExportSettings();
    s.timeMode = "custom";
    s.customDate = "2026-10-06 20:00:00";
    s.customZone = "+08:00";
    s.detail = false;
    return s;
  }

  void stage(String name) throws Exception {
    try (InputStream in = new FileInputStream(fixture(name));
        OutputStream out =
            context
                .getContentResolver()
                .openOutputStream(FixtureProvider.uri("export/" + name), "wt")) {
      Io.copy(in, out, 200000000, () -> false);
    }
  }

  TaskStore.Job run(String name, ExportSettings settings, ProfileRepository repository)
      throws Exception {
    stage(name);
    store.enqueue(
        Collections.singletonList(FixtureProvider.uri("source/" + name)),
        Collections.singletonList(name),
        settings);
    TaskStore.Job job = store.next();
    new PhotoEngine(context, repository, store)
        .process(job, () -> false, (stage, p) -> store.progress(job.id, stage, p));
    return store.find(job.id);
  }

  Button find(View view, String text) {
    if (view instanceof Button && text.equals(((Button) view).getText().toString()))
      return (Button) view;
    if (view instanceof android.view.ViewGroup) {
      ViewGroup g = (ViewGroup) view;
      for (int i = 0; i < g.getChildCount(); i++) {
        Button found = find(g.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      context = getTargetContext();
      context.startActivity(
          new Intent()
              .setClassName(
                  "com.cameraprofile.studio.tests", "com.cameraprofile.studio.tests.GrantActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long grantsDeadline = SystemClock.elapsedRealtime() + 60000;
      while (context.checkUriPermission(
              FixtureProvider.uri("export/input.jpg"),
              android.os.Process.myPid(),
              android.os.Process.myUid(),
              Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        if (SystemClock.elapsedRealtime() > grantsDeadline)
          throw new AssertionError("Fixture document grants did not arrive");
        SystemClock.sleep(300);
      }
      store = TaskStore.get(context);
      full = new ProfileRepository(context);
      if (ProcessingService.active) throw new AssertionError("service already active");
      store.clear();
      JSONArray data =
          new JSONArray(
              new String(Io.read(context.getAssets().open("profiles.json"), 1000000), "UTF-8"));
      for (int i = 0; i < data.length(); i++)
        data.getJSONObject(i)
            .put(
                "output_modes",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("label", "Runtime fixture")
                            .put("width", 256)
                            .put("height", 192)
                            .put("basis", "test_fixture")));
      small = new ProfileRepository(data);
      bitmap("input.jpg", Bitmap.CompressFormat.JPEG, false);
      bitmap("alpha.png", Bitmap.CompressFormat.PNG, true);
      bitmap("input.webp", Bitmap.CompressFormat.WEBP, false);
      ExportSettings fast = settings();
      fast.mode = "metadata";
      File original = fixture("input.jpg");
      String originalHash = JpegEngine.hash(original);
      TaskStore.Job metadata = run("input.jpg", fast, full);
      ok(
          TaskStore.SUCCESS.equals(metadata.state),
          "JPEG metadata, ExifInterface, MediaStore publish and readback");
      ok(
          JpegFiles.codingHash(original, () -> false)
              .equals(JpegFiles.codingHash(store.result(metadata.id), () -> false)),
          "fast mode preserves compressed image and ICC");
      ok(originalHash.equals(JpegEngine.hash(original)), "input original is unchanged");
      ok(
          metadata
              .report
              .getJSONObject("metadata")
              .getString("OffsetTimeOriginal")
              .equals("+08:00"),
          "date and timezone separated");
      File malformed = fixture("orientation0.jpg");
      byte[] raw = Io.read(new FileInputStream(original), 1000000);
      byte[] exif = JpegEngine.exif("OLD", "OLD", 1, 64, 48, null, null, 2d, 24);
      // Mutate the actual TIFF orientation SHORT to zero.
      for (int p = 14; p < exif.length - 12; p++)
        if ((exif[p] & 255) == 18 && (exif[p + 1] & 255) == 1 && (exif[p + 2] & 255) == 3) {
          exif[p + 8] = 0;
          exif[p + 9] = 0;
          break;
        }
      try (OutputStream out = new FileOutputStream(malformed)) {
        out.write(JpegEngine.rebuild(raw, exif));
      }
      TaskStore.Job zero = run("orientation0.jpg", fast, full);
      ok(
          zero.report.getJSONObject("metadata").getInt("Orientation") == 1,
          "illegal original orientation safely normalized");
      for (int ori = 1; ori <= 8; ori++) {
        File f = fixture("ori" + ori + ".jpg");
        try (OutputStream out = new FileOutputStream(f)) {
          out.write(
              JpegEngine.rebuild(
                  raw, JpegEngine.exif("OLD", "OLD", ori, 64, 48, null, null, 2d, 24)));
        }
        TaskStore.Job job = run("ori" + ori + ".jpg", settings(), small);
        ok(
            job.report.getInt("width") == (ori >= 5 ? 192 : 256)
                && job.report.getInt("height") == (ori >= 5 ? 256 : 192),
            "native orientation transform " + ori);
      }
      TaskStore.Job alpha = run("alpha.png", settings(), small);
      Bitmap decoded = BitmapFactory.decodeFile(store.result(alpha.id).getAbsolutePath());
      int color = decoded.getPixel(0, 0);
      ok(
          Color.red(color) > 248 && Color.green(color) > 248 && Color.blue(color) > 248,
          "PNG transparency composites to white");
      decoded.recycle();
      TaskStore.Job webp = run("input.webp", settings(), small);
      ok(TaskStore.SUCCESS.equals(webp.state), "WebP decodes and exports JPEG");
      ExportSettings leica = settings();
      leica.profileId = "leicaq343";
      leica.mode = "metadata";
      leica.iso = 400;
      leica.exposure = 1 / 125.0;
      leica.bias = -0.3;
      leica.whiteBalance = 0;
      TaskStore.Job camera = run("input.jpg", leica, full);
      Map<String, Object> tags = ExifReader.read(store.result(camera.id));
      ok(
          Math.abs(((Number) tags.get("ExposureTime")).doubleValue() - 1 / 125.0) < 1e-12,
          "camera exposure and independent TIFF readback");
      try {
        ExportSettings invalid = settings();
        invalid.iso = 400;
        full.validate(invalid);
        throw new AssertionError();
      } catch (IllegalArgumentException expected) {
        ok(true, "unverified phone exposure rejected");
      }
      try {
        ExportSettings invalid = settings();
        invalid.customDate = "2026-02-30 20:00:00";
        full.validate(invalid);
        throw new AssertionError();
      } catch (java.time.DateTimeException expected) {
        ok(true, "invalid calendar date rejected");
      }
      ExportSettings ai = settings();
      ai.ai = true;
      ai.strength = 25;
      TaskStore.Job neural = run("input.jpg", ai, small);
      ok(
          neural.report.getBoolean("ai_applied"),
          "bundled ESRGAN native LiteRT inference and tiled rendering");
      ok(
          neural.report.getString("model_sha256").equals(AiReconstructor.MODEL_SHA),
          "AI weights SHA-256 verified");
      File photo = export("photo.jpg");
      Exports.photo(context, store, alpha, FixtureProvider.uri("export/photo.jpg"));
      ok(
          alpha.sha.equals(
              Io.hash(
                  context
                      .getContentResolver()
                      .openInputStream(FixtureProvider.uri("export/photo.jpg")))),
          "direct JPEG document export byte identity");
      File zip = export("photos.zip");
      Exports.zip(
          context,
          store,
          Arrays.asList(alpha, neural, camera),
          FixtureProvider.uri("export/photos.zip"));
      try (InputStream in =
          context.getContentResolver().openInputStream(FixtureProvider.uri("export/photos.zip"))) {
        ok(in.read() == 80, "ZIP document export entries, CRC and SHA-256 readback");
      }
      try {
        Exports.photo(context, store, alpha, FixtureProvider.uri("export/photo.jpg"));
        throw new AssertionError();
      } catch (IOException expected) {
        ok(
            alpha.sha.equals(
                Io.hash(
                    context
                        .getContentResolver()
                        .openInputStream(FixtureProvider.uri("export/photo.jpg")))),
            "existing document is never overwritten");
      }
      try {
        Exports.photo(context, store, alpha, FixtureProvider.uri("export/fail.jpg"));
        throw new AssertionError();
      } catch (IOException expected) {
        ok(true, "document write failure is reported");
      }
      File originalCopy = fixture("protected.jpg");
      try (OutputStream out = new FileOutputStream(originalCopy)) {
        out.write(raw, 0, 2);
        out.write(new byte[] {(byte) 255, (byte) 235, 0, 6, 'c', '2', 'p', 'a'});
        out.write(raw, 2, raw.length - 2);
      }
      TaskStore.Job credentialJob = run("protected.jpg", fast, full);
      ok(TaskStore.SUCCESS.equals(credentialJob.state)
          && !JpegEngine.credentials(Io.read(new FileInputStream(store.result(credentialJob.id)), 1000000)),
          "credential-bearing JPEG is accepted and old credential omitted");
      store.enqueue(
          Collections.singletonList(FixtureProvider.uri("source/input.jpg")),
          Collections.singletonList("input.jpg"),
          settings());
      TaskStore.Job cancelled = store.next();
      try {
        new PhotoEngine(context, small, store).process(cancelled, () -> true, (s, p) -> {});
        throw new AssertionError();
      } catch (java.io.InterruptedIOException expected) {
        store.fail(cancelled.id, TaskStore.CANCELLED, "cancelled");
        ok(true, "processing cancellation does not publish a partial image");
      }
      // Simulate interruption after complete private validation but before final job publication.
      store.fail(alpha.id, TaskStore.INTERRUPTED, "injected interruption");
      int countBefore = galleryCount();
      store.retry();
      TaskStore.Job resumed = store.next(); // alpha is the first interrupted job.
      ok(resumed.id.equals(alpha.id), "journal resumes the original interrupted job");
      new PhotoEngine(context, small, store).process(resumed, () -> false, (s, p) -> {});
      ok(galleryCount() == countBefore, "recovery reuses verified gallery copy without duplicate");
      // Leave failure/cancellation retries out of the UI service case.
      for (TaskStore.Job pending : store.snapshot())
        if (TaskStore.QUEUED.equals(pending.state))
          store.fail(pending.id, TaskStore.FAILED, "fixture finished");
      Uri published = ResultProvider.uri(metadata.id);
      ok(
          metadata.sha.equals(Io.hash(context.getContentResolver().openInputStream(published))),
          "read-only share provider returns exact verified bytes");
      try {
        context.getContentResolver().openOutputStream(published, "w");
        throw new AssertionError();
      } catch (FileNotFoundException expected) {
        ok(true, "share provider denies writes");
      }
      File exported = context.getExternalFilesDir(null);
      exported.mkdirs();
      for (TaskStore.Job j : Arrays.asList(metadata, alpha, neural, camera))
        try (InputStream in = new FileInputStream(store.result(j.id));
            OutputStream out = new FileOutputStream(new File(exported, j.id + ".jpg"))) {
          Io.copy(in, out, 200000000, () -> false);
        }
      try (InputStream in =
              context
                  .getContentResolver()
                  .openInputStream(FixtureProvider.uri("export/photos.zip"));
          OutputStream out = new FileOutputStream(new File(exported, "verified.zip"))) {
        Io.copy(in, out, 200000000, () -> false);
      }
      // Actual Activity import → user button → foreground service pipeline.
      Intent open =
          new Intent(context, MainActivity.class)
              .setAction(Intent.ACTION_SEND)
              .setType("image/jpeg")
              .putExtra(Intent.EXTRA_STREAM, FixtureProvider.uri("source/input.jpg"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      MainActivity activity = (MainActivity) startActivitySync(open);
      java.lang.reflect.Field field = MainActivity.class.getDeclaredField("settings");
      field.setAccessible(true);
      ExportSettings ui = (ExportSettings) field.get(activity);
      ui.mode = "metadata";
      java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
      render.setAccessible(true);
      runOnMainSync(
          () -> {
            try {
              render.invoke(activity);
              Button process = find(activity.getWindow().getDecorView(), "处理并保存新副本");
              if (process == null) throw new AssertionError("PROCESS button missing");
              process.performClick();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      int previous = store.snapshot().size();
      long deadline = SystemClock.elapsedRealtime() + 90000;
      while (SystemClock.elapsedRealtime() < deadline) {
        boolean pending = false;
        for (TaskStore.Job j : store.snapshot())
          if (TaskStore.RUNNING.equals(j.state) || TaskStore.QUEUED.equals(j.state)) pending = true;
        if (!pending && !ProcessingService.active) break;
        SystemClock.sleep(200);
      }
      TaskStore.Job last = store.snapshot().get(store.snapshot().size() - 1);
      ok(
          TaskStore.SUCCESS.equals(last.state),
          "Activity import, PROCESS, foreground service and notification lifecycle");
      result.putString("report", log.toString());
      finish(Activity.RESULT_OK, result);
    } catch (Throwable failure) {
      result.putString("report", log.toString());
      result.putString("failure", failure.toString());
      java.io.StringWriter trace = new java.io.StringWriter();
      failure.printStackTrace(new java.io.PrintWriter(trace));
      result.putString("trace", trace.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  int galleryCount() {
    try (android.database.Cursor rows =
        context
            .getContentResolver()
            .query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id"},
                "owner_package_name=?",
                new String[] {context.getPackageName()},
                null)) {
      return rows == null ? 0 : rows.getCount();
    }
  }
}
