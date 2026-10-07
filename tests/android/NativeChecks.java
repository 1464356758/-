package com.cameraprofile.studio.tests;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.cameraprofile.studio.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Native integration harness; never suppresses an application ANR. */
public abstract class NativeChecks extends Instrumentation {
  protected Context context;
  protected TaskStore store;
  protected Bundle arguments;
  protected final StringBuilder log = new StringBuilder();
  protected abstract void checks() throws Exception;
  protected void cleanup() throws Exception {}
  public void onCreate(Bundle args) {super.onCreate(args);arguments=args==null?new Bundle():args; start();}
  public void onStart() {
    Bundle result=new Bundle(); Throwable failure=null;
    try {context=getTargetContext(); store=TaskStore.get(context); trace("native harness started"); checks();}
    catch(Throwable e) {failure=e;}
    finally {try {cleanup();} catch(Throwable e) {if(failure==null)failure=e;}}
    result.putString("report",log.toString());
    if(failure!=null){result.putString("failure",failure.toString()); StringWriter s=new StringWriter(); failure.printStackTrace(new PrintWriter(s)); result.putString("trace",s.toString());}
    try {File dir=context.getExternalFilesDir(null); dir.mkdirs(); try(Writer out=new FileWriter(new File(dir,getClass().getSimpleName()+".txt"))){out.write(log.toString()); if(failure!=null)out.write("FAIL "+failure);}}catch(Exception ignored){}
    finish(failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
  }
  protected void trace(String name) {Bundle status=new Bundle();status.putString("phase",name);sendStatus(1,status);android.util.Log.i("StudioNativeTest",name);}
  protected void ok(boolean pass,String name) {if(!pass)throw new AssertionError(name); log.append("PASS ").append(name).append('\n');trace("PASS "+name);}
  protected void await(java.util.concurrent.Callable<Boolean> condition,long ms,String name) throws Exception {
    long end=SystemClock.elapsedRealtime()+ms; while(SystemClock.elapsedRealtime()<end){if(condition.call())return;SystemClock.sleep(250);} throw new AssertionError("Timeout: "+name);
  }
  protected String shell(String command) throws Exception {
    try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command); InputStream in=new FileInputStream(fd.getFileDescriptor())){
      return new String(Io.read(in,4*1024*1024),"UTF-8");
    }
  }
  protected void grants() throws Exception {
    trace("requesting fixture URI grants");
    context.startActivity(new Intent().setClassName("com.cameraprofile.studio.tests","com.cameraprofile.studio.tests.GrantActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    await(()->context.checkUriPermission(FixtureProvider.uri("source/background.jpg"),android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED&&context.checkUriPermission(FixtureProvider.uri("export/background.jpg"),android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED,60000,"fixture grants");
    trace("fixture URI grants ready");
  }
  protected MainActivity open(String name) throws Exception {
    trace("launching main activity");
    ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
    shell("am start -n com.cameraprofile.studio/.MainActivity -f 0x10008000");
    MainActivity a=(MainActivity)waitForMonitorWithTimeout(monitor,45000);removeMonitor(monitor);
    if(a==null)throw new AssertionError("Main activity did not start");
    trace("main activity started");
    Method result=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);
    Intent data=new Intent().setData(FixtureProvider.uri("source/"+name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    runOnMainSync(()->{try{result.invoke(a,11,Activity.RESULT_OK,data);}catch(Exception e){throw new RuntimeException(e);}});
    return a;
  }
  protected Object field(Object obj,String name)throws Exception{Field f=obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj);}
  protected void set(Object obj,String name,Object value)throws Exception{Field f=obj.getClass().getDeclaredField(name); f.setAccessible(true); f.set(obj,value);}
  protected void redraw(MainActivity a)throws Exception{Method m=MainActivity.class.getDeclaredMethod("render");m.setAccessible(true);runOnMainSync(()->{try{m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();}
  protected Button find(View v,String text){if(v instanceof Button&&text.equals(((Button)v).getText().toString()))return(Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=find(((ViewGroup)v).getChildAt(i),text);if(b!=null)return b;}return null;}
  protected void press(MainActivity a,String text){runOnMainSync(()->{Button b=find(a.getWindow().getDecorView(),text);if(b==null)throw new AssertionError("Button missing: "+text); b.performClick();});}
  protected void importDialog(MainActivity a)throws Exception{Method m=MainActivity.class.getDeclaredMethod("pick");m.setAccessible(true);runOnMainSync(()->{try{m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();SystemClock.sleep(300);}
  protected void onlySystemWait(){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null&&"android".contentEquals(root.getPackageName())&&(!root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty()||!root.findAccessibilityNodeInfosByText("Process system isn't responding").isEmpty())){for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText("Wait"))touchNode(n);log.append("ENV software emulator OS process ANR; Wait selected\n");SystemClock.sleep(500);}}
  protected boolean text(String value){onlySystemWait(); AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(value))if(value.contentEquals(n.getText()))return true;return false;}
  protected void touchNode(AccessibilityNodeInfo n){
    Rect r=new Rect();n.getBoundsInScreen(r);long now=SystemClock.uptimeMillis();
    MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    MotionEvent up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,r.centerX(),r.centerY(),0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try{getUiAutomation().injectInputEvent(down,true);getUiAutomation().injectInputEvent(up,true);}finally{down.recycle();up.recycle();}
  }
  protected void tap(String value)throws Exception{
    onlySystemWait();AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)throw new AssertionError("No active window");
    for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(value))if(value.contentEquals(n.getText())){
      Rect r=new Rect(); n.getBoundsInScreen(r); long now=SystemClock.uptimeMillis();
      MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      MotionEvent up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,r.centerX(),r.centerY(),0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      try{getUiAutomation().injectInputEvent(down,true);getUiAutomation().injectInputEvent(up,true);}finally{down.recycle();up.recycle();}return;
    }throw new AssertionError("Text not found: "+value);
  }
  protected void snapshot(String name)throws Exception{onlySystemWait(); Bitmap image=getUiAutomation().takeScreenshot();if(image==null)throw new IOException("No screenshot");try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
  protected void makeFixture(String name,int width,int height)throws Exception{
    Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);for(int y=0;y<height;y++)for(int x=0;x<width;x++)b.setPixel(x,y,Color.rgb((x*3)%256,(y*7)%256,((x+y)*4)%256));
    try(OutputStream out=context.getContentResolver().openOutputStream(FixtureProvider.uri("export/"+name),"wt")){if(!b.compress(Bitmap.CompressFormat.JPEG,98,out))throw new IOException("Fixture encoding");}finally{b.recycle();}
  }
  protected TaskStore.Job last(){List<TaskStore.Job> jobs=store.snapshot();return jobs.get(jobs.size()-1);}
}
