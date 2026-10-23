// libddaudio.so: the part of ALSA (libasound.so.2) that FMOD uses, on AAudio, in the game process
// (libddhost). Its SONAME is libasound.so.2, the name box64 opens for the game's x86_64 FMOD, which
// looks up exactly the snd_* functions below with dlsym. One playback device, "default"; no capture.
// - hw_params/sw_params are our own small structs (FMOD allocates them with the *_sizeof sizes).
// - snd_pcm_hw_params() opens the AAudio stream; writei() queues into a ring (blocking unless nonblock),
//   starting the stream once start_threshold frames are queued. AAudio's data callback plays the ring.
// - The callback is what makes AAudio's legacy path give a fast track: a stream fed by AAudioStream_write
//   is never one, and lands on the deep-buffer output, 233 ms behind on the Galaxy A34.
// - The ring holds FMOD's whole buffer (213 ms), but FMOD may queue only two periods of it at first; each
//   time the callback finds the ring short, that grows by a period (tune_buffer).
// - There are no xruns: on an underrun the callback plays silence and carries on.
// - A stream lost to a route change (headphones) is reopened with the same settings.
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <errno.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ddaudio", __VA_ARGS__)

// ALSA values (alsa/pcm.h)
enum { STREAM_PLAYBACK = 0 };
enum { MODE_NONBLOCK = 1 };
enum { ACCESS_RW_INTERLEAVED = 3 };
enum { FORMAT_S16_LE = 2, FORMAT_FLOAT_LE = 14 };
enum { STATE_OPEN, STATE_SETUP, STATE_PREPARED, STATE_RUNNING };

typedef unsigned long snd_pcm_uframes_t;
typedef long snd_pcm_sframes_t;

typedef struct {
    int format, channels, rate;
    snd_pcm_uframes_t buffer, period;
} snd_pcm_hw_params_t;

typedef struct {
    snd_pcm_uframes_t start_threshold, avail_min;
} snd_pcm_sw_params_t;

typedef struct {
    snd_pcm_hw_params_t hw;
    snd_pcm_sw_params_t sw;
    AAudioStream *stream;
    char *ring;                     // hw.buffer frames
    _Atomic uint64_t written, read; // frames into and out of the ring: FMOD's thread writes, the callback reads
    atomic_int underruns;           // callbacks that found the ring short
    snd_pcm_uframes_t buffer_size;  // what FMOD may queue, at most hw.buffer
    int nonblock, state, frame_bytes, writes_logged, underruns_seen;
} snd_pcm_t;

static snd_pcm_uframes_t clamp(snd_pcm_uframes_t v, snd_pcm_uframes_t lo, snd_pcm_uframes_t hi) {
    return v < lo ? lo : v > hi ? hi : v;
}

// ---- device list ----
static const char *const device_hint = "default";

int snd_device_name_hint(int card, const char *iface, void ***hints) {
    (void)card; (void)iface;
    void **list = calloc(2, sizeof *list);
    if (!list)
        return -ENOMEM;
    list[0] = (void *)device_hint;
    *hints = list;
    return 0;
}

char *snd_device_name_get_hint(const void *hint, const char *id) {
    (void)hint;
    if (!strcmp(id, "NAME"))
        return strdup("default");
    if (!strcmp(id, "DESC"))
        return strdup("Android audio");
    if (!strcmp(id, "IOID"))
        return strdup("Output");
    return NULL;
}

int snd_device_name_free_hint(void **hints) {
    free(hints);
    return 0;
}

const char *snd_asoundlib_version(void) {
    return "1.2.8";
}

const char *snd_strerror(int err) {
    return strerror(err < 0 ? -err : err);
}

// ---- the ring ----
static uint64_t queued(snd_pcm_t *pcm) {
    return atomic_load_explicit(&pcm->written, memory_order_relaxed) - atomic_load_explicit(&pcm->read, memory_order_acquire);
}

// copies n frames between buf and the ring at frame pos, which wraps at hw.buffer
static void ring_copy(snd_pcm_t *pcm, uint64_t pos, char *buf, uint64_t n, int to_ring) {
    uint64_t at = pos % pcm->hw.buffer, first = n < pcm->hw.buffer - at ? n : pcm->hw.buffer - at;
    size_t fb = (size_t)pcm->frame_bytes;
    char *ring = pcm->ring + at * fb;
    if (to_ring) {
        memcpy(ring, buf, first * fb);
        memcpy(pcm->ring, buf + first * fb, (n - first) * fb);
    } else {
        memcpy(buf, ring, first * fb);
        memcpy(buf + first * fb, pcm->ring, (n - first) * fb);
    }
}

