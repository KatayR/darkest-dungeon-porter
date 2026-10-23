// libddaudio.so: the part of ALSA (libasound.so.2) that FMOD uses, on AAudio, in the game process
// (libddhost). Its SONAME is libasound.so.2, the name box64 opens for the game's x86_64 FMOD, which
// looks up exactly the snd_* functions below with dlsym. One playback device, "default"; no capture.
// - hw_params/sw_params are our own small structs (FMOD allocates them with the *_sizeof sizes).
// - snd_pcm_hw_params() opens the AAudio stream; writei() writes to it (blocking unless nonblock),
//   starting it once start_threshold frames are queued.
// - There are no xruns: on an underrun AAudio plays silence and carries on.
// - A stream lost to a route change (headphones) is reopened with the same settings.
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

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
    int nonblock, state, frame_bytes, writes_logged;
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
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setFormat(b, pcm->hw.format == FORMAT_FLOAT_LE ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, pcm->hw.channels);
    AAudioStreamBuilder_setSampleRate(b, pcm->hw.rate);
    AAudioStreamBuilder_setBufferCapacityInFrames(b, (int32_t)pcm->hw.buffer);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &pcm->stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOG("cannot open an AAudio stream: %s", AAudio_convertResultToText(r));
        pcm->stream = NULL;
        return -ENODEV;
    }
    AAudioStream_setBufferSizeInFrames(pcm->stream, (int32_t)pcm->hw.buffer);
    pcm->frame_bytes = pcm->hw.channels * (pcm->hw.format == FORMAT_FLOAT_LE ? 4 : 2);
    LOG("stream: %s %d ch %d Hz, buffer %d (asked %lu, period %lu), burst %d, start at %lu",
        pcm->hw.format == FORMAT_FLOAT_LE ? "float" : "s16", pcm->hw.channels, AAudioStream_getSampleRate(pcm->stream),
        AAudioStream_getBufferSizeInFrames(pcm->stream), pcm->hw.buffer, pcm->hw.period,
        AAudioStream_getFramesPerBurst(pcm->stream), pcm->sw.start_threshold);
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
    pcm->state = STATE_SETUP;
    return 0;
}

int snd_pcm_state(snd_pcm_t *pcm) {
    return pcm->state;
}

snd_pcm_sframes_t snd_pcm_avail_update(snd_pcm_t *pcm) {
    if (!pcm->stream)
        return -EBADFD;
    int64_t queued = AAudioStream_getFramesWritten(pcm->stream) - AAudioStream_getFramesRead(pcm->stream);
    int64_t size = AAudioStream_getBufferSizeInFrames(pcm->stream);
    return (snd_pcm_sframes_t)(queued < 0 ? size : queued > size ? 0 : size - queued);
}

snd_pcm_sframes_t snd_pcm_writei(snd_pcm_t *pcm, const void *buf, snd_pcm_uframes_t frames) {
    if (!pcm->stream)
        return -EBADFD;
    if (pcm->writes_logged < 3) {
        pcm->writes_logged++;
        LOG("write %lu frames, %ld free%s", frames, snd_pcm_avail_update(pcm), pcm->nonblock ? " (nonblock)" : "");
    }
    const char *data = buf;
    snd_pcm_uframes_t done = 0;
    int reopened = 0;
    while (done < frames) {
        // before the start, only fill what fits: a blocking write would wait for playback that never begins
        int64_t timeout = pcm->state == STATE_RUNNING && !pcm->nonblock ? 1000000000 : 0;
        aaudio_result_t r = AAudioStream_write(pcm->stream, data + done * pcm->frame_bytes, (int32_t)(frames - done), timeout);
        if (r == AAUDIO_ERROR_DISCONNECTED && !reopened) {
            reopened = 1;
            if (reopen_stream(pcm) < 0)
                return -EIO;
            continue;
        }
        if (r < 0) {
            LOG("write failed: %s", AAudio_convertResultToText(r));
            return done ? (snd_pcm_sframes_t)done : -EIO;
        }
        done += (snd_pcm_uframes_t)r;
        if (pcm->state != STATE_RUNNING) {
            int64_t queued = AAudioStream_getFramesWritten(pcm->stream);
            if ((snd_pcm_uframes_t)queued >= pcm->sw.start_threshold || done < frames) {
                int s = start_stream(pcm);
                if (s < 0)
                    return done ? (snd_pcm_sframes_t)done : s;
            }
        } else if (pcm->nonblock) {
            break;
        }
    }
    if (done == 0 && frames > 0)
        return -EAGAIN;
    return (snd_pcm_sframes_t)done;
}

snd_pcm_sframes_t snd_pcm_readi(snd_pcm_t *pcm, void *buf, snd_pcm_uframes_t frames) {
    (void)pcm; (void)buf; (void)frames;
    return -EBADFD;
}
