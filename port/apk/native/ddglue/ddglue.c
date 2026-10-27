// libddglue.so: sits between box64's wrapped SDL2 and the real SDL2 in the game process (libddhost).
// Its SONAME is libSDL2-2.0.so.0, the name box64 opens; anything not defined here resolves through
// its DT_NEEDED libSDL2.so. The real SDL runs its offscreen video driver (this process has no Java
// side), and this file supplies what the game needs from a real window:
// - GL: an EGL pbuffer with a GLES 2 context, gl4es (libGL.so) on top for the game's desktop GL.
// - Frames: at each swap the pbuffer is copied into one of DD_BUFFERS AHardwareBuffers shared
//   with the app (ddview), which shows it. A buffer is reused once the app releases it, so the
//   app's vsync paces the game. Native fences order the GPU work of both sides (../common/ddfence.h).
// - Window/display size: the size the app asked for (DD_SURFACE).
// - Frame rate: the app shows a frame at most every c refreshes (DD_SURFACE c, 1 = every refresh),
//   and a swap waits until the previous frame is on screen, so the game never draws frames nobody sees.
// - Input: mouse/key/focus messages from the app become SDL events. A press and its release are never
//   delivered in the same game frame (the game would miss the click or key; see sdl-tapfix/tapfix.c).
// - Controller: SDL's joystick subsystem needs Java, so it stays off and this file is the game's one
//   controller: the app's DD_PAD messages become SDL controller events, and the game's controller
//   calls answer from the state those events left (no rumble).
// - Text: SDL_StartTextInput/SDL_StopTextInput open and close the app's keyboard. A typed character
//   becomes SDL_TEXTINPUT plus a press of TEXT_KEY: the game's text fields insert the last text
//   received only when a printable key goes down, one character per frame.
// - Cursor: the game's colour cursors go to the app, which draws them over the frame.
// - Shift screen: the app shows a Shift button while the trinket inventory (Shift-click sells there) or
//   the provisions are open; the panels' state is read from the game's memory (see "the game's state").
// - Walk screen: the app shows arrow buttons, which hold Left/Right, while the party is in a dungeon
//   and not in a battle; read from the game's memory the same way.
// - Entry points that would reach SDL's Java side on Android (joystick/haptic/sensor init, pref
//   path, message box, URL, opening files).
// Protocol: ../common/ddproto.h.
#include "SDL.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include "gl4esinit.h"
#include "../common/ddfence.h"
#include "../common/ddproto.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ddglue", __VA_ARGS__)

void gl4es_pre_swap(void);
void gl4es_post_swap(void);

// ---- real SDL functions that this file overrides ----
static struct {
    int (*Init)(Uint32);
    int (*InitSubSystem)(Uint32);
    SDL_Window *(*CreateWindow)(const char *, int, int, int, int, Uint32);
    int (*PollEvent)(SDL_Event *);
} real;

static void init_real(void) {
    if (real.PollEvent)
        return;
    void *sdl = dlopen("libSDL2.so", RTLD_NOW | RTLD_NOLOAD);
    if (!sdl) {
        LOG("cannot find the real libSDL2.so: %s", dlerror());
        abort();
    }
    real.Init = dlsym(sdl, "SDL_Init");
    real.InitSubSystem = dlsym(sdl, "SDL_InitSubSystem");
    real.CreateWindow = dlsym(sdl, "SDL_CreateWindow");
    real.PollEvent = dlsym(sdl, "SDL_PollEvent");
}

// ---- connection to the app ----
static int sock = -1, width, height;
static int refreshes = 1;   // display refreshes per game frame (DD_SURFACE c)
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static int buffer_free[DD_BUFFERS];
static int buffer_fence[DD_BUFFERS] = { -1, -1, -1 };   // from DD_RELEASE: the app's GPU is done reading
#define INPUT_MAX 256
static dd_msg input[INPUT_MAX];   // ring buffer of input messages not yet turned into events
static int in_head, in_len;
// pressed after each typed character (see the file comment): a key no keyboard has, so no game
// hotkey reacts to it, with a printable keycode, so text fields insert the character
#define TEXT_KEY     SDL_SCANCODE_INTERNATIONAL1
#define TEXT_KEY_SYM SDLK_a

static void send_msg(int type, int a, int b, int c, int d) {
    dd_msg m = { type, a, b, c, d };
    dd_send(sock, &m, -1);
}

static void *reader(void *arg) {
    (void)arg;
    dd_msg m;
    int fence;
    while (dd_recv(sock, &m, &fence) == sizeof m) {
        pthread_mutex_lock(&lock);
        switch (m.type) {
        case DD_SURFACE:
            width = m.a;
            height = m.b;
            refreshes = m.c > 1 ? m.c : 1;
            break;
        case DD_RELEASE:
            if (m.a >= 0 && m.a < DD_BUFFERS) {
                buffer_free[m.a] = 1;
                if (buffer_fence[m.a] >= 0)
                    close(buffer_fence[m.a]);
                buffer_fence[m.a] = fence;
                fence = -1;
            }
            break;
        case DD_TEXT:   // the character, then its key down and up; all or nothing
            if (in_len + 3 <= INPUT_MAX) {
                input[(in_head + in_len++) % INPUT_MAX] = m;
                input[(in_head + in_len++) % INPUT_MAX] = (dd_msg){ DD_KEY, TEXT_KEY, 1, 0, 0 };
                input[(in_head + in_len++) % INPUT_MAX] = (dd_msg){ DD_KEY, TEXT_KEY, 0, 0, 0 };
            }
            break;
        default:
            if (in_len < INPUT_MAX)
                input[(in_head + in_len++) % INPUT_MAX] = m;
        }
        if (fence >= 0)
            close(fence);   // only DD_RELEASE carries one
        pthread_cond_broadcast(&changed);
        pthread_mutex_unlock(&lock);
    }
    LOG("app connection closed");
    exit(0);
}

