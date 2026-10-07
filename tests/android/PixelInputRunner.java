package com.cameraprofile.studio.tests;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.widget.*;
import com.cameraprofile.studio.*;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.zip.CRC32;
import org.json.*;

/** Real Android decoder/export regression for arbitrary metadata and original dimensions. */
public final class PixelInputRunner extends NativeChecks {
  private ProfileRepository profiles;
  private final JSONArray outputRecords = new JSONArray();

  private byte[] image(Bitmap.CompressFormat format) throws Exception {
    Bitmap b = Bitmap.createBitmap(65, 49, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(b);
    c.drawColor(Color.rgb(180, 45, 30));
    Paint p = new Paint(); p.setColor(Color.rgb(30, 90, 190));
    c.drawRect(32, 0, 65, 49, p);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!b.compress(format, 100, out)) throw new IOException("fixture encode");
    b.recycle(); return out.toByteArray();
  }

  private byte[] pngCredential(byte[] source) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(source, 0, 33); // PNG signature and complete IHDR.
    byte[] kind = "caBX".getBytes("US-ASCII");
    byte[] data = "synthetic-test-only-c2pa".getBytes("US-ASCII");
    DataOutputStream chunk = new DataOutputStream(out);
    chunk.writeInt(data.length); chunk.write(kind); chunk.write(data);
    CRC32 crc = new CRC32(); crc.update(kind); crc.update(data); chunk.writeInt((int)crc.getValue());
    out.write(source, 33, source.length - 33); return out.toByteArray();
  }

  private byte[] jpegCredential(byte[] source) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(source, 0, 2);
    out.write(new byte[] {(byte)255, (byte)235, 0, 6, 'c', '2', 'p', 'a'});
    out.write(source, 2, source.length - 2); return out.toByteArray();
  }

  private byte[] webpCredential(byte[] source) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(source, 0, 12);
    out.write(new byte[] {'C','2','P','A',4,0,0,0,'t','e','s','t'});
    out.write(source, 12, source.length - 12);
    byte[] result = out.toByteArray(); int length = result.length - 8;
    for (int i = 0; i < 4; i++) result[4+i] = (byte)(length >>> (8*i));
    return result;
  }

  private void stage(String name, byte[] bytes) throws Exception {
    try (OutputStream out = context.getContentResolver().openOutputStream(FixtureProvider.uri("export/"+name),"wt")) {
      out.write(bytes);
    }
  }

  private ExportSettings settings() {
    ExportSettings s = new ExportSettings(); s.originalSize = true;
    s.detail = false; s.timeMode = "none"; return s;
  }

  private TaskStore.Job process(String name, byte[] bytes, ExportSettings s) throws Exception {
    stage(name, bytes);
    Uri uri = FixtureProvider.uri("source/"+name);
    String before = Io.hash(context.getContentResolver().openInputStream(uri));
    store.enqueue(Collections.singletonList(uri), Collections.singletonList(name), s);
    TaskStore.Job job = store.next();
    new PhotoEngine(context, profiles, store).process(job, () -> false, (phase,p) -> store.progress(job.id,phase,p));
    TaskStore.Job done = store.find(job.id);
    ok(TaskStore.SUCCESS.equals(done.state)
        && before.equals(Io.hash(context.getContentResolver().openInputStream(uri))),
        name+" processed; original unchanged");
    Bitmap output = BitmapFactory.decodeFile(store.result(done.id).getAbsolutePath());
    ok(output != null && output.getWidth() == done.report.getInt("width")
        && output.getHeight() == done.report.getInt("height"), name+" output independently decodes");
    output.recycle();
    File saved = new File(context.getExternalFilesDir(null), "pixel22-"+done.id+".jpg");
    try (InputStream in = new FileInputStream(store.result(done.id)); OutputStream out = new FileOutputStream(saved)) {
      Io.copy(in,out,1000000,()->false);
    }
    outputRecords.put(new JSONObject().put("file",saved.getName()).put("input",name)
        .put("width",done.report.getInt("width")).put("height",done.report.getInt("height"))
        .put("sha256",done.sha).put("original_size",done.report.getBoolean("original_size")));
    try (Writer out = new FileWriter(new File(context.getExternalFilesDir(null),"pixel22-outputs.json"))) {
      out.write(outputRecords.toString(2));
    }
    return done;
  }

  private void originalSize(TaskStore.Job job, int width, int height, String label) throws Exception {
    JSONObject m = job.report.getJSONObject("metadata");
    ok(job.report.getInt("width") == width && job.report.getInt("height") == height
        && m.getInt("Orientation") == 1 && job.report.getBoolean("original_size")
        && job.report.getString("fit").contains("不加边"), label);
  }

  protected void checks() throws Exception {
    grants(); store.clear(); profiles = new ProfileRepository(context);
    ok(!ExportSettings.parse(new JSONObject().put("resolution",0)).originalSize,
        "2.1 settings retain previous device-preset behavior");
    ExportSettings keep = settings();
    ok(ExportSettings.parse(keep.json()).originalSize, "original-size preference survives serialization");
    byte[] jpeg = image(Bitmap.CompressFormat.JPEG);
    byte[] png = pngCredential(image(Bitmap.CompressFormat.PNG));
    TaskStore.Job disguised = process("credential22.jpg", png, keep);
    originalSize(disguised,65,49,"PNG caBX with jpg filename keeps exact dimensions");
    byte[] saved = Io.read(new FileInputStream(store.result(disguised.id)),1000000);
    ok(!JpegEngine.credentials(saved)
        && !new String(saved,"ISO-8859-1").contains("synthetic-test-only-c2pa"),
        "PNG ancillary credential is not carried into the rebuilt JPEG");
    TaskStore.Job webp = process("credential22.webp", webpCredential(image(Bitmap.CompressFormat.WEBP)), keep);
    originalSize(webp,65,49,"WebP C2PA chunk accepted at original dimensions");
    TaskStore.Job jpg = process("credential22.png", jpegCredential(jpeg), keep);
    originalSize(jpg,65,49,"JPEG APP11 accepted with arbitrary filename");
    ExportSettings fast = settings(); fast.mode = "metadata";
    TaskStore.Job fastJpg = process("credential22.png",jpegCredential(jpeg),fast);
    ok(!JpegEngine.credentials(Io.read(new FileInputStream(store.result(fastJpg.id)),1000000)),
        "fast JPEG accepts APP11 and omits old credential");
    TaskStore.Job fallback = process("credential22.jpg",png,fast);
    originalSize(fallback,65,49,"fast non-JPEG automatically rebuilds at original dimensions");
    for (int orientation = 1; orientation <= 8; orientation++) {
      byte[] source = JpegEngine.rebuild(jpeg,JpegEngine.exif("OLD","OLD",orientation,65,49,null,null,null,null));
      TaskStore.Job oriented = process("ori"+orientation+".jpg",source,keep);
      originalSize(oriented,orientation >= 5 ? 49 : 65,orientation >= 5 ? 65 : 49,
          "original-size EXIF orientation "+orientation);
    }
    ExportSettings ai = settings(); ai.ai = true;
    TaskStore.Job predicted = process("credential22.jpg",png,ai);
    originalSize(predicted,65,49,"AI reconstruction keeps original dimensions");
    ok(predicted.report.getBoolean("ai_applied"),"original-size AI runs real model inference");
    byte[] gif = Io.read(getContext().getAssets().open("first22.gif"),1000000);
    TaskStore.Job first = process("first22.gif",gif,keep);
    originalSize(first,65,49,"GIF first frame accepted at original dimensions");
    ok("FIRST".equals(first.report.getString("frame")),"GIF frame behavior recorded");
    // Each job freezes its own input dimensions even within one batch.
    byte[] portrait = JpegEngine.rebuild(jpeg,JpegEngine.exif("OLD","OLD",6,65,49,null,null,null,null));
    stage("ori1.jpg",jpeg); stage("ori6.jpg",portrait);
    store.enqueue(Arrays.asList(FixtureProvider.uri("source/ori1.jpg"),FixtureProvider.uri("source/ori6.jpg")),
        Arrays.asList("ori1.jpg","ori6.jpg"),keep);
    TaskStore.Job a = store.next(); new PhotoEngine(context,profiles,store).process(a,()->false,(s,p)->{});
    TaskStore.Job b = store.next(); new PhotoEngine(context,profiles,store).process(b,()->false,(s,p)->{});
    originalSize(store.find(a.id),65,49,"mixed batch landscape dimensions");
    originalSize(store.find(b.id),49,65,"mixed batch portrait dimensions");
    // Select the added option through the actual Android dialog.
    MainActivity activity = open("credential22.jpg");
    set(activity,"settings",new ExportSettings()); set(activity,"page","editor"); redraw(activity);
    Method picker = MainActivity.class.getDeclaredMethod("resolutionPicker"); picker.setAccessible(true);
    runOnMainSync(()->{try{picker.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
    await(()->text("保持原图尺寸"),15000,"original-size choice visible"); tap("保持原图尺寸");
    waitForIdleSync();
    ok(((ExportSettings)field(activity,"settings")).originalSize,"actual UI original-size option selects preference");
    Method select = MainActivity.class.getDeclaredMethod("selectProfile",String.class);select.setAccessible(true);
    runOnMainSync(()->{try{select.invoke(activity,"leicaq343");}catch(Exception e){throw new RuntimeException(e);}});
    ok(((ExportSettings)field(activity,"settings")).originalSize,"changing simulated device preserves original-size choice");
    redraw(activity); snapshot("original-size-22.png");
    runOnMainSync(()->activity.finish()); store.clear();
  }
}
