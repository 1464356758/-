package com.cameraprofile.studio;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Application-owned atomic job journal. Input files are never opened for writing. */
public final class TaskStore {
  public static final String QUEUED = "QUEUED",
      RUNNING = "RUNNING",
      SUCCESS = "SUCCESS",
      FAILED = "FAILED",
      CANCELLED = "CANCELLED",
      INTERRUPTED = "INTERRUPTED";
  private static TaskStore instance;

  public static synchronized TaskStore get(Context context) throws Exception {
    if (instance == null) instance = new TaskStore(context.getApplicationContext());
    return instance;
  }

  public static final class Job {
    public String id, input, name, batch, state = QUEUED, stage = "等待处理", error = "", gallery = "";
    public String sha = "";
    public long created, bytes;
    public boolean systemResume;
    public int progress;
    public ExportSettings settings;
    public JSONObject report;

    public JSONObject json() throws JSONException {
      JSONObject j = new JSONObject();
      j.put("id", id).put("input", input).put("name", name).put("batch", batch).put("state", state);
      j.put("stage", stage).put("error", error).put("gallery", gallery).put("sha256", sha);
      j.put("system_resume", systemResume);
      j.put("created", created)
          .put("bytes", bytes)
          .put("progress", progress)
          .put("settings", settings.json());
      if (report != null) j.put("report", report);
      return j;
    }

    static Job parse(JSONObject j) throws JSONException {
      Job job = new Job();
      job.id = j.getString("id");
      job.input = j.getString("input");
      job.name = j.getString("name");
      job.batch = j.getString("batch");
      job.state = j.optString("state", QUEUED);
      job.stage = j.optString("stage", "等待处理");
      job.error = j.optString("error");
      job.gallery = j.optString("gallery");
      job.sha = j.optString("sha256");
      job.created = j.optLong("created");
      job.bytes = j.optLong("bytes");
      job.progress = j.optInt("progress");
      job.systemResume = j.optBoolean("system_resume", false);
      job.settings = ExportSettings.parse(j.getJSONObject("settings"));
      job.report = j.optJSONObject("report");
      if (!job.id.matches("[a-f0-9-]{36}")) throw new JSONException("任务编号损坏");
      return job;
    }