// connects on first use and waits until the app has said how big the game should render
static void connect_app(void) {
    if (sock >= 0)
        return;
    init_real();
    const char *fd = getenv("DD_SOCKET_FD");
    if (!fd) {
        LOG("DD_SOCKET_FD not set");
        abort();
    }
    sock = atoi(fd);
    pthread_t t;
    pthread_create(&t, NULL, reader, NULL);
    pthread_mutex_lock(&lock);
    while (!width)
        pthread_cond_wait(&changed, &lock);
    pthread_mutex_unlock(&lock);
    LOG("app surface %dx%d, %d refreshes per frame", width, height, refreshes);
}

// ---- subsystems ----
// On Android these talk to SDL's Java side, which this process doesn't have. Controllers come from
// the app instead (see "controller" below).
#define NEEDS_JAVA (SDL_INIT_JOYSTICK | SDL_INIT_GAMECONTROLLER | SDL_INIT_HAPTIC | SDL_INIT_SENSOR)

int SDL_Init(Uint32 flags) {
    init_real();
    SDL_SetMainReady();   // done by SDL's Java side on Android; without it audio init refuses
    return real.Init(flags & ~NEEDS_JAVA);
}

int SDL_InitSubSystem(Uint32 flags) {
    init_real();
    SDL_SetMainReady();
    return real.InitSubSystem(flags & ~NEEDS_JAVA);
}

// ---- display and window ----
static SDL_Window *the_window;

int SDL_GetNumVideoDisplays(void) {
    return 1;
}

int SDL_GetDisplayBounds(int display, SDL_Rect *rect) {
    (void)display;
    connect_app();
    rect->x = rect->y = 0;
    rect->w = width;
    rect->h = height;
    return 0;
}

static int display_mode(SDL_DisplayMode *mode) {
    connect_app();
    mode->format = SDL_PIXELFORMAT_RGB888;
    mode->w = width;
    mode->h = height;
    mode->refresh_rate = 60;
    mode->driverdata = NULL;
    return 0;
}
int SDL_GetDesktopDisplayMode(int display, SDL_DisplayMode *mode) { (void)display; return display_mode(mode); }
int SDL_GetCurrentDisplayMode(int display, SDL_DisplayMode *mode) { (void)display; return display_mode(mode); }

SDL_Window *SDL_CreateWindow(const char *title, int x, int y, int w, int h, Uint32 flags) {
    connect_app();
    LOG("game creates a %dx%d window (flags 0x%x), rendering at %dx%d", w, h, flags, width, height);
    // the offscreen driver can't do GL on Android: the window is only bookkeeping, GL is ours
    // the offscreen driver never focuses a window: focus comes from the app (DD_FOCUS)
    the_window = real.CreateWindow(title, x, y, width, height, flags & ~SDL_WINDOW_OPENGL);
    return the_window;
}

void SDL_GetWindowSize(SDL_Window *window, int *w, int *h) {
    (void)window;
    if (w) *w = width;
    if (h) *h = height;
}

// ---- the game's state (Shift and walk buttons) ----
// The game binary sits at its fixed addresses in this process and keeps its symbols, so the state
// behind the app's buttons is read the way the game's own code reads it. The offsets differ between
// builds (GOG 26186 keeps the trinket panel at +0x2fe0, GOG 27988 and Steam 28212 at +0x2f70), so each
// is taken from the instruction that uses it in the game's code; a build whose code there differs in
// anything else never gets that button. The offsets below are those of 27988 and 28212.
// - The trinket inventory and the provisions (Shift button): the game sells a trinket on Shift-click
//   while the inventory is open, which touch can't do. Read like TownDisplay::GetIsRealmInventoryVisible
//   and GetIsProvisionSelectVisible: TownDisplay::s_Instance (NULL outside the hamlet), the panel at
//   +0x2f70 (provisions: +0x2888), and its state (UI::Panel::Base::GetIsActive: the int at +0x38):
//   0 closed, 1-4 opening or open, 5-7 closing.
// - Walking (arrow buttons): in a dungeon the party walks while Left or Right is held, outside a battle.
//   Read like Raid::IsInBattle: Raid::s_Instance (NULL outside a dungeon), the battle at +0x4778, and
//   its state (Battle::GetState: the int at +0x248), 0 outside a battle. The raid already exists while
//   its loading screen shows, so that is read too, like LoadingScreen::GetIsActive:
//   LoadingScreen::s_Instance and the byte at +0x2cc (set by StartScreen, cleared by FinishScreen).
static char **town_display;   // &TownDisplay::s_Instance; NULL: no Shift button
static char **raid;           // &Raid::s_Instance; NULL: no walk buttons
static char **loading;        // &LoadingScreen::s_Instance (set with raid)
static long trinkets_state;   // the trinket panel's state in TownDisplay (panel + its state); 0: none
static long provisions_state;   // the provision panel's state in TownDisplay; 0: none
static long battle_state_in_raid;   // the battle's state in Raid (battle + its state)
static long loading_flag;     // the loading screen's active byte in LoadingScreen

