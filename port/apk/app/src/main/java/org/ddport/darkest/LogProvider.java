package org.ddport.darkest;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the log file DDActivity.sendLogs writes to the app the share sheet picks (mail, chat, …), read
 * only and only to that app (the share intent grants it the URI). It serves that one file and nothing
 * else.
 */
public class LogProvider extends ContentProvider {
    static final String NAME = "darkest-dungeon-porter-logs.txt";
    static final Uri URI = Uri.parse("content://org.ddport.darkest.logs/" + NAME);

    static File file(Context c) {
        return new File(c.getCacheDir(), NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!NAME.equals(uri.getLastPathSegment()))
            throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The name and size, which mail apps ask for before attaching the file. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i]))
                row[i] = NAME;
            else if (OpenableColumns.SIZE.equals(columns[i]))
                row[i] = file(getContext()).length();
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
