package com.cameraprofile.studio;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;

/** User-started media foreground service. Wake maintenance is independent of inference. */
public final class ProcessingService extends Service {
  public static final String CANCEL = "com.cameraprofile.studio.CANCEL";
  private static final String CHANNEL = "photo-processing", COMPLETE_CHANNEL = "photo-complete";
  private static final Object QUEUE_LOCK = new Object();
  public static volatile boolean active;
  public static volatile String fatal = "";
  private static volatile long startedAt;
  private volatile boolean cancelled, destroyed, userCancelled;
  private volatile String stopReason = "", stage = "准备处理照片";
  private volatile int percent;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private PowerManager.WakeLock wakeLock;
  private long wakeRenewedAt;
  private int latestStartId;

  private final Runnable heartbeat = new Runnable() {
    public void run() {
      if (!active || destroyed) return;
      try {
        keepAwake();
        notifications().notify(41, progressNotification());
      } catch (Exception error) {
        stopReason = "后台运行受系统限制：" + error.getMessage();
        fatal = stopReason;
        cancelled = true;
      }
      if (!destroyed) main.postDelayed(this, 2000);
    }
  };

  public static String elapsedText() {
    long seconds = startedAt == 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - startedAt) / 1000;
    return seconds < 60 ? seconds + " 秒" : seconds / 60 + " 分 " + seconds % 60 + " 秒";
  }

  public void onCreate() {
    super.onCreate();
    notifications().createNotificationChannel(
        new NotificationChannel(CHANNEL, "照片处理进度", NotificationManager.IMPORTANCE_LOW));
    notifications().createNotificationChannel(
        new NotificationChannel(COMPLETE_CHANNEL, "照片处理完成", NotificationManager.IMPORTANCE_DEFAULT));
    wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraProfile:processing");
    wakeLock.setReferenceCounted(false);
  }

  public int onStartCommand(Intent intent, int flags, int startId) {
    latestStartId = startId;
    if (intent != null && CANCEL.equals(intent.getAction())) {
      userCancelled = cancelled = true;
      stage = "正在取消，等待当前计算块结束";
      try { TaskStore.get(this).cancelWaiting(); }
      catch (Exception error) { fatal = error.getMessage(); }
      if (!active) stopSelf(startId);
      return START_NOT_STICKY;
    }
    if (destroyed) return START_NOT_STICKY;
    if (active) return START_REDELIVER_INTENT;
    active = true;
    cancelled = userCancelled = false;
    stopReason = fatal = "";
    stage = "准备处理照片";
    percent = 0;
    startedAt = SystemClock.elapsedRealtime();
    try {
      int type = Build.VERSION.SDK_INT >= 35
          ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
          : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
      startForeground(41, progressNotification(), type);
      notifications().cancel(42);
      keepAwake();
      if (intent == null || (flags & START_FLAG_REDELIVERY) != 0)
        TaskStore.get(this).resumeSystemInterrupted();
    } catch (Exception error) {
      active = false;
      fatal = "系统未允许后台任务：" + error.getMessage();
      try { TaskStore.get(this).interruptActive(fatal); } catch (Exception ignored) {}
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }
    main.removeCallbacks(heartbeat);
    main.post(heartbeat);
    worker.execute(() -> {
      synchronized (QUEUE_LOCK) {
        if (!destroyed) runQueue();
      }
    });
    return START_REDELIVER_INTENT;
  }

  private void keepAwake() {
    long now = SystemClock.elapsedRealtime();
    if (!wakeLock.isHeld() || now - wakeRenewedAt >= 60000) {
      wakeLock.acquire(10 * 60 * 1000L);
      wakeRenewedAt = now;
    }
  }

  private void runQueue() {
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
    boolean failed = false;
    ArrayList<String> handled = new ArrayList<>();
    try {
      TaskStore store = TaskStore.get(this);
      PhotoEngine engine = new PhotoEngine(this, new ProfileRepository(this), store);
      TaskStore.Job job;
      while (!cancelled && !destroyed && (job = store.next()) != null) {
        final TaskStore.Job current = job;
        handled.add(job.id);
        try {
          engine.process(job, () -> cancelled || destroyed || Thread.currentThread().isInterrupted(),
              (text, value) -> {
                if (cancelled || destroyed) throw new InterruptedIOException("处理已中断");
                stage = text;
                percent = value;
                store.progress(current.id, text, value);
              });
        } catch (InterruptedIOException stop) {
          TaskStore.Job state = store.find(job.id);
          if (!destroyed && state != null && TaskStore.RUNNING.equals(state.state)) {
            if (!stopReason.isEmpty()) store.fail(job.id, TaskStore.INTERRUPTED, stopReason);
            else store.fail(job.id, TaskStore.CANCELLED, "处理已取消，可重试");
          }
        } catch (OutOfMemoryError memory) {
          TaskStore.Job state = store.find(job.id);
          if (!destroyed && state != null && TaskStore.RUNNING.equals(state.state))
            store.fail(job.id, TaskStore.FAILED, "手机内存不足，请选择较低尺寸或关闭 AI 后重试");
        } catch (Exception error) {
          String message = error.getMessage();
          TaskStore.Job state = store.find(job.id);
          if (!destroyed && state != null && TaskStore.RUNNING.equals(state.state))
            store.fail(job.id, TaskStore.FAILED,
                message == null ? error.getClass().getSimpleName() : message);
        }
      }
      if (cancelled && !destroyed) {
        if (userCancelled) store.cancelWaiting();
        else store.interruptActive(stopReason.isEmpty() ? "后台任务已中断，可重试" : stopReason);
      }
    } catch (Exception fatalError) {
      failed = true;
      if (!destroyed) {
        fatal = "队列未完成：" + fatalError.getMessage();
        try { TaskStore.get(this).interruptActive(fatal); } catch (Exception ignored) {}
      }
    } finally {
      final boolean resumePending = !failed;
      main.post(() -> {
        if (destroyed) return;
        active = false;
        try {
          if (!cancelled && resumePending) {
            for (TaskStore.Job pending : TaskStore.get(this).snapshot())
              if (TaskStore.QUEUED.equals(pending.state)) {
                onStartCommand(new Intent(), 0, latestStartId);
                return;
              }
          }
          notifyFinished(handled);
        } catch (Exception error) { fatal = error.getMessage(); }
        main.removeCallbacks(heartbeat);
        if (wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(latestStartId);
      });
    }
  }

  private NotificationManager notifications() {
    return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
  }

  private PendingIntent open(String page) {
    return PendingIntent.getActivity(this, 0,
        new Intent(this, MainActivity.class).putExtra("page", page),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private Notification progressNotification() {
    PendingIntent cancel = PendingIntent.getService(this, 1,
        new Intent(this, ProcessingService.class).setAction(CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String line = stage + " · 已用 " + elapsedText();
    Notification.Builder builder = new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_gallery)
        .setContentTitle("正在本机处理照片").setContentText(line)
        .setStyle(new Notification.BigTextStyle().bigText(line + "\n切换 App 后继续运行；取消会等待当前计算块结束。"))
        .setContentIntent(open("queue")).setOngoing(true).setOnlyAlertOnce(true)
        .setProgress(100, percent, percent == 0)
        .addAction(new Notification.Action.Builder(null, "取消", cancel).build());
    if (Build.VERSION.SDK_INT >= 31)
      builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    return builder.build();
  }

  private void notifyFinished(List<String> handled) throws Exception {
    if (handled.isEmpty()) return;
    int saved = 0, unfinished = 0;
    for (TaskStore.Job job : TaskStore.get(this).snapshot())
      if (handled.contains(job.id)) {
        if (TaskStore.SUCCESS.equals(job.state)) saved++;
        else unfinished++;
      }
    String text = "已保存 " + saved + " 张新副本" + (unfinished > 0 ? " · 未完成 " + unfinished + " 张" : "");
    notifications().notify(42, new Notification.Builder(this, COMPLETE_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_gallery)
        .setContentTitle(userCancelled ? "处理已取消" : unfinished > 0 ? "队列处理结束" : "照片处理完成")
        .setContentText(text).setContentIntent(open(unfinished > 0 ? "queue" : "results"))
        .setAutoCancel(true).build());
  }

  public void onTimeout(int startId, int type) {
    stopReason = "系统已达到后台处理时限；未完成照片可在回到 App 后重试";
    fatal = stopReason;
    cancelled = true;
    try { TaskStore.get(this).interruptActive(stopReason); } catch (Exception ignored) {}
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  public IBinder onBind(Intent intent) { return null; }

  public void onDestroy() {
    boolean wasActive = active;
    destroyed = cancelled = true;
    active = false;
    main.removeCallbacksAndMessages(null);
    if (wasActive) {
      try {
        TaskStore.get(this).interruptActive(
            stopReason.isEmpty() ? "后台任务已被系统中断，可重新处理当前照片" : stopReason,
            !userCancelled && stopReason.isEmpty());
      } catch (Exception ignored) {}
    }
    worker.shutdownNow();
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    super.onDestroy();
  }
}
