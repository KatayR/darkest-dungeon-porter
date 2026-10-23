// Loads libbox64.so (BOX64_SHARED build) with dlopen and runs an x86_64 program through box64_run(),
// the way libddhost does. Tests the shared-library build without an APK.
// Usage: box64-lib-runner /path/libbox64.so ./program [args...]
// Build (NDK): aarch64-linux-android31-clang -o box64-lib-runner box64-lib-runner.c
#include <dlfcn.h>
#include <stdio.h>

extern char **environ;

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s libbox64.so program [args...]\n", argv[0]);
        return 2;
    }
    void *lib = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!lib) {
        fprintf(stderr, "dlopen: %s\n", dlerror());
        return 1;
    }
    int (*box64_run)(int, const char **, char **) =
        (int (*)(int, const char **, char **))dlsym(lib, "box64_run");
    if (!box64_run) {
        fprintf(stderr, "dlsym box64_run: %s\n", dlerror());
        return 1;
    }
    argv[1] = "box64";  // box64_run expects argv[0] to stand for box64 itself
    return box64_run(argc - 1, (const char **)argv + 1, environ);
}
