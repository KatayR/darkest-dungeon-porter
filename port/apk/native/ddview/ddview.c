// libddview.so: the app side. Listens on an abstract unix socket for the game process (libddhost),
// gets the game's frame buffers as AHardwareBuffers, and draws each presented frame (plus the
// mouse cursor) onto the SurfaceView with GLES. No CPU copy: frames stay in GPU memory.
// Also owns the cursor position: the Java trackpad sends relative moves, this sends absolute
// positions to the game. Tells the activity when the game wants text (it shows the keyboard).
// Game controllers: the activity's buttons and axes are passed on; whether one is connected is kept
// here, so a game that connects later still learns of it.
// Frame rate: a game frame is shown at most every `refreshes` display refreshes (1 = every one), and
// nothing is swapped twice in one refresh, counted by a vsync thread (ddglue keeps the game from
// drawing ahead, see DD_SURFACE).
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/choreographer.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/looper.h>
#include <android/native_window_jni.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <stddef.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include "../common/ddfence.h"
#include "../common/ddproto.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ddview", __VA_ARGS__)

static int listen_fd = -1, conn = -1, wake[2] = { -1, -1 };
static int game_w, game_h;
static int refreshes = 1;          // display refreshes per game frame
static int since_shown;            // refreshes since a game frame was last shown
static int since_swap;             // refreshes since anything was last swapped (a frame or a cursor move)
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *pending_window;   // set by the UI thread, picked up by the render thread
static int window_changed;
static int pad;                    // a game controller is connected (DD_PAD)

// cursor: position in game pixels, owned here (see file comment)
static float cursor_x, cursor_y;
static int cursor_visible = 1, cursor_w, cursor_h, cursor_hx, cursor_hy, cursor_dirty;
static unsigned char *cursor_pixels;

// the activity, told when the game wants text (DD_TEXT_INPUT)
static JavaVM *vm;
static jobject activity;
static jmethodID on_text_input;

// recent left clicks (cursor height and time), to tell the activity where the text field may be
#define CLICKS    16
#define CLICK_MS  30000   // how long a click counts as recent
static struct { float y; double ms; } clicks[CLICKS];
static int n_clicks;      // ever made; the newest is clicks[(n_clicks - 1) % CLICKS]

static void send_fenced(int type, int a, int b, int c, int fence_fd) {
    dd_msg m = { type, a, b, c, 0 };
    pthread_mutex_lock(&lock);
    if (conn >= 0)
        dd_send(conn, &m, fence_fd);
    pthread_mutex_unlock(&lock);
}

static void send_msg(int type, int a, int b) {
    send_fenced(type, a, b, 0, -1);
}

// ---- GL ----
static EGLDisplay dpy = EGL_NO_DISPLAY;
static EGLConfig config;
static EGLContext ctx = EGL_NO_CONTEXT;
static EGLSurface pbuf = EGL_NO_SURFACE, win_surf = EGL_NO_SURFACE;
static ANativeWindow *window;
static GLuint prog, cursor_tex;
static GLint u_tex, a_pos, a_uv;
static int nbuf, buf_w, buf_h;
static AHardwareBuffer *ahb[DD_BUFFERS];
static EGLImageKHR img[DD_BUFFERS];
static GLuint tex[DD_BUFFERS];
static int shown = -1;            // buffer on screen now
static int held[DD_BUFFERS];      // presented while there was no window: released once one exists
static int latest_held = -1;
static int pending = -1;          // presented, to be drawn at the end of the render pass
static double pending_at;         // when it arrived (ms)
static double waited_ms;          // time the frames shown this second waited to be drawn
static int waited_n;

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getClientBuffer;
static PFNEGLCREATEIMAGEKHRPROC createImage;
static PFNEGLDESTROYIMAGEKHRPROC destroyImage;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTargetTexture;

static GLuint shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    return s;
}

