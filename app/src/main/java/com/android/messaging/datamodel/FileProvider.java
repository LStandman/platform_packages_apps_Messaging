/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.messaging.datamodel;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * A very simple content provider that can serve files.
 */
public abstract class FileProvider extends ContentProvider {
    // Object to generate random id for temp images.

    abstract File getFile(final String path, final String extension);

    private static final String FILE_EXTENSION_PARAM_KEY = "ext";

    /**
     * Check if filename conforms to requirement for our provider
     * @param fileId filename (optionally starting with path character
     * @return true if filename consists only of digits
     */
    protected static boolean isValidFileId(final String fileId) {
        // Ignore initial "/"
        for (int index = (fileId.startsWith("/") ? 1 : 0); index < fileId.length(); index++) {
            final char c = fileId.charAt(index);
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        final String fileId = uri.getPath();
        assert fileId != null;
        if (isValidFileId(fileId)) {
            final File file = getFile(fileId, getExtensionFromUri(uri));
            return file.delete() ? 1 : 0;
        }
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(final Uri uri, @NonNull final String fileMode)
            throws FileNotFoundException {
        final String fileId = uri.getPath();
        assert fileId != null;
        if (isValidFileId(fileId)) {
            final File file = getFile(fileId, getExtensionFromUri(uri));
            final int mode =
                    (TextUtils.equals(fileMode, "r") ? ParcelFileDescriptor.MODE_READ_ONLY :
                        ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
            return ParcelFileDescriptor.open(file, mode);
        }
        return null;
    }

    protected static String getExtensionFromUri(final Uri uri) {
        return uri.getQueryParameter(FILE_EXTENSION_PARAM_KEY);
    }

    @Override
    public Cursor query(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        // Don't support queries.
        return null;
    }

    @Override
    public Uri insert(@NonNull final Uri uri, final ContentValues values) {
        // Don't support inserts.
        return null;
    }

    @Override
    public int update(@NonNull final Uri uri, final ContentValues values, final String selection,
                      final String[] selectionArgs) {
        // Don't support updates.
        return 0;
    }

    @Override
    public String getType(@NonNull final Uri uri) {
        // No need for mime types.
        return null;
    }
}