// AAudio's callback thread: plays what is queued, then silence
static aaudio_data_callback_result_t play(AAudioStream *stream, void *user, void *out, int32_t frames) {
    (void)stream;
    snd_pcm_t *pcm = user;
    uint64_t read = atomic_load_explicit(&pcm->read, memory_order_relaxed);
    uint64_t have = atomic_load_explicit(&pcm->written, memory_order_acquire) - read;
    uint64_t n = have < (uint64_t)frames ? have : (uint64_t)frames;
    ring_copy(pcm, read, out, n, 0);
    atomic_store_explicit(&pcm->read, read + n, memory_order_release);
    if (n < (uint64_t)frames) {
        memset((char *)out + n * (size_t)pcm->frame_bytes, 0, ((uint64_t)frames - n) * (size_t)pcm->frame_bytes);
        atomic_fetch_add_explicit(&pcm->underruns, 1, memory_order_relaxed);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// ---- the AAudio stream ----
static void close_stream(snd_pcm_t *pcm) {
    if (pcm->stream)
        AAudioStream_close(pcm->stream);
    pcm->stream = NULL;
}

static int open_stream(snd_pcm_t *pcm) {
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK)
        return -ENODEV;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(b, pcm->hw.format == FORMAT_FLOAT_LE ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, pcm->hw.channels);
    AAudioStreamBuilder_setSampleRate(b, pcm->hw.rate);
    AAudioStreamBuilder_setDataCallback(b, play, pcm);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &pcm->stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOG("cannot open an AAudio stream: %s", AAudio_convertResultToText(r));
        pcm->stream = NULL;
        return -ENODEV;
    }
    // AudioTrack's own buffer comes as 2048 frames (43 ms); the callback only copies, so two bursts do
    AAudioStream_setBufferSizeInFrames(pcm->stream, 2 * AAudioStream_getFramesPerBurst(pcm->stream));
    LOG("stream: %s %d ch %d Hz, %s, burst %d, buffer %d; ring %lu, FMOD queues %lu (period %lu), start at %lu",
        pcm->hw.format == FORMAT_FLOAT_LE ? "float" : "s16", pcm->hw.channels, AAudioStream_getSampleRate(pcm->stream),
        AAudioStream_getPerformanceMode(pcm->stream) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY ? "low latency" : "not low latency",
        AAudioStream_getFramesPerBurst(pcm->stream), AAudioStream_getBufferSizeInFrames(pcm->stream),
        pcm->hw.buffer, pcm->buffer_size, pcm->hw.period, pcm->sw.start_threshold);
    return 0;
}

static int start_stream(snd_pcm_t *pcm) {
    aaudio_result_t r = AAudioStream_requestStart(pcm->stream);
    if (r != AAUDIO_OK) {
        LOG("cannot start the stream: %s", AAudio_convertResultToText(r));
        return -EIO;
    }
    pcm->state = STATE_RUNNING;
    return 0;
}

// underruns since the last write grow what FMOD may queue by a period, up to the ring
static void tune_buffer(snd_pcm_t *pcm) {
    int underruns = atomic_load_explicit(&pcm->underruns, memory_order_relaxed);
    if (underruns == pcm->underruns_seen)
        return;
    pcm->underruns_seen = underruns;
    if (pcm->buffer_size < pcm->hw.buffer) {
        pcm->buffer_size = clamp(pcm->buffer_size + pcm->hw.period, 0, pcm->hw.buffer);
        LOG("%d underruns, FMOD now queues %lu", underruns, pcm->buffer_size);
    }
}

// a stream lost to a route change: open a new one on the new route
static int reopen_stream(snd_pcm_t *pcm) {
    LOG("stream disconnected, reopening");
    int running = pcm->state == STATE_RUNNING;
    close_stream(pcm);
    pcm->state = STATE_SETUP;
    if (open_stream(pcm) < 0)
        return -EIO;
    pcm->state = STATE_PREPARED;
    return running ? start_stream(pcm) : 0;
}