static void gl_init(void) {
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, NULL, NULL);
    const EGLint attrs[] = { EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                             EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                             EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLint n;
    eglChooseConfig(dpy, attrs, &config, 1, &n);
    const EGLint cattrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, cattrs);
    const EGLint pattrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    pbuf = eglCreatePbufferSurface(dpy, config, pattrs);   // keeps the context current without a window
    eglMakeCurrent(dpy, pbuf, pbuf, ctx);
    dd_fence_init();

    getClientBuffer = (void *)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    createImage = (void *)eglGetProcAddress("eglCreateImageKHR");
    destroyImage = (void *)eglGetProcAddress("eglDestroyImageKHR");
    imageTargetTexture = (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES");

    prog = glCreateProgram();
    glAttachShader(prog, shader(GL_VERTEX_SHADER,
        "attribute vec2 pos; attribute vec2 uv; varying vec2 v;"
        "void main() { v = uv; gl_Position = vec4(pos, 0.0, 1.0); }"));
    glAttachShader(prog, shader(GL_FRAGMENT_SHADER,
        "precision mediump float; uniform sampler2D tex; varying vec2 v;"
        "void main() { gl_FragColor = texture2D(tex, v); }"));
    glLinkProgram(prog);
    glUseProgram(prog);
    u_tex = glGetUniformLocation(prog, "tex");
    a_pos = glGetAttribLocation(prog, "pos");
    a_uv = glGetAttribLocation(prog, "uv");
    glUniform1i(u_tex, 0);
    glGenTextures(1, &cursor_tex);
}

