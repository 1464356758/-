package com.cameraprofile.studio.tests;
import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.net.Uri;
import com.cameraprofile.studio.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Real native ESRGAN tiles while home + screen off; not a phone benchmark. */
public final class BackgroundRunner extends NativeChecks {
  private MainActivity activity;
  protected void checks()throws Exception{
    int side=Integer.parseInt(arguments.getString("tileSide","2"));if(side<2||side>20)throw new IllegalArgumentException("tileSide must be 2..20");final int tiles=side*side;
    grants();await(()->!ProcessingService.active,30000,"idle service");store.clear();makeFixture("background.jpg",34*side,34*side);
    String original=Io.hash(context.getContentResolver().openInputStream(FixtureProvider.uri("source/background.jpg")));
    activity=open("background.jpg");ExportSettings s=new ExportSettings();s.profileId="gfx100ii";s.resolution=0;s.timeMode="none";s.detail=false;s.ai=true;s.strength=25;set(activity,"settings",s);redraw(activity);press(activity,"处理并保存新副本");
    await(()->{TaskStore.Job j=last();if(TaskStore.FAILED.equals(j.state))throw new AssertionError(j.error);return ProcessingService.active&&j.stage.startsWith("AI 分块推理")&&j.stage.endsWith("/"+tiles);},240000,"native AI stage");ok(true,"actual PROCESS button starts "+tiles+" native ESRGAN inference tiles");
    ok(notification(41)!=null,"foreground processing notification exists");
    shell("dumpsys battery unplug");shell("input keyevent 3");shell("input keyevent 223");
    PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
    await(()->!pm.isInteractive(),30000,"screen off");ok(true,"screen is off during native AI inference");
    final boolean[] visible={true};await(()->{runOnMainSync(()->{try{visible[0]=(Boolean)field(activity,"visible");}catch(Exception e){throw new RuntimeException(e);}});return !visible[0];},60000,"activity lifecycle stop");ok(true,"activity has stopped and UI polling is disabled");
    ok(shell("dumpsys power").contains("CameraProfile:processing"),"CPU wake lock held while screen is off");
    String offStage=last().stage;String before=notificationText();SystemClock.sleep(5000);String after=notificationText();ok(!before.equals(after)&&after.contains("已用"),"notification heartbeat changes independently of model callbacks");
    await(()->{TaskStore.Job j=last();if(TaskStore.FAILED.equals(j.state))throw new AssertionError(j.error);return !offStage.equals(j.stage);},360000,"inference advances in background");ok(!pm.isInteractive(),"real inference advances while screen remains off");
    await(()->!ProcessingService.active&&!TaskStore.RUNNING.equals(last().state)&&!TaskStore.QUEUED.equals(last().state),1200000,"background job completion");TaskStore.Job job=last();
    ok(TaskStore.SUCCESS.equals(job.state),"background native AI queue completes successfully");
    ok(job.report.getBoolean("ai_applied")&&job.report.getInt("width")==4000&&job.report.getInt("height")==3000,"native AI output readback is exact 4000 by 3000");
    ok(original.equals(Io.hash(context.getContentResolver().openInputStream(FixtureProvider.uri("source/background.jpg")))),"background reconstruction leaves original unchanged");
    ok(job.sha.equals(Io.hash(context.getContentResolver().openInputStream(Uri.parse(job.gallery)))),"gallery JPEG has identical verified SHA-256");
    ok(notification(41)==null&&notification(42)!=null,"progress notification replaced with completion notification");
    ok(!shell("dumpsys power").contains("CameraProfile:processing"),"CPU wake lock released after completion");
    try(InputStream in=new FileInputStream(store.result(job.id));OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"background-ai-12mp.jpg"))){Io.copy(in,out,200000000,()->false);}
    shell("input keyevent 224");shell("input keyevent 82");context.startActivity(new Intent(context,MainActivity.class).putExtra("page","queue").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700);snapshot("queue-completed-21.png");
    // User cancellation is exercised through the actual notification action.
    store.enqueue(Collections.singletonList(FixtureProvider.uri("source/background.jpg")),Collections.singletonList("background.jpg"),s);
    context.startForegroundService(new Intent(context,ProcessingService.class));await(()->ProcessingService.active&&last().stage.startsWith("AI 分块"),240000,"cancel job begins");
    StatusBarNotification progress=notification(41);ok(progress!=null&&progress.getNotification().actions.length>0,"notification provides a real cancel action");progress.getNotification().actions[0].actionIntent.send();
    await(()->!ProcessingService.active,240000,"cancel finishes current neural tile");ok(TaskStore.CANCELLED.equals(last().state)&&last().gallery.isEmpty(),"notification cancellation publishes no partial gallery photo");
    String cancelledId=last().id;
    // Cold journal recovery: only system interruptions qualify for redelivery.
    ExportSettings fast=new ExportSettings();fast.mode="metadata";fast.timeMode="none";
    store.enqueue(Collections.singletonList(FixtureProvider.uri("source/background.jpg")),Collections.singletonList("background.jpg"),fast);TaskStore.Job running=store.next();
    java.lang.reflect.Constructor<TaskStore> ctor=TaskStore.class.getDeclaredConstructor(Context.class);ctor.setAccessible(true);TaskStore recovered=ctor.newInstance(context);
    ok(TaskStore.INTERRUPTED.equals(recovered.find(running.id).state)&&recovered.find(running.id).systemResume,"cold journal load marks system interruption recoverable");
    int resumed=recovered.resumeSystemInterrupted();ok(resumed==1&&TaskStore.CANCELLED.equals(recovered.find(cancelledId).state),"redelivery resumes system interruption and preserves user cancellation");
    Field singleton=TaskStore.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,recovered);store=recovered;
    context.startForegroundService(new Intent(context,ProcessingService.class));await(()->!ProcessingService.active&&TaskStore.SUCCESS.equals(store.find(running.id).state),90000,"recovered metadata job");ok(true,"recovered job processes to a verified new copy");
  }
  private StatusBarNotification notification(int id){NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);for(StatusBarNotification n:nm.getActiveNotifications())if(n.getId()==id)return n;return null;}
  private String notificationText(){StatusBarNotification n=notification(41);return n==null?"":String.valueOf(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT));}
  protected void cleanup()throws Exception{shell("dumpsys battery reset");shell("input keyevent 224");shell("input keyevent 82");}
}
