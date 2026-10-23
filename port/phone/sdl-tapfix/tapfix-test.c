// Check for tapfix: drains SDL events once per 50 ms "frame" (like the game at ~20 FPS) and prints
// the frame each mouse press/release lands in. Instant clicks (xdotool click) should show press and
// release in the same frame without the shim, and in different frames with it.
#include <SDL2/SDL.h>
#include <stdio.h>

int main(void) {
    SDL_Init(SDL_INIT_VIDEO);
    SDL_Window *w = SDL_CreateWindow("tapfix-test", 0, 0, 1280, 720, 0);
    for (int frame = 0; frame < 120; frame++) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_MOUSEBUTTONDOWN || e.type == SDL_MOUSEBUTTONUP)
                printf("frame %d: button %d %s\n", frame, e.button.button,
                       e.type == SDL_MOUSEBUTTONDOWN ? "down" : "up");
            if (e.type == SDL_QUIT)
                frame = 1000;
        }
        fflush(stdout);
        SDL_Delay(50);
    }
    SDL_DestroyWindow(w);
    SDL_Quit();
    return 0;
}
