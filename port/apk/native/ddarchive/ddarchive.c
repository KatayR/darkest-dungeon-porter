// libddarchive.so: reads the archives the app imports mods from (zip, 7z, RAR4 and RAR5) through
// libarchive, for ArchiveInputStream: an entry at a time, like ZipInputStream. libarchive and liblzma
// are linked in statically (built by build-natives.sh).
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

// throws ArchiveInputStream.ReadException: what failed, libarchive's reason, and whether the archive
// was found to hold encrypted entries or headers (which libarchive can't read)
static void throw_io(JNIEnv *env, struct archive *a, const char *what) {
    const char *err = a ? archive_error_string(a) : strerror(errno);
    char msg[512];
    snprintf(msg, sizeof msg, "%s: %s", what, err ? err : "unknown error");
    for (char *c = msg; *c; c++)   // NewStringUTF takes only valid UTF-8; a file name in it may not be
        if (*c & 0x80)
            *c = '?';
    jclass cls = (*env)->FindClass(env, "org/ddport/darkest/ArchiveInputStream$ReadException");
    jmethodID init = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;Z)V");
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    jobject ex = (*env)->NewObject(env, cls, init, jmsg, a && archive_read_has_encrypted_entries(a) > 0);
    if (ex)
        (*env)->Throw(env, ex);
}

// open(fd): a reader of the file from its start (fd stays open: it is the caller's)
JNIEXPORT jlong JNICALL
Java_org_ddport_darkest_ArchiveInputStream_open(JNIEnv *env, jclass cls, jint fd) {
    if (lseek(fd, 0, SEEK_SET) < 0) {
        throw_io(env, NULL, "Can't read the file");
        return 0;
    }
    struct archive *a = archive_read_new();
    archive_read_support_format_zip(a);
    archive_read_support_format_7zip(a);
    archive_read_support_format_rar(a);
    archive_read_support_format_rar5(a);
    if (archive_read_open_fd(a, fd, 1 << 16) != ARCHIVE_OK) {
        throw_io(env, a, "Can't open the archive");
        archive_read_free(a);
        return 0;
    }
    return (jlong)(intptr_t)a;
}

// next(h, info): the next entry's name as UTF-8, or null at the end; info gets its type (0 a file,
// 1 a folder, 2 anything else), its time in ms (-1 if it has none) and 1 if it is encrypted
JNIEXPORT jbyteArray JNICALL
Java_org_ddport_darkest_ArchiveInputStream_next(JNIEnv *env, jclass cls, jlong h, jlongArray info) {
    struct archive *a = (struct archive *)(intptr_t)h;
    struct archive_entry *e;
    int r = archive_read_next_header(a, &e);
    if (r == ARCHIVE_EOF)
        return NULL;
    if (r < ARCHIVE_WARN) {   // a warning (such as a name not in UTF-8) still gives the entry
        throw_io(env, a, "Can't read the archive");
        return NULL;
    }
    const char *name = archive_entry_pathname_utf8(e);
    if (!name)
        name = archive_entry_pathname(e);
    if (!name)
        name = "";
    mode_t type = archive_entry_filetype(e);
    jlong v[3] = {
        type == AE_IFREG ? 0 : type == AE_IFDIR ? 1 : 2,
        archive_entry_mtime_is_set(e) ? (jlong)archive_entry_mtime(e) * 1000 + archive_entry_mtime_nsec(e) / 1000000
                                      : -1,
        archive_entry_is_encrypted(e) ? 1 : 0,
    };
    (*env)->SetLongArrayRegion(env, info, 0, 3, v);
    jsize len = (jsize)strlen(name);
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out)
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *)name);
    return out;
}

// read(h, buf, off, len): up to len bytes of the entry's data into buf at off; -1 at its end
JNIEXPORT jint JNICALL
Java_org_ddport_darkest_ArchiveInputStream_read(JNIEnv *env, jclass cls, jlong h, jbyteArray buf, jint off,
                                                jint len) {
    struct archive *a = (struct archive *)(intptr_t)h;
    if (len == 0)
        return 0;
    jbyte *b = (*env)->GetPrimitiveArrayCritical(env, buf, NULL);   // libarchive makes no JNI calls
    la_ssize_t n = archive_read_data(a, b + off, (size_t)len);
    (*env)->ReleasePrimitiveArrayCritical(env, buf, b, n > 0 ? 0 : JNI_ABORT);
    if (n < 0) {
        throw_io(env, a, "Can't unpack the archive");
        return -1;
    }
    return n == 0 ? -1 : (jint)n;
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_ArchiveInputStream_free(JNIEnv *env, jclass cls, jlong h) {
    archive_read_free((struct archive *)(intptr_t)h);
}