static int check_stream(snd_pcm_t *pcm) {
    if (!pcm->stream)
        return -EBADFD;
    if (AAudioStream_getState(pcm->stream) == AAUDIO_STREAM_STATE_DISCONNECTED && reopen_stream(pcm) < 0)
        return -EIO;
    return 0;
}

// ---- pcm ----
int snd_pcm_open(snd_pcm_t **out, const char *name, int stream, int mode) {
    if (stream != STREAM_PLAYBACK)
        return -ENODEV;
    snd_pcm_t *pcm = calloc(1, sizeof *pcm);
    if (!pcm)
        return -ENOMEM;
    pcm->nonblock = mode & MODE_NONBLOCK;
    pcm->state = STATE_OPEN;
    pcm->sw.start_threshold = 1;
    LOG("open \"%s\"%s", name ? name : "", pcm->nonblock ? " (nonblock)" : "");
    *out = pcm;
    return 0;
}

int snd_pcm_close(snd_pcm_t *pcm) {
    LOG("close");
    close_stream(pcm);
    free(pcm->ring);
    free(pcm);
    return 0;
}

int snd_pcm_nonblock(snd_pcm_t *pcm, int nonblock) {
    pcm->nonblock = nonblock;
    return 0;
}

size_t snd_pcm_hw_params_sizeof(void) {
    return sizeof(snd_pcm_hw_params_t);
}

int snd_pcm_hw_params_any(snd_pcm_t *pcm, snd_pcm_hw_params_t *p) {
    (void)pcm;
    *p = (snd_pcm_hw_params_t){ FORMAT_S16_LE, 2, 48000, 4096, 1024 };
    return 0;
}

int snd_pcm_hw_params_set_access(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned access) {
    (void)pcm; (void)p;
    return access == ACCESS_RW_INTERLEAVED ? 0 : -EINVAL;
}

int snd_pcm_hw_params_set_format(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, int format) {
    (void)pcm;
    if (format != FORMAT_S16_LE && format != FORMAT_FLOAT_LE)
        return -EINVAL;
    p->format = format;
    return 0;
}

int snd_pcm_hw_params_set_channels(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned channels) {
    (void)pcm;
    if (channels < 1 || channels > 8)
        return -EINVAL;
    p->channels = (int)channels;
    return 0;
}

int snd_pcm_hw_params_test_rate(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned rate, int dir) {
    (void)pcm; (void)p; (void)dir;
    return rate >= 8000 && rate <= 192000 ? 0 : -EINVAL;
}

int snd_pcm_hw_params_set_rate(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned rate, int dir) {
    if (snd_pcm_hw_params_test_rate(pcm, p, rate, dir) < 0)
        return -EINVAL;
    p->rate = (int)rate;
    return 0;
}

int snd_pcm_hw_params_set_period_size_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, snd_pcm_uframes_t *val, int *dir) {
    (void)pcm;
    p->period = *val = clamp(*val, 64, 16384);
    if (dir)
        *dir = 0;
    return 0;
}

int snd_pcm_hw_params_set_buffer_size_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, snd_pcm_uframes_t *val) {
    (void)pcm;
    p->buffer = *val = clamp(*val, 2 * p->period, 65536);
    return 0;
}

int snd_pcm_hw_params(snd_pcm_t *pcm, snd_pcm_hw_params_t *p) {
    close_stream(pcm);
    pcm->hw = *p;
    pcm->frame_bytes = p->channels * (p->format == FORMAT_FLOAT_LE ? 4 : 2);
    free(pcm->ring);
    pcm->ring = malloc(p->buffer * (size_t)pcm->frame_bytes);
    if (!pcm->ring)
        return -ENOMEM;
    pcm->written = pcm->read = 0;
    pcm->underruns = pcm->underruns_seen = 0;
    pcm->buffer_size = clamp(2 * p->period, 0, p->buffer);
    pcm->state = STATE_SETUP;
    int r = open_stream(pcm);
    if (r < 0)
        return r;
    pcm->state = STATE_PREPARED;
    return 0;
}

size_t snd_pcm_sw_params_sizeof(void) {
    return sizeof(snd_pcm_sw_params_t);
}

int snd_pcm_sw_params_current(snd_pcm_t *pcm, snd_pcm_sw_params_t *p) {
    *p = pcm->sw;
    return 0;
}

int snd_pcm_sw_params_set_avail_min(snd_pcm_t *pcm, snd_pcm_sw_params_t *p, snd_pcm_uframes_t val) {
    (void)pcm;
    p->avail_min = val;
    return 0;
}

