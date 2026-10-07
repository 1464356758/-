package com.cameraprofile.studio;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

/** Read input → reconstruct (optional) → rebuild → read back → publish a new copy. */
public final class PhotoEngine {
  public interface Progress {
    void update(String stage, int percent) throws Exception;
  }

  private final Context context;
  private final ProfileRepository profiles;
  private final TaskStore store;

  public PhotoEngine(Context context, ProfileRepository profiles, TaskStore store) {
    this.context = context;
    this.profiles = profiles;
    this.store = store;
  }

  public void process(TaskStore.Job job, PixelReconstructor.Cancel cancel, Progress progress)
      throws Exception {
    ExportSettings settings = job.settings;
    profiles.validate(settings);
    File destination = store.result(job.id);
    // A verified private copy survives interrupted gallery publication.
    if (job.report != null
        && !job.sha.isEmpty()
        && destination.exists()
        && job.sha.equals(JpegEngine.hash(destination))) {
      progress.update("恢复已验证文件", 94);
      Verification.verify(destination, job.report.getJSONObject("metadata"));
      Uri gallery = Gallery.save(context, job, destination, job.report, store, cancel);
      store.checkpoint(job.id, gallery.toString(), job.sha, destination.length(), job.report);
      store.complete(job.id);
      return;
    }
    File source = new File(context.getCacheDir(), job.id + ".input");
    File encoded = new File(context.getCacheDir(), job.id + ".encoded.jpg");
    File partial = new File(context.getCacheDir(), job.id + ".verified.jpg");
    Bitmap input = null, output = null;
    try {
      progress.update("读取照片", 1);
      if (context.getCacheDir().getUsableSpace() < 128L * 1024 * 1024)
        throw new IOException("剩余存储空间不足，请先释放至少 128MB");
      try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(job.input));
          FileOutputStream out = new FileOutputStream(source)) {
        Io.copy(in, out, 100L * 1024 * 1024, cancel);
        out.getFD().sync();
      }
      String inputSha = JpegEngine.hash(source);
      boolean jpeg = Io.jpeg(source);
      if (jpeg && "metadata".equals(settings.mode)) JpegFiles.preflight(source, cancel);
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
      if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new IOException("手机无法解码该图片格式");
      int orientation = 1;
      try {
        orientation =
            JpegEngine.normalizeOrientation(
                new ExifInterface(source).getAttributeInt("Orientation", 1));
      } catch (IOException unsupported) {
      }
      JSONObject profile = profiles.get(settings.profileId), lens = profiles.lens(settings);
      JpegEngine.Metadata metadata = new JpegEngine.Metadata();
      metadata.make = profile.getString("manufacturer");
      metadata.model = profile.getString("model");
      metadata.orientation = orientation;
      metadata.width = bounds.outWidth;
      metadata.height = bounds.outHeight;
      if (lens.has("aperture")) metadata.aperture = lens.getDouble("aperture");
      if (lens.has("equivalent")) metadata.equivalent = lens.getInt("equivalent");
      if (lens.has("focal_length")) metadata.focal = lens.getDouble("focal_length");
      metadata.lensModel = lens.optString("lens_model", null);
      metadata.iso = settings.iso;
      metadata.exposureSeconds = settings.exposure;
      metadata.exposureBias = settings.bias;
      metadata.whiteBalance = settings.whiteBalance;
      String[] stamp = settings.time();
      metadata.date = stamp[0];
      metadata.zone = stamp[1];
      boolean rebuilt = "rebuild".equals(settings.mode) || !jpeg, aiApplied = false;
      boolean keepSize = settings.originalSize || "metadata".equals(settings.mode);
      String fit = "保持原像素尺寸", algorithm = "JPEG 元数据重建；压缩图像数据保持一致";
      File codingSource = source;
      JSONObject target = null;
      if (!rebuilt) {
        metadata.colorSpace = 65535; // ICC retained; do not assert sRGB conversion.
      } else {
        int sw = orientation >= 5 ? bounds.outHeight : bounds.outWidth;
        int sh = orientation >= 5 ? bounds.outWidth : bounds.outHeight;
        target = keepSize
            ? new JSONObject().put("width", sw).put("height", sh)
                .put("basis", "original_dimensions").put("label", "保持原图尺寸")
            : profiles.resolution(settings);
        ResolutionPlan plan =
            keepSize ? ResolutionPlan.original(sw, sh)
                : new ResolutionPlan(sw, sh, target.getInt("width"), target.getInt("height"));
        boolean useAi = settings.ai && settings.strength > 0 && (keepSize || plan.contentWidth > sw);
        if (useAi && (long) sw * sh > 4000000L)
          throw new IOException("本版 AI 支持不超过 400 万像素的输入；可关闭 AI 使用普通重建");
        Runtime runtime = Runtime.getRuntime();
        long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE))
            .getMemoryInfo(memory);
        available = Math.min(available, memory.availMem / 2);
        MemoryBudget.require(sw, sh, plan.width, plan.height, available, useAi);
        if (context.getFilesDir().getUsableSpace()
            < (long) plan.width * plan.height * 4 + 64L * 1024 * 1024)
          throw new IOException("剩余存储空间不足以生成该尺寸");
        progress.update("解码并规范方向", 4);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (decoded == null) throw new IOException("图片解码失败；HEIC 支持取决于手机系统");
        try {
          input = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
          Canvas canvas = new Canvas(input);
          canvas.drawColor(Color.WHITE);
          Matrix transform = new Matrix();
          transform.setValues(
              PixelOrientation.matrix(orientation, bounds.outWidth, bounds.outHeight));
          canvas.drawBitmap(decoded, transform, new Paint());
        } finally {
          decoded.recycle();
        }
        output = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888);
        output.eraseColor(Color.WHITE);
        final Bitmap src = input, dst = output;
        PixelReconstructor.Source originalRows = (y, row) -> src.getPixels(row, 0, sw, 0, y, sw, 1);
        PixelReconstructor.Source resultRows =
            (y, row) ->
                dst.getPixels(
                    row, 0, plan.contentWidth, plan.left, y + plan.top, plan.contentWidth, 1);
        PixelReconstructor.Sink sink =
            (y, row) ->
                dst.setPixels(
                    row, 0, plan.contentWidth, plan.left, y + plan.top, plan.contentWidth, 1);
        int workers = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors()));
        PixelReconstructor.reconstruct(
            sw,
            sh,
            plan.contentWidth,
            plan.contentHeight,
            originalRows,
            sink,
            cancel,
            (done, total) ->
                progress.update(
                    "像素重建 " + done * 100 / total + "%", 5 + done * (useAi ? 40 : 75) / total),
            workers);
        algorithm = "Lanczos-3 线性光重建";
        if (useAi) {
          progress.update("加载离线 AI 模型", 46);
          try (AiReconstructor model = new AiReconstructor(context)) {
            progress.update("AI 分块推理 0/" + NeuralTiles.tileCount(sw, sh, model.inputSize()), 47);
            NeuralTiles.apply(
                sw,
                sh,
                plan.contentWidth,
                plan.contentHeight,
                originalRows,
                resultRows,
                sink,
                model,
                settings.strength,
                cancel,
                (done, total) ->
                    progress.update("AI 分块推理 " + done + "/" + total, 47 + done * 35 / total));
          }
          algorithm += " + ESRGAN 4× 细节预测（" + settings.strength + "%）";
          aiApplied = true;
        }
        if (settings.detail) {
          progress.update("保守细节增强", 83);
          PixelReconstructor.detail(
              plan.contentWidth, plan.contentHeight, resultRows, sink, cancel);
          algorithm += " + 轻度细节增强";
        }
        input.recycle();
        input = null;
        if (cancel.requested()) throw new InterruptedIOException("处理已取消");
        progress.update("编码 JPEG", 86);
        try (FileOutputStream stream = new FileOutputStream(encoded)) {
          if (!output.compress(Bitmap.CompressFormat.JPEG, settings.quality, stream))
            throw new IOException("JPEG 编码失败");
          stream.getFD().sync();
        }
        output.recycle();
        output = null;
        metadata.orientation = 1;
        metadata.width = plan.width;
        metadata.height = plan.height;
        metadata.colorSpace = 1;
        codingSource = encoded;
        fit = keepSize ? "保持原图尺寸；不缩放、不裁切、不加边"
            : plan.padded() ? "等比缩放并加白边；无裁切、无拉伸" : "等比例重建到目标尺寸";
      }
      if (cancel.requested()) throw new InterruptedIOException("处理已取消");
      progress.update("重建元数据", 90);
      String codingHash = JpegFiles.codingHash(codingSource, cancel);
      JpegFiles.rebuild(codingSource, partial, JpegEngine.exif(metadata), cancel);
      JSONObject tags = Verification.expected(metadata);
      progress.update("回读并验证文件", 92);
      Verification.verify(partial, tags);
      if (!codingHash.equals(JpegFiles.codingHash(partial, cancel)))
        throw new IOException("JPEG 图像编码数据校验失败");
      String sha = JpegEngine.hash(partial);
      if (destination.exists() && !destination.delete()) throw new IOException("无法更新本地新副本");
      if (!partial.renameTo(destination)) throw new IOException("无法保留已验证文件");
      String fileName =
          "CP_"
              + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(job.created))
              + "_"
              + job.id.substring(0, 8)
              + ".jpg";
      JSONObject report = new JSONObject();
      report.put("file", fileName).put("sha256", sha).put("bytes", destination.length());
      report
          .put("width", metadata.width)
          .put("height", metadata.height)
          .put("device", metadata.model);
      report.put("lens", lens.getString("name")).put("app_version", "2.2").put("format", "JPEG");
      report.put("algorithm", algorithm).put("mode", rebuilt ? "rebuild" : "metadata").put("fit", fit);
      report.put("requested_mode", settings.mode).put("original_size", keepSize);
      if ("image/gif".equals(bounds.outMimeType)) report.put("frame", "FIRST");
      report
          .put("quality", rebuilt ? settings.quality : JSONObject.NULL)
          .put("color", rebuilt ? "sRGB SDR" : "保留原 ICC");
      report.put("metadata", tags).put("gps", "REMOVED").put("readback", "PASSED");
      report
          .put("capture_provenance", "SIMULATION")
          .put("input_output_different", !inputSha.equals(sha));
      report.put("ai_requested", settings.ai).put("ai_applied", aiApplied);
      if (aiApplied)
        report.put("model_sha256", AiReconstructor.MODEL_SHA).put("ai_runtime", "LiteRT 1.4.2 CPU");
      report.put("profile_version", profile.optInt("version", 4));
      if (target != null) report.put("resolution_basis", target.optString("basis"));
      store.checkpoint(job.id, "", sha, destination.length(), report);
      progress.update("保存到相册并回读", 96);
      TaskStore.Job staged = store.find(job.id);
      Uri gallery = Gallery.save(context, staged, destination, report, store, cancel);
      store.checkpoint(job.id, gallery.toString(), sha, destination.length(), report);
      store.complete(job.id);
    } finally {
      if (input != null) input.recycle();
      if (output != null) output.recycle();
      source.delete();
      encoded.delete();
      partial.delete();
    }
  }
}
