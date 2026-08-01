package ru.alikdrone.hub;

import android.net.Uri;

public final class ControllerFileItem {
    public final Uri uri;
    public final String displayName;
    public final String mimeType;
    public final long sizeBytes;
    public final long modifiedAt;
    public final boolean directory;
    public final boolean supportsDelete;

    public ControllerFileItem(
            Uri uri,
            String displayName,
            String mimeType,
            long sizeBytes,
            long modifiedAt,
            boolean directory,
            boolean supportsDelete
    ) {
        this.uri = uri;
        this.displayName = displayName == null ? "" : displayName;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.sizeBytes = Math.max(-1L, sizeBytes);
        this.modifiedAt = Math.max(0L, modifiedAt);
        this.directory = directory;
        this.supportsDelete = supportsDelete;
    }
}
