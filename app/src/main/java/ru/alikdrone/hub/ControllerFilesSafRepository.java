package ru.alikdrone.hub;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Narrow Storage Access Framework adapter.
 *
 * <p>Every document URI returned here is derived from the tree URI selected by
 * the user. No raw paths, shell commands, broad storage permissions or private
 * Android/data workarounds are used.</p>
 */
public final class ControllerFilesSafRepository {
    static final int MAX_SCANNED_ITEMS = 500;
    private static final String[] PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
    };

    public static final class Listing {
        public final List<ControllerFileItem> items;
        public final boolean truncated;

        Listing(List<ControllerFileItem> items, boolean truncated) {
            this.items = Collections.unmodifiableList(items);
            this.truncated = truncated;
        }
    }

    public void persistTreePermission(ContentResolver resolver, Uri treeUri, int resultFlags) {
        if (!isContentUri(treeUri)) {
            throw new IllegalArgumentException("Only SAF content URIs are supported");
        }
        int persistable = resultFlags
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        resolver.takePersistableUriPermission(treeUri, persistable);
    }

    public void releaseTreePermission(ContentResolver resolver, Uri treeUri) {
        if (!isContentUri(treeUri)) {
            return;
        }
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (!treeUri.equals(permission.getUri())) {
                continue;
            }
            int flags = 0;
            if (permission.isReadPermission()) {
                flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            }
            if (permission.isWritePermission()) {
                flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
            if (flags != 0) {
                try {
                    resolver.releasePersistableUriPermission(treeUri, flags);
                } catch (RuntimeException ignored) {
                    // A revoked provider permission is already effectively released.
                }
            }
            return;
        }
    }

    public List<ControllerFileItem> list(ContentResolver resolver, Uri treeUri) {
        return list(resolver, treeUri, null);
    }

    public List<ControllerFileItem> list(
            ContentResolver resolver,
            Uri treeUri,
            CancellationSignal cancellationSignal
    ) {
        return listLimited(resolver, treeUri, cancellationSignal).items;
    }

    public Listing listLimited(
            ContentResolver resolver,
            Uri treeUri,
            CancellationSignal cancellationSignal
    ) {
        if (!isContentUri(treeUri)) {
            return new Listing(Collections.emptyList(), false);
        }
        String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException invalidTree) {
            return new Listing(Collections.emptyList(), false);
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                rootDocumentId
        );
        List<ControllerFileItem> result = new ArrayList<>();
        boolean truncated = false;
        try (Cursor cursor = resolver.query(
                childrenUri,
                PROJECTION,
                null,
                null,
                null,
                cancellationSignal
        )) {
            if (cursor == null) {
                return new Listing(result, false);
            }
            int idIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
            );
            int nameIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            );
            int mimeIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            );
            int sizeIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_SIZE
            );
            int modifiedIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            );
            int flagsIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_FLAGS
            );
            while (cursor.moveToNext()) {
                if (cancellationSignal != null
                        && cancellationSignal.isCanceled()) {
                    break;
                }
                if (result.size() >= MAX_SCANNED_ITEMS) {
                    truncated = true;
                    break;
                }
                String documentId = cursor.getString(idIndex);
                String name = cursor.getString(nameIndex);
                String mime = cursor.getString(mimeIndex);
                long size = cursor.isNull(sizeIndex) ? -1L : cursor.getLong(sizeIndex);
                long modified = cursor.isNull(modifiedIndex) ? 0L : cursor.getLong(modifiedIndex);
                int flags = cursor.isNull(flagsIndex) ? 0 : cursor.getInt(flagsIndex);
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId
                );
                result.add(new ControllerFileItem(
                        documentUri,
                        name,
                        mime,
                        size,
                        modified,
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                        (flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                ));
            }
        } catch (RuntimeException inaccessible) {
            return new Listing(Collections.emptyList(), false);
        }
        Collections.sort(result, new Comparator<ControllerFileItem>() {
            @Override
            public int compare(ControllerFileItem left, ControllerFileItem right) {
                if (left.directory != right.directory) {
                    return left.directory ? -1 : 1;
                }
                int modifiedOrder = Long.compare(right.modifiedAt, left.modifiedAt);
                return modifiedOrder != 0
                        ? modifiedOrder
                        : left.displayName.compareToIgnoreCase(right.displayName);
            }
        });
        return new Listing(result, truncated);
    }

    public boolean delete(
            ContentResolver resolver,
            ControllerFileItem item,
            boolean userConfirmed,
            boolean itemCameFromCurrentListing
    ) {
        if (item == null
                || item.directory
                || !item.supportsDelete
                || !isContentUri(item.uri)
                || !ControllerFilesPolicy.canDelete(userConfirmed, itemCameFromCurrentListing)) {
            return false;
        }
        try {
            ControllerFileItem current = queryDocument(resolver, item.uri);
            if (!sameMetadata(item, current)) {
                return false;
            }
            return DocumentsContract.deleteDocument(resolver, item.uri);
        } catch (IOException | RuntimeException denied) {
            return false;
        }
    }

    private ControllerFileItem queryDocument(
            ContentResolver resolver,
            Uri documentUri
    ) {
        try (Cursor cursor = resolver.query(
                documentUri,
                PROJECTION,
                null,
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String documentId = cursor.getString(cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
            ));
            String name = cursor.getString(cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ));
            String mime = cursor.getString(cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            ));
            int sizeIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_SIZE
            );
            int modifiedIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            );
            int flagsIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_FLAGS
            );
            long size = cursor.isNull(sizeIndex) ? -1L : cursor.getLong(sizeIndex);
            long modified = cursor.isNull(modifiedIndex)
                    ? 0L
                    : cursor.getLong(modifiedIndex);
            int flags = cursor.isNull(flagsIndex) ? 0 : cursor.getInt(flagsIndex);
            Uri canonicalUri = DocumentsContract.buildDocumentUriUsingTree(
                    documentUri,
                    documentId
            );
            return new ControllerFileItem(
                    canonicalUri,
                    name,
                    mime,
                    size,
                    modified,
                    DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                    (flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
            );
        } catch (RuntimeException inaccessible) {
            return null;
        }
    }

    static boolean sameMetadata(
            ControllerFileItem expected,
            ControllerFileItem current
    ) {
        return expected != null
                && current != null
                && expected.uri.equals(current.uri)
                && expected.displayName.equals(current.displayName)
                && expected.mimeType.equals(current.mimeType)
                && expected.sizeBytes == current.sizeBytes
                && expected.modifiedAt == current.modifiedAt
                && expected.directory == current.directory
                && expected.supportsDelete == current.supportsDelete;
    }

    private boolean isContentUri(Uri uri) {
        return uri != null && ControllerFilesPolicy.isSafContentUri(uri.toString());
    }
}
