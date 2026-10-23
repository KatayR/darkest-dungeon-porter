// Stand-in libSDL2 that forwards everything to the real SDL2 and only wraps SDL_PollEvent.
//
// Problem: Termux:X11 sends a tap's button press and release back to back. The game folds events
// into per-frame state, so a press and release drained in the same frame cancel out and the click
// is lost (a short tap-and-hold works). Fix: when a release arrives in the same frame as its press,
// park it (and everything after it, to keep order) until the game has finished MIN_FRAMES more
// frames and MIN_HOLD_MS has passed since the press. A "frame" ends whenever we return 0.
//
// Set DD_TAPFIX_DEBUG=1 to log held releases to stderr.
#define _GNU_SOURCE
#include <SDL2/SDL.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

#define REAL_SDL     "/usr/local/lib/dd-tapfix/libSDL2-real.so"
#define QUEUE_MAX    256
#define MAX_BUTTONS  8
#define MIN_FRAMES   2
#define MIN_HOLD_MS  60

static int (*real_poll)(SDL_Event *);
static int debug;

static SDL_Event queue[QUEUE_MAX];   // ring buffer of events pulled from SDL but not yet delivered
static int q_head, q_len;

static unsigned frame;                   // number of times we returned 0
static int down[MAX_BUTTONS];            // press delivered, release not yet delivered
static unsigned press_frame[MAX_BUTTONS];
static Uint32 press_ms[MAX_BUTTONS];
static int held_button = -1;             // button whose release sits at the queue head, or -1

static void init(void) {
    void *real = dlopen(REAL_SDL, RTLD_NOW | RTLD_NOLOAD);
    real_poll = real ? (int (*)(SDL_Event *))dlsym(real, "SDL_PollEvent") : NULL;
    if (!real_poll) {
        fprintf(stderr, "tapfix: cannot find real SDL_PollEvent (%s)\n", dlerror());
        abort();
    }
    debug = getenv("DD_TAPFIX_DEBUG") != NULL;
}

static void push_back(const SDL_Event *e) {
    queue[(q_head + q_len) % QUEUE_MAX] = *e;
    q_len++;
}

static void push_front(const SDL_Event *e) {
    q_head = (q_head + QUEUE_MAX - 1) % QUEUE_MAX;
    queue[q_head] = *e;
    q_len++;
}

static SDL_Event pop_front(void) {
    SDL_Event e = queue[q_head];
    q_head = (q_head + 1) % QUEUE_MAX;
    q_len--;
    return e;
}

static int end_frame(void) {
    frame++;
    return 0;
}

int SDL_PollEvent(SDL_Event *ev) {
    if (!real_poll)
        init();
    if (!ev)
        return (held_button < 0 && q_len > 0) || real_poll(NULL);

    if (held_button >= 0) {
        // Keep pulling so SDL's own queue can't overflow and ordering is preserved.
        SDL_Event e;
        while (q_len < QUEUE_MAX && real_poll(&e))
            push_back(&e);
        if (frame - press_frame[held_button] < MIN_FRAMES ||
            SDL_GetTicks() - press_ms[held_button] < MIN_HOLD_MS)
            return end_frame();
        held_button = -1;
    }

    SDL_Event e;
    if (q_len > 0)
        e = pop_front();
    else if (!real_poll(&e))
        return end_frame();

    int b = (e.type == SDL_MOUSEBUTTONDOWN || e.type == SDL_MOUSEBUTTONUP) ? e.button.button : -1;
    if (b >= 0 && b < MAX_BUTTONS) {
        if (e.type == SDL_MOUSEBUTTONDOWN) {
            down[b] = 1;
            press_frame[b] = frame;
            press_ms[b] = SDL_GetTicks();
        } else if (down[b] && press_frame[b] == frame) {
            if (debug)
                fprintf(stderr, "tapfix: holding release of button %d\n", b);
            push_front(&e);
            held_button = b;
            return end_frame();
        } else {
            down[b] = 0;
        }
    }
    *ev = e;
    return 1;
}
