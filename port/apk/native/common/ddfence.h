// Native fence fds (EGL_ANDROID_native_fence_sync) for the frames shared between the game (ddglue)
// and the app (ddview): one side makes a fence after its GPU work on a buffer, the other makes its
// GPU wait for it. Neither CPU blocks. Call dd_fence_init() once an EGL display is initialised.
#pragma once
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <poll.h>
#include <unistd.h>

static PFNEGLCREATESYNCKHRPROC dd_create_sync;
static PFNEGLDESTROYSYNCKHRPROC dd_destroy_sync;
static PFNEGLWAITSYNCKHRPROC dd_wait_sync;
static PFNEGLDUPNATIVEFENCEFDANDROIDPROC dd_dup_fence;

static inline void dd_fence_init(void) {
    dd_create_sync = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
    dd_destroy_sync = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");
    dd_wait_sync = (PFNEGLWAITSYNCKHRPROC)eglGetProcAddress("eglWaitSyncKHR");
    dd_dup_fence = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)eglGetProcAddress("eglDupNativeFenceFDANDROID");
}

// a fence fd that signals once the GPU has run everything submitted so far, or -1 if there is none
// (the caller then has to glFinish). flush is the raw GLES glFlush: the fd exists once it has run
static inline int dd_fence_make(EGLDisplay dpy, void (*flush)(void)) {
    if (!dd_create_sync || !dd_destroy_sync || !dd_dup_fence)
        return -1;
    EGLSyncKHR s = dd_create_sync(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, NULL);
    if (s == EGL_NO_SYNC_KHR)
        return -1;
    flush();
    int fd = dd_dup_fence(dpy, s);   // EGL_NO_NATIVE_FENCE_FD_ANDROID is -1
    dd_destroy_sync(dpy, s);
    return fd;
}

// makes the GPU wait for fence_fd before anything submitted after this; takes the fd.
// Without GPU-side waits it blocks until the fence signals
static inline void dd_fence_wait(EGLDisplay dpy, int fence_fd) {
    if (fence_fd < 0)
        return;
    const EGLint attrs[] = { EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fence_fd, EGL_NONE };
    EGLSyncKHR s = dd_create_sync && dd_wait_sync ? dd_create_sync(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs)
                                                  : EGL_NO_SYNC_KHR;
    if (s == EGL_NO_SYNC_KHR) {   // the fd is still ours
        struct pollfd p = { fence_fd, POLLIN, 0 };
        poll(&p, 1, -1);
        close(fence_fd);
        return;
    }
    dd_wait_sync(dpy, s, 0);   // the sync owns the fd now
    dd_destroy_sync(dpy, s);
}
