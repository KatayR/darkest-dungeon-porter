// libddhost.so: an executable (named lib*.so so the APK installs it executable in nativeLibraryDir).
// DDActivity execs it as the game process: a fresh address space, unlike the app's, where ART's heap
// sits on the fixed addresses the non-PIE game needs. Sets up the environment for box64, gl4es,
// SDL and the game, connects to the app (ddview), then runs the x86_64 game through box64_run().
// From DDActivity:
//   DD_GAME_DIR   game root (the folder holding _linuxnosteam/): DDActivity's private mirror of the
//                 game copy on external storage (which isn't executable)
//   DD_HOME       app-private dir: HOME (saves, box64 cache), x64lib/
//   DD_NATIVE_DIR the APK's native libs (gl4es libGL.so)
//   DD_SOCKET     abstract unix socket name ddview listens on
// stdout/stderr (game + box64 logs) are redirected to $DD_HOME/dd.log by DDActivity.
#include <dlfcn.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int box64_run(int argc, const char **argv, char **env);
extern char **environ;

static char *join(const char *a, const char *b) {
    char *s = malloc(strlen(a) + strlen(b) + 1);
    strcpy(s, a);
    return strcat(s, b);
}

static int connect_app(const char *name) {
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    strncpy(addr.sun_path + 1, name, sizeof addr.sun_path - 2);   // abstract: leading NUL
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name);
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    return connect(fd, (struct sockaddr *)&addr, len) == 0 ? fd : -1;
}

int main(void) {
    prctl(PR_SET_PDEATHSIG, SIGKILL);   // die with the app
    setvbuf(stdout, NULL, _IOLBF, 0);
    const char *game = getenv("DD_GAME_DIR"), *home = getenv("DD_HOME");
    const char *native = getenv("DD_NATIVE_DIR"), *sock = getenv("DD_SOCKET");
    if (!game || !home || !native || !sock) {
        fprintf(stderr, "ddhost: DD_GAME_DIR / DD_HOME / DD_NATIVE_DIR / DD_SOCKET not set\n");
        return 1;
    }
    int fd = connect_app(sock);
    if (fd < 0) {
        fprintf(stderr, "ddhost: cannot connect to the app on %s\n", sock);
        return 1;
    }
    char fdstr[16];
    snprintf(fdstr, sizeof fdstr, "%d", fd);
    setenv("DD_SOCKET_FD", fdstr, 1);   // for ddglue

    setenv("HOME", home, 1);  // the game keeps saves under $HOME/.local/share; with HOME=/ it crashes
    setenv("BOX64_LD_LIBRARY_PATH", join(join(game, "/_linuxnosteam/lib64:"), join(home, "/x64lib")), 1);
    setenv("BOX64_LIBGL", join(native, "/libGL.so"), 1);
    setenv("BOX64_LOG", "1", 0);
    setenv("DD_BASE_PATH", join(game, "/_linuxnosteam/"), 1);
    setenv("SDL_VIDEODRIVER", "offscreen", 1);   // ddglue does the GL and the window; no Java here
    setenv("SDL_AUDIODRIVER", "openslES", 0);   // SDL's aaudio backend lists devices through Java
    setenv("LIBGL_ES", "2", 1);
    setenv("LIBGL_GL", "21", 1);
    setenv("LIBGL_NOBANNER", "1", 1);
    setenv("LIBGL_SILENTSTUB", "1", 1);
    setenv("LIBGL_NOPSA", "1", 1);
    setenv("LIBGL_LOGSHADERERROR", "1", 1);
    // the game streams its vertices through a mapped ring buffer (unsynchronized writes); gl4es would
    // update a real GLES buffer per flush, which Adreno copies whole each time. Client arrays instead
    // (each draw passes only the vertex range it uses: patches/gl4es-client-array-range.patch)
    setenv("LIBGL_USEVBO", "0", 1);

    if (chdir(game) != 0) {
        fprintf(stderr, "ddhost: cannot enter game dir %s\n", game);
        return 1;
    }
    // the glue's SONAME is libSDL2-2.0.so.0, the name box64 opens: once loaded, that dlopen finds it.
    // (Linking it instead would make the loader look for a file called libSDL2-2.0.so.0.)
    if (!dlopen(join(native, "/libddglue.so"), RTLD_NOW | RTLD_GLOBAL)) {
        fprintf(stderr, "ddhost: cannot load libddglue.so: %s\n", dlerror());
        return 1;
    }
    // the same for ALSA: libddaudio.so's SONAME is libasound.so.2, which the game's FMOD opens
    if (!dlopen(join(native, "/libddaudio.so"), RTLD_NOW | RTLD_GLOBAL)) {
        fprintf(stderr, "ddhost: cannot load libddaudio.so: %s\n", dlerror());
        return 1;
    }
    const char *args[] = { "box64", join(game, "/_linuxnosteam/darkest.bin.x86_64"), NULL };
    fprintf(stderr, "ddhost: starting %s\n", args[1]);
    return box64_run(2, args, environ);
}