// the offset in the instruction at addr in the game's code, whose bytes are those of the template
// except the offset's own (size bytes at at, little-endian); -1 if the code there differs
static long code_offset(const unsigned char *f, const Elf64_Shdr *text, Elf64_Addr addr,
                        const unsigned char *bytes, size_t len, size_t at, size_t size) {
    if (addr < text->sh_addr || addr + len > text->sh_addr + text->sh_size)
        return -1;
    const unsigned char *code = f + text->sh_offset + (addr - text->sh_addr);
    if (memcmp(code, bytes, at) || memcmp(code + at + size, bytes + at + size, len - at - size))
        return -1;
    long offset = 0;
    for (size_t i = size; i--;)
        offset = offset << 8 | code[at + i];
    return offset;
}

// finds TownDisplay::s_Instance, Raid::s_Instance and LoadingScreen::s_Instance in the game binary's
// symbols and reads the offsets above from the game's code
static void find_game_state(void) {
    const char *base = getenv("DD_BASE_PATH");
    char path[1024];
    snprintf(path, sizeof path, "%sdarkest.bin.x86_64", base ? base : "./");
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) != 0) {
        LOG("no Shift or walk buttons: cannot open %s: %s", path, strerror(errno));
        if (fd >= 0)
            close(fd);
        return;
    }
    const unsigned char *f = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (f == MAP_FAILED) {
        LOG("no Shift or walk buttons: cannot map %s: %s", path, strerror(errno));
        return;
    }
    // box64 runs this very file, so it is a valid ELF
    const Elf64_Ehdr *eh = (const Elf64_Ehdr *)f;
    const Elf64_Shdr *sh = (const Elf64_Shdr *)(f + eh->e_shoff);
    const char *section_names = (const char *)f + sh[eh->e_shstrndx].sh_offset;
    const Elf64_Shdr *symtab = NULL, *text = NULL;
    for (int i = 0; i < eh->e_shnum; i++)
        if (sh[i].sh_type == SHT_SYMTAB)
            symtab = &sh[i];
        else if (!strcmp(section_names + sh[i].sh_name, ".text"))
            text = &sh[i];
    Elf64_Addr instance = 0, visible = 0, provisions_visible = 0, active = 0, raid_instance = 0, in_battle = 0, battle_state = 0,
               loading_instance = 0, loading_active = 0;
    if (symtab && text) {
        const Elf64_Sym *sym = (const Elf64_Sym *)(f + symtab->sh_offset);
        const char *names = (const char *)f + sh[symtab->sh_link].sh_offset;
        for (size_t i = 0; i < symtab->sh_size / sizeof *sym; i++) {
            const char *s = names + sym[i].st_name;
            if (!strcmp(s, "_ZN6TownUI6Screen11TownDisplay10s_InstanceE"))
                instance = sym[i].st_value;
            else if (!strcmp(s, "_ZNK6TownUI6Screen11TownDisplay26GetIsRealmInventoryVisibleEb"))
                visible = sym[i].st_value;
            else if (!strcmp(s, "_ZNK6TownUI6Screen11TownDisplay27GetIsProvisionSelectVisibleEv"))
                provisions_visible = sym[i].st_value;
            else if (!strcmp(s, "_ZNK2UI5Panel4Base11GetIsActiveEv"))
                active = sym[i].st_value;
            else if (!strcmp(s, "_ZN4Raid10s_InstanceE"))
                raid_instance = sym[i].st_value;
            else if (!strcmp(s, "_ZNK4Raid10IsInBattleEv"))
                in_battle = sym[i].st_value;
            else if (!strcmp(s, "_ZNK6Battle8GetStateEv"))
                battle_state = sym[i].st_value;
            else if (!strcmp(s, "_ZN13LoadingScreen10s_InstanceE"))
                loading_instance = sym[i].st_value;
            else if (!strcmp(s, "_ZNK13LoadingScreen11GetIsActiveEv"))
                loading_active = sym[i].st_value;
        }
    }
    // the instructions that read the state, with 27988's offsets (any offset matches)
    static const unsigned char add_panel[] = { 0x48, 0x81, 0xc7, 0x70, 0x2f, 0x00, 0x00 };    // add $0x2f70,%rdi
    static const unsigned char add_provisions[] = { 0x48, 0x81, 0xc7, 0x88, 0x28, 0x00, 0x00 };   // add $0x2888,%rdi
    static const unsigned char cmp_state[] = { 0x83, 0x7f, 0x38, 0x00 };                      // cmpl $0,0x38(%rdi)
    static const unsigned char add_battle[] = { 0x48, 0x81, 0xc7, 0x78, 0x47, 0x00, 0x00 };   // add $0x4778,%rdi
    static const unsigned char mov_state[] = { 0x8b, 0x87, 0x48, 0x02, 0x00, 0x00 };          // mov 0x248(%rdi),%eax
    static const unsigned char movzb_loading[] = { 0x0f, 0xb6, 0x87, 0xcc, 0x02, 0x00, 0x00 };   // movzbl 0x2cc(%rdi),%eax
#define CODE_OFFSET(addr, bytes, at, size) code_offset(f, text, addr, bytes, sizeof bytes, at, size)
    long panel = -1, provisions = -1, panel_state = -1, battle = -1, state = -1, flag = -1;
    if (instance && active) {
        panel_state = CODE_OFFSET(active, cmp_state, 2, 1);
        if (visible)
            panel = CODE_OFFSET(visible + 8, add_panel, 3, 4);
        if (provisions_visible)
            provisions = CODE_OFFSET(provisions_visible, add_provisions, 3, 4);
    }
    if (raid_instance && in_battle && battle_state && loading_instance && loading_active) {
        battle = CODE_OFFSET(in_battle + 1, add_battle, 3, 4);   // +1: push %rax
        state = CODE_OFFSET(battle_state, mov_state, 2, 4);
        flag = CODE_OFFSET(loading_active, movzb_loading, 3, 4);
    }
