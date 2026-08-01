package ru.alikdrone.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Read-only, non-exported provider for APKs explicitly prepared by the user via
 * the inventory Install button.
 */
public final class InventoryApkProvider extends ContentProvider {
    static final String AUTHORITY = "ru.alikdrone.hub.inventory";
    static final String CACHE_DIRECTORY = "inventory-install";
    private static final Pattern SAFE_NAME =
            Pattern.compile("[a-f0-9]{32}\\.(apk|xapk)");
    private static final String APK_MIME =
            "application/vnd.android.package-archive";

    static Uri uriForName(String fileName) {
        if (!SAFE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Unsafe inventory cache name");
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(fileName)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        File file = resolveFile(uri);
        if (file == null) {
            return null;
        }
        return file.getName().endsWith(".apk")
                ? APK_MIME
                : "application/zip";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Inventory provider is read-only");
        }
        File file = resolveFile(uri);
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException("Prepared APK not found");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        File file = resolveFile(uri);
        if (file == null || !file.isFile()) {
            return null;
        }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private File resolveFile(Uri uri) {
        if (getContext() == null
                || uri == null
                || !AUTHORITY.equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1) {
            return null;
        }
        String fileName = uri.getLastPathSegment();
        if (fileName == null || !SAFE_NAME.matcher(fileName).matches()) {
            return null;
        }
        File root = new File(getContext().getCacheDir(), CACHE_DIRECTORY);
        File candidate = new File(root, fileName);
        try {
            String rootPath = root.getCanonicalPath();
            String candidatePath = candidate.getCanonicalPath();
            return candidatePath.startsWith(rootPath + File.separator)
                    ? candidate
                    : null;
        } catch (IOException error) {
            return null;
        }
    }
}
