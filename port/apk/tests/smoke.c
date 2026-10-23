// glibc smoke test for the bionic Box64 build: stdio, files, time, dlopen, threads, TLS, errno.
// Build (x86_64): x86_64-linux-gnu-gcc -O1 -o smoke-c smoke.c -lpthread -ldl
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static __thread int tls_value;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
static int counter;

static void *worker(void *arg) {
    tls_value = (int)(long)arg;
    for (int i = 0; i < 100000; i++) {
        pthread_mutex_lock(&lock);
        counter++;
        pthread_cond_signal(&cond);
        pthread_mutex_unlock(&lock);
    }
    return (void *)(long)tls_value;
}

int main(int argc, char **argv) {
    printf("argv[0]=%s argc=%d\n", argv[0], argc);

    char cwd[512];
    printf("cwd=%s\n", getcwd(cwd, sizeof cwd));

    struct stat st;
    int sr = stat(argv[0], &st);
    printf("stat(argv[0])=%d size=%lld\n", sr, (long long)st.st_size);
    FILE *f = fopen("/nonexistent/x", "r");
    printf("fopen missing -> %p errno=%d (%s)\n", (void *)f, errno, strerror(errno));

    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    printf("monotonic ok=%d time=%ld\n", ts.tv_sec > 0, (long)time(NULL));

    void *m = dlopen("libm.so.6", RTLD_NOW);
    double (*cosp)(double) = m ? (double (*)(double))dlsym(m, "cos") : NULL;
    printf("dlopen libm=%p cos(0)=%g\n", m, cosp ? cosp(0.0) : -1.0);

    pthread_t t[4];
    for (long i = 0; i < 4; i++)
        pthread_create(&t[i], NULL, worker, (void *)(i + 1));
    long sum = 0;
    for (int i = 0; i < 4; i++) {
        void *r;
        pthread_join(t[i], &r);
        sum += (long)r;
    }
    printf("threads: counter=%d (want 400000) tls sum=%ld (want 10)\n", counter, sum);

    char *p = malloc(1 << 20);
    memset(p, 7, 1 << 20);
    printf("malloc ok=%d\n", p[12345] == 7);
    free(p);
    puts(counter == 400000 && sum == 10 ? "SMOKE-C OK" : "SMOKE-C FAIL");
    return 0;
}
