package com.cameraprofile.studio;

import android.content.*;
import android.os.Build;
import android.provider.MediaStore;

/** Distinct native photo and document entry points without broad storage access. */
public final class ImportPicker {
  public static Intent album(Context context) {
    Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
    if (picker.resolveActivity(context.getPackageManager()) != null) {
      int limit = Build.VERSION.SDK_INT >= 33 ? Math.min(100, MediaStore.getPickImagesMaxLimit()) : 100;
      picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, limit);
      return picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    Intent gallery = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (gallery.resolveActivity(context.getPackageManager()) != null) return gallery;
    return Intent.createChooser(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "从相册选择");
  }

  public static Intent files() {
    return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  }
}
