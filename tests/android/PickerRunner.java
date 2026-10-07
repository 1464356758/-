package com.cameraprofile.studio.tests;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.MediaStore;
import com.cameraprofile.studio.*;
import java.lang.reflect.*;
import java.util.*;

public final class PickerRunner extends NativeChecks {
  protected void checks()throws Exception{
    grants();makeFixture("input.jpg",64,48);makeFixture("background.jpg",68,34);MainActivity a=open("input.jpg");
    Intent album=ImportPicker.album(context), files=ImportPicker.files();
    ok(MediaStore.ACTION_PICK_IMAGES.equals(album.getAction()),"album uses native Photo Picker on Android 14");
    ok(album.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,0)>1,"album supports multiple photos within platform limit");
    ok(Intent.ACTION_OPEN_DOCUMENT.equals(files.getAction())&&files.hasCategory(Intent.CATEGORY_OPENABLE)&&files.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false),"folder uses multi-select document picker");
    importDialog(a);await(()->text("从相册选择")&&text("从文件夹选择"),45000,"two import choices");ok(true,"import dialog exposes album and folder choices");snapshot("import-choices-21.png");
    IntentFilter af=new IntentFilter(album.getAction());af.addDataType("image/*");ActivityMonitor monitor=addMonitor(af,new ActivityResult(Activity.RESULT_CANCELED,null),true);
    tap("从相册选择");await(()->monitor.getHits()==1,10000,"album dispatch");removeMonitor(monitor);ok(true,"album dialog action dispatches Photo Picker intent");
    ok(((List<?>)field(a,"inputs")).size()==1,"cancelling album preserves selected photo");
    importDialog(a);IntentFilter ff=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);ff.addDataType("image/*");ff.addCategory(Intent.CATEGORY_OPENABLE);ActivityMonitor fm=addMonitor(ff,new ActivityResult(Activity.RESULT_CANCELED,null),true);
    tap("从文件夹选择");await(()->fm.getHits()==1,10000,"folder dispatch");removeMonitor(fm);ok(true,"folder dialog action dispatches OPEN_DOCUMENT");
    ClipData clip=ClipData.newUri(context.getContentResolver(),"photos",FixtureProvider.uri("source/input.jpg"));clip.addItem(new ClipData.Item(FixtureProvider.uri("source/background.jpg")));clip.addItem(new ClipData.Item(FixtureProvider.uri("source/input.jpg")));
    Intent data=new Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);data.setClipData(clip);Method result=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);
    runOnMainSync(()->{try{result.invoke(a,11,Activity.RESULT_OK,data);}catch(Exception e){throw new RuntimeException(e);}});
    ok(((List<?>)field(a,"inputs")).size()==2,"album multiple selection deduplicates URIs");
    importDialog(a);tap("从相册选择");String expected=album.resolveActivity(context.getPackageManager()).getPackageName();await(()->{onlySystemWait();android.view.accessibility.AccessibilityNodeInfo r=getUiAutomation().getRootInActiveWindow();return r!=null&&expected.contentEquals(r.getPackageName());},30000,"real system photo picker");snapshot("native-album-21.png");ok(true,"real native Photo Picker opens successfully");shell("input keyevent 4");
  }
}
