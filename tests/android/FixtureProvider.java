package com.cameraprofile.studio.tests;

import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;

public final class FixtureProvider extends DocumentsProvider {
  public static final String AUTH = "com.cameraprofile.studio.tests.documents";

  public static Uri uri(String id) {
    return DocumentsContract.buildDocumentUri(AUTH, id);
  }

  public boolean onCreate() {
    return true;
  }

  File path(String id) throws FileNotFoundException {
    if (!id.matches("(?:source|export)/[A-Za-z0-9_.-]+")) throw new FileNotFoundException();
    File f =
        new File(
            getContext().getFilesDir(),
            id.startsWith("source/") ? "export/" + id.substring(7) : id);
    f.getParentFile().mkdirs();
    return f;
  }

  public Cursor queryRoots(String[] projection) {
    String[] cols =
        projection == null
            ? new String[] {
              DocumentsContract.Root.COLUMN_ROOT_ID,
              DocumentsContract.Root.COLUMN_DOCUMENT_ID,
              DocumentsContract.Root.COLUMN_TITLE,
              DocumentsContract.Root.COLUMN_FLAGS
            }
            : projection;
    MatrixCursor c = new MatrixCursor(cols);
    MatrixCursor.RowBuilder r = c.newRow();
    for (String k : cols) {
      if (k.equals(DocumentsContract.Root.COLUMN_ROOT_ID)) r.add("fixtures");
      else if (k.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID)) r.add("export");
      else if (k.equals(DocumentsContract.Root.COLUMN_TITLE)) r.add("Runtime fixtures");
      else if (k.equals(DocumentsContract.Root.COLUMN_FLAGS))
        r.add(DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
      else r.add(null);
    }
    return c;
  }

  public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
    File f = path(id);
    String[] cols =
        projection == null
            ? new String[] {
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              OpenableColumns.DISPLAY_NAME,
              OpenableColumns.SIZE,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_FLAGS
            }
            : projection;
    MatrixCursor c = new MatrixCursor(cols);
    MatrixCursor.RowBuilder r = c.newRow();
    for (String k : cols) {
      if (k.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) r.add(id);
      else if (k.equals(OpenableColumns.DISPLAY_NAME)) r.add(f.getName());
      else if (k.equals(OpenableColumns.SIZE)) r.add(f.length());
      else if (k.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))
        r.add(f.getName().endsWith(".zip") ? "application/zip" : "image/jpeg");
      else if (k.equals(DocumentsContract.Document.COLUMN_FLAGS))
        r.add(
            id.startsWith("export/")
                ? DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                    | DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                : 0);
      else r.add(null);
    }
    return c;
  }

  public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) {
    return new MatrixCursor(new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID});
  }

  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancel)
      throws FileNotFoundException {
    if (!"r".equals(mode) && !id.startsWith("export/"))
      throw new FileNotFoundException("source is read only");
    if (id.equals("export/fail.jpg") && mode.contains("w"))
      throw new FileNotFoundException("injected write failure");
    if (id.equals("export/unreadable.jpg") && "r".equals(mode))
      throw new FileNotFoundException("injected read failure on an existing writable file");
    return ParcelFileDescriptor.open(path(id), ParcelFileDescriptor.parseMode(mode));
  }

  public void deleteDocument(String id) throws FileNotFoundException {
    if (!id.startsWith("export/")) throw new FileNotFoundException();
    path(id).delete();
  }

  public String createDocument(String parent, String mime, String name)
      throws FileNotFoundException {
    String id = "export/" + name;
    try {
      path(id).createNewFile();
    } catch (IOException e) {
      throw new FileNotFoundException();
    }
    return id;
  }
}
