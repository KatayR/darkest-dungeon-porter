// Messages between the app (ddview, presents frames and sends input) and the game process
// (ddglue, renders). One SOCK_SEQPACKET connection on an abstract unix socket; every message is
// one packet holding a dd_msg, except where noted.
// DD_PRESENT and DD_RELEASE may carry a native fence fd (SCM_RIGHTS): the GPU work that wrote the
// frame (PRESENT) or read it (RELEASE) is done when it signals. The receiver waits on it on the GPU.
#pragma once
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>

#define DD_BUFFERS 3

enum {
    // app -> game
    DD_SURFACE = 1,   // a=width b=height: the size the game renders at; c=display refreshes per game
                      // frame (1 = 60 FPS, 2 = 30). Sent once after connecting.
    DD_MOUSE_MOVE,    // a=x b=y in game pixels (absolute)
    DD_MOUSE_BUTTON,  // a=SDL button (1 left, 3 right) b=1 down / 0 up
    DD_KEY,           // a=SDL scancode b=1 down / 0 up
    DD_RELEASE,       // a=buffer index: the app no longer shows it, the game may draw into it again
    DD_FOCUS,         // a=1 the game is on screen / 0 it isn't (the game ignores input without focus)
    DD_TEXT,          // a=one typed character (Unicode code point)
    DD_PAD,           // a=1 a game controller is connected / 0 none is
    DD_PAD_BUTTON,    // a=SDL_GameControllerButton b=1 down / 0 up
    DD_PAD_AXIS,      // a=SDL_GameControllerAxis b=value (sticks -32768..32767, triggers 0..32767)

    // game -> app
    DD_BUFFERS_MSG,   // a=count b=width c=height; followed by `count` AHardwareBuffer handle packets
                      // (AHardwareBuffer_sendHandleToUnixSocket)
    DD_PRESENT,       // a=buffer index holding a finished frame
    DD_CURSOR,        // a=width b=height c=hot x d=hot y; followed by one packet of width*height RGBA
    DD_CURSOR_SHOW,   // a=1 visible / 0 hidden
    DD_WARP,          // a=x b=y: the game moved the mouse
    DD_TEXT_INPUT,    // a=1 the game wants text (keyboard up) / 0 it doesn't
    DD_SHIFT_SCREEN,  // a=1 the trinket inventory is open, where Shift-click sells (Shift button up) / 0 it isn't
};

typedef struct {
    int32_t type, a, b, c, d;
} dd_msg;

// sends one message, with fence_fd attached when it is >= 0 (the caller still owns and closes it)
static inline ssize_t dd_send(int sock, const dd_msg *m, int fence_fd) {
    struct iovec iov = { (void *)m, sizeof *m };
    char ctl[CMSG_SPACE(sizeof(int))] = { 0 };
    struct msghdr h = { .msg_iov = &iov, .msg_iovlen = 1 };
    if (fence_fd >= 0) {
        h.msg_control = ctl;
        h.msg_controllen = sizeof ctl;
        struct cmsghdr *c = CMSG_FIRSTHDR(&h);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fence_fd, sizeof(int));
    }
    return sendmsg(sock, &h, MSG_NOSIGNAL);
}

// receives one message; *fence_fd is the attached fd (now the caller's) or -1
static inline ssize_t dd_recv(int sock, dd_msg *m, int *fence_fd) {
    struct iovec iov = { m, sizeof *m };
    char ctl[CMSG_SPACE(sizeof(int))];
    struct msghdr h = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = ctl, .msg_controllen = sizeof ctl };
    ssize_t n = recvmsg(sock, &h, MSG_CMSG_CLOEXEC);
    *fence_fd = -1;
    struct cmsghdr *c = n > 0 ? CMSG_FIRSTHDR(&h) : NULL;
    if (c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS)
        memcpy(fence_fd, CMSG_DATA(c), sizeof(int));
    return n;
}
