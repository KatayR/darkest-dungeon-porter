package org.ddport.darkest;

import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A zip, 7z or RAR (4 or 5) file read through libarchive (libddarchive.so), an entry at a time like
 * ZipInputStream: nextEntry() moves to the next entry, and read() gives its data. Each one reads the
 * file from its start, so a second pass is a second ArchiveInputStream on the same descriptor, which
 * stays open: it is the caller's. Encrypted archives can't be read. libarchive's errors come as
 * ReadException.
 */
final class ArchiveInputStream extends InputStream {
    static {
        System.loadLibrary("ddarchive");
    }

    private static native long open(int fd) throws IOException;
    private static native byte[] next(long h, long[] info) throws IOException;
    private static native int read(long h, byte[] buf, int off, int len) throws IOException;
    private static native void free(long h);

    /** libarchive couldn't read the archive; `encrypted` if it found encrypted entries or headers in it. */
    static final class ReadException extends IOException {
        final boolean encrypted;

        ReadException(String message, boolean encrypted) {   // from ddarchive.c
            super(message);
            this.encrypted = encrypted;
        }
    }

    private long h;
    private final long[] info = new long[3];   // the entry's type, time, encrypted (see ddarchive.c)

    ArchiveInputStream(ParcelFileDescriptor file) throws IOException {
        h = open(file.getFd());
    }

    /** The next entry's name, a folder's ending in /, or null at the end. */
    String nextEntry() throws IOException {
        byte[] name = next(h, info);
        if (name == null)
            return null;
        String s = new String(name, StandardCharsets.UTF_8);
        return isDirectory() && !s.endsWith("/") ? s + "/" : s;
    }

    boolean isDirectory() {
        return info[0] == 1;
    }

    /** A file's entry, not a folder's or a link's. */
    boolean isFile() {
        return info[0] == 0;
    }

    /** The entry's time in ms, or -1. */
    long time() {
        return info[1];
    }

    boolean isEncrypted() {
        return info[2] != 0;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off)
            throw new IndexOutOfBoundsException();
        return read(h, b, off, len);
    }

    @Override
    public void close() {
        if (h != 0)
            free(h);
        h = 0;
    }
}