#undef CODE_OFFSET
    if (!instance || !visible || !active)
        LOG("no Shift button in the trinket inventory: the game binary lacks its symbols");
    else if (panel < 0 || panel_state < 0)
        LOG("no Shift button in the trinket inventory: this game build reads it differently");
    else {
        town_display = (char **)instance;
        trinkets_state = panel + panel_state;
        LOG("trinket inventory: TownDisplay::s_Instance at 0x%llx, the panel at +0x%lx, its state at +0x%lx",
            (unsigned long long)instance, panel, panel_state);
    }
    if (!instance || !provisions_visible || !active)
        LOG("no Shift button in the provisions: the game binary lacks their symbols");
    else if (provisions < 0 || panel_state < 0)
        LOG("no Shift button in the provisions: this game build reads them differently");
    else {
        town_display = (char **)instance;
        provisions_state = provisions + panel_state;
        LOG("provisions: TownDisplay::s_Instance at 0x%llx, the panel at +0x%lx, its state at +0x%lx",
            (unsigned long long)instance, provisions, panel_state);
    }
    if (!raid_instance || !in_battle || !battle_state || !loading_instance || !loading_active)
        LOG("no walk buttons: the game binary lacks the dungeon's symbols");
    else if (battle < 0 || state < 0 || flag < 0)
        LOG("no walk buttons: this game build reads the battle or loading state differently");
    else {
        raid = (char **)raid_instance;
        loading = (char **)loading_instance;
        battle_state_in_raid = battle + state;
        loading_flag = flag;
        LOG("dungeon: Raid::s_Instance at 0x%llx, the battle at +0x%lx, its state at +0x%lx; "
            "LoadingScreen::s_Instance at 0x%llx, its flag at +0x%lx",
            (unsigned long long)raid_instance, battle, state, (unsigned long long)loading_instance, flag);
    }
    munmap((void *)f, st.st_size);
}

// whether the panel whose state is at offset state in TownDisplay is opening or open (0: no such panel)
static int panel_open(const char *town, long state) {
    int s = state ? *(const int *)(town + state) : 0;
    return s >= 1 && s <= 4;
}

// tells the app when the trinket inventory or the provisions open or close, and when the party can
// start or stop walking (in a dungeon, outside a battle and its loading screen); at each swap, on the
// game's thread
static void check_game_state(void) {
    static int looked, open, walk;
    if (!looked) {
        looked = 1;
        find_game_state();
    }
    if (town_display) {
        char *town = *town_display;
        int now = town && (panel_open(town, trinkets_state) || panel_open(town, provisions_state));
        if (now != open) {
            open = now;
            LOG("Shift button %s", open ? "shown" : "hidden");
            send_msg(DD_SHIFT_SCREEN, open, 0, 0, 0);
        }
    }
    if (raid) {
        char *r = *raid, *l = *loading;
        int now = r && *(int *)(r + battle_state_in_raid) == 0 && !(l && l[loading_flag]);
        if (now != walk) {
            walk = now;
            LOG("walk buttons %s", walk ? "shown" : "hidden");
            send_msg(DD_WALK_SCREEN, walk, 0, 0, 0);
        }
    }
}

// ---- GL ----
static EGLDisplay dpy = EGL_NO_DISPLAY;
static EGLContext ctx = EGL_NO_CONTEXT;
static EGLSurface pbuf = EGL_NO_SURFACE;
static GLuint tex[DD_BUFFERS];
static struct {   // raw GLES (libGLESv2), not gl4es's same-named desktop-GL functions
    void (*GetIntegerv)(GLenum, GLint *);
    void (*ActiveTexture)(GLenum);
    void (*BindTexture)(GLenum, GLuint);
    void (*BindFramebuffer)(GLenum, GLuint);
    void (*GenTextures)(GLsizei, GLuint *);
    void (*TexParameteri)(GLenum, GLenum, GLint);
    void (*CopyTexSubImage2D)(GLenum, GLint, GLint, GLint, GLint, GLint, GLsizei, GLsizei);
    void (*Flush)(void);
    void (*Finish)(void);
} gles;
static void *libgles;

static void *gles_proc(const char *name) {
    void *p = dlsym(libgles, name);
    return p ? p : (void *)eglGetProcAddress(name);
}

static void main_fb_size(int *w, int *h) {
    *w = width;
    *h = height;
}