static void import_buffer(int i) {
    const EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    img[i] = createImage(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, getClientBuffer(ahb[i]), attrs);
    glGenTextures(1, &tex[i]);
    glBindTexture(GL_TEXTURE_2D, tex[i]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    imageTargetTexture(GL_TEXTURE_2D, img[i]);
    LOG("buffer %d: %dx%d image %p", i, buf_w, buf_h, img[i]);
}

static void apply_window(void) {
    pthread_mutex_lock(&lock);
    ANativeWindow *w = pending_window;
    window_changed = 0;
    pthread_mutex_unlock(&lock);
    if (win_surf != EGL_NO_SURFACE) {
        eglMakeCurrent(dpy, pbuf, pbuf, ctx);
        eglDestroySurface(dpy, win_surf);
        win_surf = EGL_NO_SURFACE;
    }
    if (window)
        ANativeWindow_release(window);
    window = w;
    send_msg(DD_FOCUS, window != NULL, 0);
    if (window) {
        win_surf = eglCreateWindowSurface(dpy, config, window, NULL);
        eglMakeCurrent(dpy, win_surf, win_surf, ctx);
        LOG("window %dx%d", ANativeWindow_getWidth(window), ANativeWindow_getHeight(window));
    }
}

// draws texture t over the game-pixel rectangle (x, y, w, h); y grows downwards like the game's
static void quad(GLuint t, float x, float y, float w, float h, int flip) {
    float l = x / game_w * 2 - 1, r = (x + w) / game_w * 2 - 1;
    float top = 1 - y / game_h * 2, bot = 1 - (y + h) / game_h * 2;
    // game frames come from a GL framebuffer (row 0 = bottom); the cursor bitmap is top-down
    float v0 = flip ? 1 : 0, v1 = flip ? 0 : 1;
    const float verts[] = { l, bot, 0, v0,  r, bot, 1, v0,  l, top, 0, v1,  r, top, 1, v1 };
    glBindTexture(GL_TEXTURE_2D, t);
    glVertexAttribPointer(a_pos, 2, GL_FLOAT, GL_FALSE, 16, verts);
    glVertexAttribPointer(a_uv, 2, GL_FLOAT, GL_FALSE, 16, verts + 2);
    glEnableVertexAttribArray(a_pos);
    glEnableVertexAttribArray(a_uv);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static void draw(int i) {
    int ww = ANativeWindow_getWidth(window), wh = ANativeWindow_getHeight(window);
    glViewport(0, 0, ww, wh);
    glDisable(GL_BLEND);
    quad(tex[i], 0, 0, game_w, game_h, 0);
    if (cursor_dirty) {
        glBindTexture(GL_TEXTURE_2D, cursor_tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, cursor_w, cursor_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, cursor_pixels);
        cursor_dirty = 0;
    }
    if (cursor_visible && cursor_pixels) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        quad(cursor_tex, cursor_x - cursor_hx, cursor_y - cursor_hy, cursor_w, cursor_h, 1);
    }
    eglSwapBuffers(dpy, win_surf);
    since_swap = 0;
}

static void present(int i) {
    static int frames;
    static time_t since;
    if (win_surf == EGL_NO_SURFACE) {
        held[i] = 1;           // keep it: the game stops once all buffers are held
        latest_held = i;
        return;
    }
    draw(i);
    since_shown = 0;
    int done = dd_fence_make(dpy, glFlush);   // the buffers released below have been read once it signals
    for (int j = 0; j < nbuf; j++)
        if (held[j] && j != i) {
            held[j] = 0;
            send_fenced(DD_RELEASE, j, 0, 0, done);
        }
    held[i] = 0;
    latest_held = -1;
    if (shown >= 0 && shown != i)
        send_fenced(DD_RELEASE, shown, 0, 0, done);
    if (done >= 0)
        close(done);
    shown = i;
    frames++;
    time_t now = time(NULL);
    if (now != since) {
        if (since)
            LOG("fps %d, waited %.1f ms", frames, waited_n ? waited_ms / waited_n : 0.0);
        frames = 0;
        waited_ms = 0;
        waited_n = 0;
        since = now;
    }
}

// DDActivity.onTextInput(on, last click, lowest recent click), heights 0..1, from the render thread
// (attached to the VM on first use). The game doesn't say where its text field is; the player has
// clicked it just now, or a little earlier (a save slot, then the dialog that follows).
static void text_input(int on) {
    static JNIEnv *env;
    if (!env && (*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
        env = NULL;
        LOG("cannot attach to the Java VM");
        return;
    }
    float last = cursor_y, lowest = cursor_y;   // no recent click: where the cursor is
    double now = now_ms();
    pthread_mutex_lock(&lock);
    for (int i = n_clicks - 1; i >= 0 && i >= n_clicks - CLICKS && now - clicks[i % CLICKS].ms < CLICK_MS; i--) {
        if (i == n_clicks - 1)
            last = lowest = clicks[i % CLICKS].y;
        else if (clicks[i % CLICKS].y > lowest)
            lowest = clicks[i % CLICKS].y;
    }
    pthread_mutex_unlock(&lock);
    (*env)->CallVoidMethod(env, activity, on_text_input, (jboolean)on, (jfloat)(last / game_h),
                           (jfloat)(lowest / game_h));
}

static void handle(int fd) {
    dd_msg m;
    int fence;
    ssize_t n = dd_recv(fd, &m, &fence);
    if (fence >= 0 && m.type != DD_PRESENT)
        close(fence);   // only DD_PRESENT carries one
    if (n <= 0) {
        LOG("game disconnected");
        pthread_mutex_lock(&lock);
        conn = -1;
        pthread_mutex_unlock(&lock);
        close(fd);
        return;
    }
    switch (m.type) {
    case DD_BUFFERS_MSG:
        nbuf = m.a < DD_BUFFERS ? m.a : DD_BUFFERS;
        buf_w = m.b;
        buf_h = m.c;
        for (int i = 0; i < nbuf; i++) {
            AHardwareBuffer_recvHandleFromUnixSocket(fd, &ahb[i]);
            import_buffer(i);
        }
        break;
    case DD_PRESENT:
        dd_fence_wait(dpy, fence);   // everything drawn from here on waits for the game's copy
        if (m.a < 0 || m.a >= nbuf)
            break;
        if (pending >= 0)
            send_msg(DD_RELEASE, pending, 0);   // newer frame came first: never drawn, nothing reads it
        pending = m.a;                          // drawn at the end of this render pass
        pending_at = now_ms();
        break;
    case DD_CURSOR: {
        size_t size = (size_t)m.a * m.b * 4;
        unsigned char *p = malloc(size);
        if (recv(fd, p, size, 0) == (ssize_t)size) {
            free(cursor_pixels);
            cursor_pixels = p;
            cursor_w = m.a;
            cursor_h = m.b;
            cursor_hx = m.c;
            cursor_hy = m.d;
            cursor_dirty = 1;
        } else
            free(p);
        break;
    }
    case DD_CURSOR_SHOW:
        cursor_visible = m.a;
        break;
    case DD_WARP:
        cursor_x = m.a;
        cursor_y = m.b;
        break;
    case DD_TEXT_INPUT:
        text_input(m.a != 0);
        break;
    }
}

// one "v" on the wake pipe per display refresh
static void on_vsync(int64_t time_ns, void *data) {
    (void)time_ns; (void)data;
    write(wake[1], "v", 1);
    AChoreographer_postFrameCallback64(AChoreographer_getInstance(), on_vsync, NULL);
}

static void *vsync_thread(void *arg) {
    (void)arg;
    ALooper_prepare(0);
    AChoreographer_postFrameCallback64(AChoreographer_getInstance(), on_vsync, NULL);
    for (;;)
        ALooper_pollOnce(-1, NULL, NULL, NULL);
    return NULL;
}

static void *render_thread(void *arg) {
    (void)arg;
    gl_init();
    int redraw = 0;
    for (;;) {
        int fd = conn;
        struct pollfd p[2] = { { wake[0], POLLIN, 0 }, { fd >= 0 ? fd : listen_fd, POLLIN, 0 } };
        poll(p, 2, -1);
        // take in everything that is waiting, then swap at most once per refresh: more swaps would
        // queue up in the window and delay every frame shown after them
        if (p[0].revents) {
            char c[64];
            ssize_t n;
            while ((n = read(wake[0], c, sizeof c)) > 0)
                for (ssize_t k = 0; k < n; k++) {
                    if (c[k] == 'v') {
                        since_shown++;
                        since_swap++;
                    } else
                        redraw = 1;   // new surface or cursor moved
                }
            if (window_changed)
                apply_window();
        }
        if (p[1].revents && fd >= 0) {
            struct pollfd more = { fd, POLLIN, 0 };
            do
                handle(fd);
            while (conn == fd && poll(&more, 1, 0) > 0);
        } else if (p[1].revents) {
            int c = accept(listen_fd, NULL, NULL);
            if (c >= 0) {
                pthread_mutex_lock(&lock);
                conn = c;
                int has_pad = pad;
                pthread_mutex_unlock(&lock);
                LOG("game connected");
                send_fenced(DD_SURFACE, game_w, game_h, refreshes, -1);
                send_msg(DD_MOUSE_MOVE, (int)cursor_x, (int)cursor_y);   // where the drawn cursor starts
                send_msg(DD_FOCUS, window != NULL, 0);
                if (has_pad)
                    send_msg(DD_PAD, 1, 0);
            }
        }
        if (since_swap < 1)
            continue;              // swapped this refresh already: the rest waits for the next one
        if (pending >= 0 && since_shown >= refreshes) {
            int i = pending;
            pending = -1;
            waited_ms += now_ms() - pending_at;
            waited_n++;
            present(i);            // the newest frame, with the cursor where it is now (held if hidden)
            redraw = 0;
        } else if (window && latest_held >= 0)
            present(latest_held);  // frames arrived while hidden: show the newest, free the rest
        else if (redraw && window && shown >= 0) {
            draw(shown);           // show the last frame again
            redraw = 0;
        }
    }
    return NULL;
}

// ---- JNI (org.ddport.darkest.DDActivity) ----
JNIEXPORT jint JNICALL
Java_org_ddport_darkest_DDActivity_nativeStart(JNIEnv *env, jclass cls, jobject act, jstring name, jint w,
                                               jint h, jint frame_refreshes) {
    (*env)->GetJavaVM(env, &vm);
    activity = (*env)->NewGlobalRef(env, act);
    on_text_input = (*env)->GetMethodID(env, cls, "onTextInput", "(ZFF)V");
    game_w = w;
    game_h = h;
    refreshes = frame_refreshes > 1 ? frame_refreshes : 1;
    since_shown = refreshes;   // the first frame is due at once
    since_swap = 1;
    cursor_x = w / 2.0f;
    cursor_y = h / 2.0f;
    const char *s = (*env)->GetStringUTFChars(env, name, NULL);
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    strncpy(addr.sun_path + 1, s, sizeof addr.sun_path - 2);   // abstract: leading NUL
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(s);
    (*env)->ReleaseStringUTFChars(env, name, s);
    listen_fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (bind(listen_fd, (struct sockaddr *)&addr, len) < 0 || listen(listen_fd, 1) < 0) {
        LOG("cannot listen on the game socket");
        return -1;
    }
    pipe(wake);   // non-blocking: read dry each render pass; a full pipe already means "wake up"
    fcntl(wake[0], F_SETFL, O_NONBLOCK);
    fcntl(wake[1], F_SETFL, O_NONBLOCK);
    pthread_t t;
    pthread_create(&t, NULL, render_thread, NULL);
    pthread_create(&t, NULL, vsync_thread, NULL);
    return 0;
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_DDActivity_nativeSetSurface(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    pthread_mutex_lock(&lock);
    if (pending_window && pending_window != window)
        ANativeWindow_release(pending_window);
    pending_window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    window_changed = 1;
    pthread_mutex_unlock(&lock);
    write(wake[1], "w", 1);
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_DDActivity_nativeMoveCursor(JNIEnv *env, jclass cls, jfloat dx, jfloat dy) {
    (void)env; (void)cls;
    cursor_x = cursor_x + dx < 0 ? 0 : cursor_x + dx >= game_w ? game_w - 1 : cursor_x + dx;
    cursor_y = cursor_y + dy < 0 ? 0 : cursor_y + dy >= game_h ? game_h - 1 : cursor_y + dy;
    send_msg(DD_MOUSE_MOVE, (int)cursor_x, (int)cursor_y);
    write(wake[1], "m", 1);   // redraw so the cursor follows even while the game shows a still frame
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_DDActivity_nativeButton(JNIEnv *env, jclass cls, jint button, jboolean down) {
    (void)env; (void)cls;
    if (button == 1 && down) {
        pthread_mutex_lock(&lock);
        clicks[n_clicks % CLICKS].y = cursor_y;
        clicks[n_clicks++ % CLICKS].ms = now_ms();
        pthread_mutex_unlock(&lock);
    }
    send_msg(DD_MOUSE_BUTTON, button, down);
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_DDActivity_nativeKey(JNIEnv *env, jclass cls, jint scancode, jboolean down) {
    (void)env; (void)cls;
    send_msg(DD_KEY, scancode, down);
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_DDActivity_nativeText(JNIEnv *env, jclass cls, jint code_point) {
    (void)env; (void)cls;
    send_msg(DD_TEXT, code_point, 0);
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_DDActivity_nativePad(JNIEnv *env, jclass cls, jboolean connected) {
    (void)env; (void)cls;
    pthread_mutex_lock(&lock);
    pad = connected;
    pthread_mutex_unlock(&lock);
    send_msg(DD_PAD, connected, 0);
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_DDActivity_nativePadButton(JNIEnv *env, jclass cls, jint button, jboolean down) {
    (void)env; (void)cls;
    send_msg(DD_PAD_BUTTON, button, down);
}

JNIEXPORT void JNICALL
Java_org_ddport_darkest_DDActivity_nativePadAxis(JNIEnv *env, jclass cls, jint axis, jint value) {
    (void)env; (void)cls;
    send_msg(DD_PAD_AXIS, axis, value);
}
