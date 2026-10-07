package com.cameraprofile.studio.tests;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class GrantActivity extends Activity {
  public void onCreate(Bundle state) {
    super.onCreate(state);
    String[] names = {
      "input.jpg",
      "background.jpg",
      "user.jpg",
      "alpha.png",
      "input.webp",
      "orientation0.jpg",
      "ori1.jpg",
      "ori2.jpg",
      "ori3.jpg",
      "ori4.jpg",
      "ori5.jpg",
      "ori6.jpg",
      "ori7.jpg",
      "ori8.jpg",
      "protected.jpg",
      "credential22.jpg",
      "credential22.webp",
      "credential22.png",
      "first22.gif",
      "photo.jpg",
      "photos.zip",
      "fail.jpg",
      "unreadable.jpg",
      "regression.jpg",
      "regression.zip"
    };
    for (String name : names) {
      grantUriPermission(
          "com.cameraprofile.studio",
          FixtureProvider.uri("source/" + name),
          Intent.FLAG_GRANT_READ_URI_PERMISSION);
      grantUriPermission(
          "com.cameraprofile.studio",
          FixtureProvider.uri("export/" + name),
          Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }
    finish();
  }
}