static void create_buffers(void) {
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC client_buffer = (void *)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    PFNEGLCREATEIMAGEKHRPROC create_image = (void *)eglGetProcAddress("eglCreateImageKHR");
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC target_texture = (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    AHardwareBuffer *ahb[DD_BUFFERS];
    AHardwareBuffer_Desc desc = {
        .width = width, .height = height, .layers = 1, .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
    };
    GLint old;
    gles.GetIntegerv(GL_TEXTURE_BINDING_2D, &old);
    gles.GenTextures(DD_BUFFERS, tex);
    for (int i = 0; i < DD_BUFFERS; i++) {
        if (AHardwareBuffer_allocate(&desc, &ahb[i]) != 0) {
            LOG("AHardwareBuffer_allocate failed");
            abort();
        }
        const EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
        EGLImageKHR img = create_image(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client_buffer(ahb[i]), attrs);
        gles.BindTexture(GL_TEXTURE_2D, tex[i]);
        gles.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        gles.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        target_texture(GL_TEXTURE_2D, img);
        buffer_free[i] = 1;
    }
    gles.BindTexture(GL_TEXTURE_2D, old);
    send_msg(DD_BUFFERS_MSG, DD_BUFFERS, width, height, 0);
    for (int i = 0; i < DD_BUFFERS; i++)
        AHardwareBuffer_sendHandleToUnixSocket(ahb[i], sock);
    LOG("%d shared %dx%d buffers sent to the app", DD_BUFFERS, width, height);
}

// the framebuffer the game asked for (SDL_GL_GetAttribute would query GL, which isn't there yet)
static int depth, stencil;

int SDL_GL_SetAttribute(SDL_GLattr attr, int value) {
    if (attr == SDL_GL_DEPTH_SIZE)
        depth = value;
    else if (attr == SDL_GL_STENCIL_SIZE)
        stencil = value;
    return 0;
}

int SDL_GL_LoadLibrary(const char *path) {
    (void)path;
    return 0;
}

SDL_GLContext SDL_GL_CreateContext(SDL_Window *window) {
    (void)window;
    connect_app();
    if (ctx != EGL_NO_CONTEXT)
        return ctx;   // the game only needs one; it is already current
    libgles = dlopen("libGLESv2.so", RTLD_NOW);
#define GET(f) gles.f = gles_proc("gl" #f)
    GET(GetIntegerv); GET(ActiveTexture); GET(BindTexture); GET(BindFramebuffer);
    GET(GenTextures); GET(TexParameteri); GET(CopyTexSubImage2D); GET(Flush); GET(Finish);
#undef GET
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, NULL, NULL);
    dd_fence_init();
    const EGLint attrs[] = { EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                             EGL_DEPTH_SIZE, depth, EGL_STENCIL_SIZE, stencil,
                             EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLConfig config;
    EGLint n = 0;
    if (!eglChooseConfig(dpy, attrs, &config, 1, &n) || n < 1) {
        LOG("no EGL config (depth %d stencil %d)", depth, stencil);
        return NULL;
    }
    const EGLint pattrs[] = { EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE };
    pbuf = eglCreatePbufferSurface(dpy, config, pattrs);
    const EGLint cattrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, cattrs);
    if (pbuf == EGL_NO_SURFACE || ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, pbuf, pbuf, ctx)) {
        LOG("EGL setup failed: 0x%x", eglGetError());
        return NULL;
    }
    set_getprocaddress(gles_proc);
    set_getmainfbsize(main_fb_size);
    initialize_gl4es();
    create_buffers();
    LOG("GLES 2 context %dx%d (depth %d stencil %d), gl4es initialised", width, height, depth, stencil);
    return ctx;
}

int SDL_GL_MakeCurrent(SDL_Window *window, SDL_GLContext context) {
    (void)window;
    return eglMakeCurrent(dpy, context ? pbuf : EGL_NO_SURFACE, context ? pbuf : EGL_NO_SURFACE,
                          context ? ctx : EGL_NO_CONTEXT) ? 0 : -1;
}

SDL_GLContext SDL_GL_GetCurrentContext(void) {
    return eglGetCurrentContext() == EGL_NO_CONTEXT ? NULL : ctx;
}

SDL_Window *SDL_GL_GetCurrentWindow(void) {
    return the_window;
}

void SDL_GL_DeleteContext(SDL_GLContext context) {
    (void)context;   // lives as long as the process
}

void *SDL_GL_GetProcAddress(const char *proc) {
    return gl4es_GetProcAddress(proc);
}

void SDL_GL_GetDrawableSize(SDL_Window *window, int *w, int *h) {
    SDL_GetWindowSize(window, w, h);
}

int SDL_GL_SetSwapInterval(int interval) {
    (void)interval;   // the app presents with vsync
    return 0;
}

int SDL_GL_GetSwapInterval(void) {
    return 1;
}

void SDL_GL_SwapWindow(SDL_Window *window) {
    (void)window;
    check_game_state();
    gl4es_pre_swap();
    // a free buffer, and the previous frame on screen (the app then holds only that one)
    int i = 0, need = DD_BUFFERS - 1;
    pthread_mutex_lock(&lock);
    for (;;) {
        int nfree = 0;
        for (int j = 0; j < DD_BUFFERS; j++)
            if (buffer_free[j]) {
                nfree++;
                i = j;
            }
        if (nfree >= need)
            break;
        pthread_cond_wait(&changed, &lock);
    }
    buffer_free[i] = 0;
    int released = buffer_fence[i];
    buffer_fence[i] = -1;
    pthread_mutex_unlock(&lock);
    dd_fence_wait(dpy, released);   // the copy below runs once the app's GPU has read the old frame

    // copy the finished frame into the shared buffer, leaving gl4es's view of the GL state intact
    GLint fb, active, bound;
    gles.GetIntegerv(GL_FRAMEBUFFER_BINDING, &fb);
    gles.GetIntegerv(GL_ACTIVE_TEXTURE, &active);
    gles.ActiveTexture(GL_TEXTURE0);
    gles.GetIntegerv(GL_TEXTURE_BINDING_2D, &bound);
    gles.BindFramebuffer(GL_FRAMEBUFFER, 0);
    gles.BindTexture(GL_TEXTURE_2D, tex[i]);
    gles.CopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
    gles.BindTexture(GL_TEXTURE_2D, bound);
    gles.ActiveTexture(active);
    gles.BindFramebuffer(GL_FRAMEBUFFER, fb);
    // the app's GPU waits for the copy; without a fence, wait for it here
    int done = dd_fence_make(dpy, gles.Flush);
    if (done < 0) {
        static int logged;
        if (!logged++)
            LOG("no native fence: glFinish at every swap");
        gles.Finish();
    }
    dd_msg m = { DD_PRESENT, i, 0, 0, 0 };
    dd_send(sock, &m, done);
    if (done >= 0)
        close(done);
    gl4es_post_swap();

    static Uint32 since;
    static int frames;
    Uint32 now = SDL_GetTicks();
    frames++;
    if (!since)
        since = now;
    else if (now - since >= 1000) {
        LOG("fps %.1f", frames * 1000.0f / (now - since));
        frames = 0;
        since = now;
    }
}