int snd_pcm_sw_params_set_start_threshold(snd_pcm_t *pcm, snd_pcm_sw_params_t *p, snd_pcm_uframes_t val) {
    (void)pcm;
    p->start_threshold = val;
    return 0;
}

int snd_pcm_sw_params(snd_pcm_t *pcm, snd_pcm_sw_params_t *p) {
    pcm->sw = *p;
    LOG("sw params: start at %lu, avail min %lu", p->start_threshold, p->avail_min);
    return 0;
}

int snd_pcm_prepare(snd_pcm_t *pcm) {
    if (!pcm->stream)
        return -EBADFD;
    if (pcm->state != STATE_RUNNING)
        pcm->state = STATE_PREPARED;
    return 0;
}

int snd_pcm_start(snd_pcm_t *pcm) {
    if (!pcm->stream)
        return -EBADFD;
    return pcm->state == STATE_RUNNING ? 0 : start_stream(pcm);
}

int snd_pcm_resume(snd_pcm_t *pcm) {
    (void)pcm;
    return 0;
}

// stops at once and throws away what is queued, like ALSA; the stream needs snd_pcm_prepare() again
int snd_pcm_drop(snd_pcm_t *pcm) {
    if (!pcm->stream)
        return -EBADFD;
    if (pcm->state == STATE_RUNNING) {
        aaudio_stream_state_t s = AAUDIO_STREAM_STATE_UNINITIALIZED;
        AAudioStream_requestPause(pcm->stream);
        AAudioStream_waitForStateChange(pcm->stream, AAUDIO_STREAM_STATE_PAUSING, &s, 100000000);
        AAudioStream_requestFlush(pcm->stream);
    }
    atomic_store_explicit(&pcm->read, atomic_load_explicit(&pcm->written, memory_order_relaxed), memory_order_release);
    pcm->state = STATE_SETUP;
    return 0;
}

int snd_pcm_state(snd_pcm_t *pcm) {
    return pcm->state;
}

snd_pcm_sframes_t snd_pcm_avail_update(snd_pcm_t *pcm) {
    int r = check_stream(pcm);
    if (r < 0)
        return r;
    uint64_t q = queued(pcm);
    return (snd_pcm_sframes_t)(q >= pcm->buffer_size ? 0 : pcm->buffer_size - q);
}

snd_pcm_sframes_t snd_pcm_writei(snd_pcm_t *pcm, const void *buf, snd_pcm_uframes_t frames) {
    int r = check_stream(pcm);
    if (r < 0)
        return r;
    tune_buffer(pcm);
    if (pcm->writes_logged < 3) {
        pcm->writes_logged++;
        LOG("write %lu frames, %ld free%s", frames, snd_pcm_avail_update(pcm), pcm->nonblock ? " (nonblock)" : "");
    }
    const char *data = buf;
    snd_pcm_uframes_t done = 0;
    while (done < frames) {
        uint64_t q = queued(pcm), n = q < pcm->buffer_size ? pcm->buffer_size - q : 0;
        if (n > frames - done)
            n = frames - done;
        uint64_t written = atomic_load_explicit(&pcm->written, memory_order_relaxed);
        ring_copy(pcm, written, (char *)data + done * pcm->frame_bytes, n, 1);
        atomic_store_explicit(&pcm->written, written + n, memory_order_release);
        done += n;
        // FMOD's start threshold is its whole buffer, more than it may queue here: start once that is full
        if (pcm->state != STATE_RUNNING && (q + n >= pcm->sw.start_threshold || q + n >= pcm->buffer_size)) {
            int s = start_stream(pcm);
            if (s < 0)
                return done ? (snd_pcm_sframes_t)done : s;
        }
        if (done == frames || pcm->nonblock || AAudioStream_getState(pcm->stream) == AAUDIO_STREAM_STATE_DISCONNECTED)
            break;
        // blocking and full: wait for the callback to make room
        nanosleep(&(struct timespec){ 0, 2000000 }, NULL);
    }
    if (done == 0 && frames > 0)
        return -EAGAIN;
    return (snd_pcm_sframes_t)done;
}

snd_pcm_sframes_t snd_pcm_readi(snd_pcm_t *pcm, void *buf, snd_pcm_uframes_t frames) {
    (void)pcm; (void)buf; (void)frames;
    return -EBADFD;
}