    Job copy() {
      try {
        return parse(new JSONObject(json().toString()));
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private final AtomicFile journal;
  private final File results;
  private final ArrayList<Job> jobs = new ArrayList<>();
  private String loadNotice = "";
  private long revision, lastProgressSave;

  private TaskStore(Context context) throws Exception {
    results = new File(context.getFilesDir(), "results");
    if (!results.exists() && !results.mkdirs()) throw new IOException("无法创建结果目录");
    journal = new AtomicFile(new File(context.getFilesDir(), "tasks-v2.json"));
    if (journal.getBaseFile().exists() || new File(journal.getBaseFile() + ".bak").exists()) {
      try {
        JSONObject doc =
            new JSONObject(
                new String(Io.read(journal.openRead(), 4 * 1024 * 1024), StandardCharsets.UTF_8));
        JSONArray array = doc.getJSONArray("jobs");
        for (int i = 0; i < array.length(); i++) jobs.add(Job.parse(array.getJSONObject(i)));
        for (Job job : jobs)
          if (RUNNING.equals(job.state) || QUEUED.equals(job.state)) {
            job.state = INTERRUPTED;
            job.systemResume = true;
            job.stage = "上次任务已中断";
            job.error = "可继续处理；已保存照片会先核对，避免重复生成";
          }
      } catch (Exception corrupt) {
        jobs.clear();
        File backup =
            new File(
                context.getFilesDir(), "tasks-unreadable-" + System.currentTimeMillis() + ".json");
        journal.getBaseFile().renameTo(backup);
        journal.delete();
        loadNotice = "工作记录无法读取。原图及已经保存到相册的照片仍可使用。";
      }
      if (!jobs.isEmpty())
        try {
          save();
        } catch (Exception unavailable) {
          loadNotice = "暂时无法保存工作记录，请检查存储空间；相册照片保留。";
        }
    }
  }

  public synchronized String notice() {
    return loadNotice;
  }

  public synchronized long revision() {
    return revision;
  }

  public synchronized File result(String id) {
    if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("任务编号无效");
    return new File(results, id + ".jpg");
  }

  public synchronized List<Job> snapshot() {
    ArrayList<Job> copy = new ArrayList<>();
    for (Job job : jobs) copy.add(job.copy());
    return copy;
  }

  public synchronized Job find(String id) {
    for (Job job : jobs) if (job.id.equals(id)) return job.copy();
    return null;
  }

  public synchronized String enqueue(List<Uri> sources, List<String> names, ExportSettings settings)
      throws Exception {
    if (sources.isEmpty()) throw new IOException("请先导入照片");
    if (sources.size() > 100) throw new IOException("单批最多 100 张照片");
    if (jobs.size() + sources.size() > 300) throw new IOException("工作记录已达到 300 条，请先清理本地工作记录");
    String batch = UUID.randomUUID().toString();
    for (int i = 0; i < sources.size(); i++) {
      Job job = new Job();
      job.id = UUID.randomUUID().toString();
      job.batch = batch;
      job.input = sources.get(i).toString();
      job.name = names.get(i);
      job.created = System.currentTimeMillis();
      job.settings = ExportSettings.parse(settings.json());
      jobs.add(job);
    }
    save();
    return batch;
  }

  public synchronized Job next() throws Exception {
    for (Job job : jobs)
      if (QUEUED.equals(job.state)) {
        job.state = RUNNING;
        job.systemResume = false;
        job.stage = "正在读取";
        job.error = "";
        job.progress = 0;
        save();
        return job.copy();
      }
    return null;
  }

  public synchronized void progress(String id, String stage, int percent) throws Exception {
    for (Job job : jobs)
      if (job.id.equals(id) && RUNNING.equals(job.state)) {
        job.stage = stage;
        job.progress = Math.max(0, Math.min(99, percent));
        revision++;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastProgressSave >= 10000) { save(); lastProgressSave = now; }
        return;
      }
  }

  public synchronized void checkpoint(
      String id, String gallery, String sha, long bytes, JSONObject report) throws Exception {
    Job job = mutable(id);
    job.gallery = gallery;
    job.sha = sha;
    job.bytes = bytes;
    job.report = report;
    save();
  }

  public synchronized void complete(String id) throws Exception {
    Job job = mutable(id);
    job.state = SUCCESS;
    job.systemResume = false;
    job.stage = "已验证并保存";
    job.error = "";
    job.progress = 100;
    save();
  }

  public synchronized void fail(String id, String state, String error) throws Exception {
    Job job = mutable(id);
    job.state = state;
    job.systemResume = false;
    job.stage = state.equals(CANCELLED) ? "已取消" : "未完成";
    job.error = error;
    save();
  }

  public synchronized int retry() throws Exception {
    int count = 0;
    for (Job job : jobs)
      if (FAILED.equals(job.state)
          || CANCELLED.equals(job.state)
          || INTERRUPTED.equals(job.state)) {
        job.state = QUEUED;
        job.systemResume = false;
        job.stage = "等待重试";
        job.progress = 0;
        count++;
      }
    if (count > 0) save();
    return count;
  }

  public synchronized void cancelWaiting() throws Exception {
    for (Job job : jobs)
      if (QUEUED.equals(job.state)) {
        job.state = CANCELLED;
        job.systemResume = false;
        job.stage = "已取消";
        job.error = "用户取消，可重试";
      }
    save();
  }

  /** Keep the UI recoverable even when the journal cannot be written. */
  public synchronized void interruptActive(String reason) throws Exception {
    interruptActive(reason, false);
  }

  public synchronized void interruptActive(String reason, boolean resume) throws Exception {
    for (Job job : jobs)
      if (RUNNING.equals(job.state) || QUEUED.equals(job.state)) {
        job.state = INTERRUPTED;
        job.systemResume = resume;
        job.stage = "队列已中断";
        job.error = reason;
      }
    revision++;
    save();
  }

  public synchronized int resumeSystemInterrupted() throws Exception {
    int count = 0;
    for (Job job : jobs)
      if (INTERRUPTED.equals(job.state) && job.systemResume) {
        job.state = QUEUED;
        job.systemResume = false;
        job.stage = "系统恢复处理中";
        job.error = "";
        job.progress = 0;
        count++;
      }
    if (count > 0) save();
    return count;
  }

  public synchronized void clear() throws Exception {
    for (Job job : jobs)
      if (RUNNING.equals(job.state) || QUEUED.equals(job.state)) throw new IOException("请先结束当前队列");
    // Gallery copies are intentionally retained.
    for (Job job : jobs) result(job.id).delete();
    jobs.clear();
    save();
  }

  private Job mutable(String id) {
    for (Job job : jobs) if (job.id.equals(id)) return job;
    throw new IllegalArgumentException("任务不存在");
  }

  private void save() throws Exception {
    JSONArray array = new JSONArray();
    for (Job job : jobs) array.put(job.json());
    byte[] bytes =
        new JSONObject()
            .put("schema_version", 2)
            .put("jobs", array)
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    FileOutputStream stream = null;
    try {
      stream = journal.startWrite();
      stream.write(bytes);
      journal.finishWrite(stream);
      revision++;
    } catch (Exception e) {
      if (stream != null) journal.failWrite(stream);
      throw new IOException("无法保存工作记录，请检查存储空间", e);
    }
  }
}