// ---- input ----
// Events come first from the real SDL (window/quit events), then from the app's messages.
static Uint32 buttons;   // SDL_BUTTON_LMASK etc.
static int mouse_x, mouse_y;

static SDL_Event second;   // the other half of a message that becomes two events
static int has_second;

static int next_event(SDL_Event *e) {
    if (has_second) {
        *e = second;
        has_second = 0;
        return 1;
    }
    if (real.PollEvent(e))
        return 1;
    pthread_mutex_lock(&lock);
    if (!in_len) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    dd_msg m = input[in_head];
    in_head = (in_head + 1) % INPUT_MAX;
    in_len--;
    pthread_mutex_unlock(&lock);

    memset(e, 0, sizeof *e);
    Uint32 id = the_window ? SDL_GetWindowID(the_window) : 0;
    switch (m.type) {
    case DD_MOUSE_MOVE:
        e->type = SDL_MOUSEMOTION;
        e->motion.windowID = id;
        e->motion.state = buttons;
        e->motion.xrel = m.a - mouse_x;
        e->motion.yrel = m.b - mouse_y;
        e->motion.x = mouse_x = m.a;
        e->motion.y = mouse_y = m.b;
        break;
    case DD_MOUSE_BUTTON:
        e->type = m.b ? SDL_MOUSEBUTTONDOWN : SDL_MOUSEBUTTONUP;
        e->button.windowID = id;
        e->button.button = m.a;
        e->button.state = m.b ? SDL_PRESSED : SDL_RELEASED;
        e->button.clicks = 1;
        e->button.x = mouse_x;
        e->button.y = mouse_y;
        if (m.b)
            buttons |= SDL_BUTTON(m.a);
        else
            buttons &= ~SDL_BUTTON(m.a);
        break;
    case DD_FOCUS:
        e->type = SDL_WINDOWEVENT;
        e->window.windowID = id;
        e->window.event = m.a ? SDL_WINDOWEVENT_FOCUS_GAINED : SDL_WINDOWEVENT_FOCUS_LOST;
        break;
    case DD_KEY:
        e->type = m.b ? SDL_KEYDOWN : SDL_KEYUP;
        e->key.windowID = id;
        e->key.state = m.b ? SDL_PRESSED : SDL_RELEASED;
        e->key.keysym.scancode = m.a;
        e->key.keysym.sym = m.a == TEXT_KEY ? TEXT_KEY_SYM : SDL_GetKeyFromScancode(m.a);
        break;
    case DD_TEXT: {   // one code point as UTF-8
        unsigned c = m.a;
        char *t = e->text.text;
        if (c < 0x80) {
            t[0] = c;
        } else if (c < 0x800) {
            t[0] = 0xC0 | c >> 6;
            t[1] = 0x80 | (c & 0x3F);
        } else if (c < 0x10000) {
            t[0] = 0xE0 | c >> 12;
            t[1] = 0x80 | (c >> 6 & 0x3F);
            t[2] = 0x80 | (c & 0x3F);
        } else {
            t[0] = 0xF0 | c >> 18;
            t[1] = 0x80 | (c >> 12 & 0x3F);
            t[2] = 0x80 | (c >> 6 & 0x3F);
            t[3] = 0x80 | (c & 0x3F);
        }
        e->type = SDL_TEXTINPUT;
        e->text.windowID = id;
        break;
    }
    case DD_PAD:   // as in SDL: a joystick event, then a controller one, both for device 0
        LOG("controller %s", m.a ? "connected" : "disconnected");
        e->type = m.a ? SDL_JOYDEVICEADDED : SDL_JOYDEVICEREMOVED;
        second.cdevice.type = m.a ? SDL_CONTROLLERDEVICEADDED : SDL_CONTROLLERDEVICEREMOVED;
        second.cdevice.timestamp = SDL_GetTicks();
        second.cdevice.which = 0;
        has_second = 1;
        break;
    case DD_PAD_BUTTON:
        if (m.a < 0 || m.a >= SDL_CONTROLLER_BUTTON_MAX)
            return next_event(e);
        e->type = m.b ? SDL_CONTROLLERBUTTONDOWN : SDL_CONTROLLERBUTTONUP;
        e->cbutton.button = m.a;
        e->cbutton.state = m.b ? SDL_PRESSED : SDL_RELEASED;
        break;
    case DD_PAD_AXIS:
        if (m.a < 0 || m.a >= SDL_CONTROLLER_AXIS_MAX)
            return next_event(e);
        e->type = SDL_CONTROLLERAXISMOTION;
        e->caxis.axis = m.a;
        e->caxis.value = m.b;
        break;
    default:
        return next_event(e);
    }
    e->common.timestamp = SDL_GetTicks();
    return 1;
}

// ---- controller ----
// One controller, device 0, whose handles are these addresses. Its state changes as its events reach
// the game, so polling it agrees with the events. Every call that takes a handle is answered here:
// the real SDL never sees one. SDL_GameControllerEventState and SDL_GameControllerUpdate stay SDL's
// (they only set event states, and update nothing without the joystick subsystem).
static char pad_handle, pad_joystick;
static int pad_attached;
static Uint8 pad_buttons[SDL_CONTROLLER_BUTTON_MAX];
static Sint16 pad_axes[SDL_CONTROLLER_AXIS_MAX];
#define PAD ((SDL_GameController *)&pad_handle)

// applies a controller event to the state; 1 if the game ignores events of its type
static int pad_event(const SDL_Event *e) {
    switch (e->type) {
    case SDL_JOYDEVICEADDED:
    case SDL_CONTROLLERDEVICEADDED:
        pad_attached = 1;
        break;
    case SDL_JOYDEVICEREMOVED:
    case SDL_CONTROLLERDEVICEREMOVED:
        pad_attached = 0;
        memset(pad_buttons, 0, sizeof pad_buttons);
        memset(pad_axes, 0, sizeof pad_axes);
        break;
    case SDL_CONTROLLERBUTTONDOWN:
    case SDL_CONTROLLERBUTTONUP:
        pad_buttons[e->cbutton.button] = e->cbutton.state;
        break;
    case SDL_CONTROLLERAXISMOTION:
        pad_axes[e->caxis.axis] = e->caxis.value;
        break;
    default:
        return 0;
    }
    return SDL_EventState(e->type, SDL_QUERY) == SDL_IGNORE;
}

int SDL_NumJoysticks(void) {
    return pad_attached;
}

SDL_bool SDL_IsGameController(int index) {
    return index == 0 && pad_attached;
}

SDL_GameController *SDL_GameControllerOpen(int index) {
    if (!SDL_IsGameController(index)) {
        SDL_SetError("There are %d joysticks available", pad_attached);
        return NULL;
    }
    LOG("game opens the controller");
    return PAD;
}

void SDL_GameControllerClose(SDL_GameController *c) {
    (void)c;
}

SDL_bool SDL_GameControllerGetAttached(SDL_GameController *c) {
    return c == PAD && pad_attached;
}

Uint8 SDL_GameControllerGetButton(SDL_GameController *c, SDL_GameControllerButton b) {
    return c == PAD && b >= 0 && b < SDL_CONTROLLER_BUTTON_MAX ? pad_buttons[b] : 0;
}

Sint16 SDL_GameControllerGetAxis(SDL_GameController *c, SDL_GameControllerAxis a) {
    return c == PAD && a >= 0 && a < SDL_CONTROLLER_AXIS_MAX ? pad_axes[a] : 0;
}

SDL_Joystick *SDL_GameControllerGetJoystick(SDL_GameController *c) {
    return c == PAD ? (SDL_Joystick *)&pad_joystick : NULL;
}

int SDL_JoystickIsHaptic(SDL_Joystick *j) {
    (void)j;
    return 0;
}

SDL_Haptic *SDL_HapticOpenFromJoystick(SDL_Joystick *j) {
    (void)j;
    SDL_SetError("Haptic: Joystick isn't a haptic device.");
    return NULL;
}

// ---- polling ----
// tapfix (see port/phone/sdl-tapfix/tapfix.c): the game folds events into per-frame state, so a
// press and release drained in the same frame cancel out. A release that arrives in the frame of
// its press is parked (with everything after it) until MIN_FRAMES frames and MIN_HOLD_MS passed.
// Keys and controller buttons too: Android sends a tapped Back's down and up together.
#define QUEUE_MAX   256
#define MAX_BUTTONS 8
// mouse buttons, then keys by scancode, then controller buttons
#define MAX_PRESS   (MAX_BUTTONS + SDL_NUM_SCANCODES + SDL_CONTROLLER_BUTTON_MAX)
#define MIN_FRAMES  2
#define MIN_HOLD_MS 60
static SDL_Event queue[QUEUE_MAX];
static int q_head, q_len;
static unsigned frame;                   // number of times SDL_PollEvent returned 0
static int down[MAX_PRESS];
static unsigned press_frame[MAX_PRESS];
static Uint32 press_ms[MAX_PRESS];
static int held = -1;                    // press whose release sits at the queue head, or -1

// the down[] index of a press or release event, or -1
static int press_of(const SDL_Event *e) {
    if (e->type == SDL_MOUSEBUTTONDOWN || e->type == SDL_MOUSEBUTTONUP)
        return e->button.button < MAX_BUTTONS ? e->button.button : -1;
    if (e->type == SDL_KEYDOWN || e->type == SDL_KEYUP)
        return e->key.keysym.scancode < SDL_NUM_SCANCODES ? MAX_BUTTONS + (int)e->key.keysym.scancode : -1;
    if (e->type == SDL_CONTROLLERBUTTONDOWN || e->type == SDL_CONTROLLERBUTTONUP)
        return MAX_BUTTONS + SDL_NUM_SCANCODES + e->cbutton.button;   // checked in next_event
    return -1;
}

static int end_frame(void) {
    frame++;
    return 0;
}

// the next event for the game, after tapfix
static int next_for_game(SDL_Event *ev) {
    if (held >= 0) {
        SDL_Event e;
        while (q_len < QUEUE_MAX && next_event(&e))
            queue[(q_head + q_len++) % QUEUE_MAX] = e;
        if (frame - press_frame[held] < MIN_FRAMES || SDL_GetTicks() - press_ms[held] < MIN_HOLD_MS)
            return end_frame();
        held = -1;
    }
    SDL_Event e;
    if (q_len > 0) {
        e = queue[q_head];
        q_head = (q_head + 1) % QUEUE_MAX;
        q_len--;
    } else if (!next_event(&e))
        return end_frame();

    int b = press_of(&e);
    if (b >= 0) {
        if (e.type == SDL_MOUSEBUTTONDOWN || e.type == SDL_KEYDOWN || e.type == SDL_CONTROLLERBUTTONDOWN) {
            down[b] = 1;
            press_frame[b] = frame;
            press_ms[b] = SDL_GetTicks();
        } else if (down[b] && press_frame[b] == frame) {
            q_head = (q_head + QUEUE_MAX - 1) % QUEUE_MAX;   // park it at the front
            queue[q_head] = e;
            q_len++;
            held = b;
            return end_frame();
        } else {
            down[b] = 0;
        }
    }
    *ev = e;
    return 1;
}

int SDL_PollEvent(SDL_Event *ev) {
    connect_app();
    if (!ev) {
        if (held < 0 && q_len > 0)
            return 1;
        SDL_Event e;
        if (!next_event(&e))
            return 0;
        queue[(q_head + q_len++) % QUEUE_MAX] = e;   // keep it for the next real poll
        return 1;
    }
    while (next_for_game(ev))
        if (!pad_event(ev))
            return 1;
    return 0;
}

// as in SDL: the keyboard shows only if the hint allows it. SDL's own video init starts text input
// with the hint off, so the keyboard doesn't pop up at launch.
void SDL_StartTextInput(void) {
    if (!SDL_GetHintBoolean(SDL_HINT_ENABLE_SCREEN_KEYBOARD, SDL_TRUE))
        return;
    connect_app();
    send_msg(DD_TEXT_INPUT, 1, 0, 0, 0);
}

void SDL_StopTextInput(void) {
    connect_app();
    send_msg(DD_TEXT_INPUT, 0, 0, 0, 0);
}

void SDL_WarpMouseInWindow(SDL_Window *window, int x, int y) {
    (void)window;
    connect_app();
    send_msg(DD_WARP, x, y, 0, 0);
    dd_msg m = { DD_MOUSE_MOVE, x, y, 0, 0 };   // SDL reports a warp as motion too
    pthread_mutex_lock(&lock);
    if (in_len < INPUT_MAX)
        input[(in_head + in_len++) % INPUT_MAX] = m;
    pthread_mutex_unlock(&lock);
}

// ---- cursor ----
typedef struct {
    int w, h, hx, hy;
    void *rgba;
} Cursor;
static Cursor *current_cursor;
static int cursor_shown = 1;

SDL_Cursor *SDL_CreateColorCursor(SDL_Surface *surface, int hot_x, int hot_y) {
    SDL_Surface *s = SDL_ConvertSurfaceFormat(surface, SDL_PIXELFORMAT_RGBA32, 0);
    if (!s)
        return NULL;
    Cursor *c = malloc(sizeof *c);
    c->w = s->w;
    c->h = s->h;
    c->hx = hot_x;
    c->hy = hot_y;
    c->rgba = malloc((size_t)s->w * s->h * 4);
    for (int y = 0; y < s->h; y++)
        memcpy((char *)c->rgba + (size_t)y * s->w * 4, (char *)s->pixels + (size_t)y * s->pitch, (size_t)s->w * 4);
    SDL_FreeSurface(s);
    return (SDL_Cursor *)c;
}

void SDL_SetCursor(SDL_Cursor *cursor) {
    connect_app();
    Cursor *c = cursor ? (Cursor *)cursor : current_cursor;
    if (!c)
        return;
    current_cursor = c;
    send_msg(DD_CURSOR, c->w, c->h, c->hx, c->hy);
    send(sock, c->rgba, (size_t)c->w * c->h * 4, MSG_NOSIGNAL);
}

void SDL_FreeCursor(SDL_Cursor *cursor) {
    Cursor *c = (Cursor *)cursor;
    if (!c)
        return;
    if (c == current_cursor)
        current_cursor = NULL;
    free(c->rgba);
    free(c);
}

int SDL_ShowCursor(int toggle) {
    if (toggle != SDL_QUERY) {
        connect_app();
        cursor_shown = toggle == SDL_ENABLE;
        send_msg(DD_CURSOR_SHOW, cursor_shown, 0, 0, 0);
    }
    return cursor_shown ? SDL_ENABLE : SDL_DISABLE;
}

// ---- files, paths and things that would need SDL's Java side ----
// SDL on Android opens relative (or missing) files through Java's asset manager: plain files here
SDL_RWops *SDL_RWFromFile(const char *file, const char *mode) {
    FILE *fp = fopen(file, mode);
    if (!fp) {
        SDL_SetError("Couldn't open %s: %s", file, strerror(errno));
        return NULL;
    }
    return SDL_RWFromFP(fp, SDL_TRUE);
}

char *SDL_GetBasePath(void) {
    const char *base = getenv("DD_BASE_PATH");
    return SDL_strdup(base ? base : "./");
}

char *SDL_GetPrefPath(const char *org, const char *app) {
    // same place SDL picks on Linux: $HOME/.local/share/<org>/<app>/
    char path[1024];
    snprintf(path, sizeof path, "%s/.local/share/%s/%s/", getenv("HOME"), org ? org : "", app ? app : "");
    for (char *p = path + 1; *p; p++)
        if (*p == '/') {
            *p = 0;
            mkdir(path, 0700);
            *p = '/';
        }
    return SDL_strdup(path);
}

int SDL_ShowSimpleMessageBox(Uint32 flags, const char *title, const char *message, SDL_Window *window) {
    (void)flags; (void)window;
    LOG("message box: %s: %s", title, message);
    fprintf(stderr, "message box: %s: %s\n", title, message);
    return 0;
}

int SDL_OpenURL(const char *url) {
    LOG("game wants to open %s (ignored)", url);
    return 0;
}
