# Native part of the app. box64 (libbox64.so) and gl4es (libGL.so) are prebuilt by build-natives.sh
# into ${DD_BUILD}/out/jniLibs.
#   app process:  libddview.so (presents the game's frames, sends input)
#   game process: libddhost.so (an executable) + libddglue.so + libddaudio.so + libSDL2.so + libbox64.so + libGL.so
cmake_minimum_required(VERSION 3.10)
project(ddport C)

set(DD_BUILD "C:/dd-build" CACHE PATH "Build tree holding sources and prebuilt natives")
set(PREBUILT ${DD_BUILD}/out/jniLibs/${ANDROID_ABI})
set(NATIVE ${CMAKE_CURRENT_SOURCE_DIR}/../../native)

# SDL only runs in the game process, which has no Java side: ddglue never starts the subsystems
# that would need it (joystick, haptic, sensor), but SDL's Android core must still have them built
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL_HIDAPI OFF CACHE BOOL "" FORCE)
add_subdirectory(${DD_BUILD}/src/SDL2 SDL2)

add_library(GL SHARED IMPORTED)
set_target_properties(GL PROPERTIES IMPORTED_LOCATION ${PREBUILT}/libGL.so)
add_library(box64 SHARED IMPORTED)
set_target_properties(box64 PROPERTIES IMPORTED_LOCATION ${PREBUILT}/libbox64.so)

# SONAME libSDL2-2.0.so.0: the name box64's SDL2 wrapper opens
add_library(ddglue SHARED ${NATIVE}/ddglue/ddglue.c)
target_include_directories(ddglue PRIVATE ${DD_BUILD}/src/gl4es/include)
target_link_libraries(ddglue SDL2 GL EGL nativewindow log dl)
set_target_properties(ddglue PROPERTIES NO_SONAME ON)
target_link_options(ddglue PRIVATE -Wl,-soname,libSDL2-2.0.so.0)

# SONAME libasound.so.2: the name box64's ALSA wrapper opens (for the game's FMOD)
add_library(ddaudio SHARED ${NATIVE}/ddaudio/ddaudio.c)
target_link_libraries(ddaudio aaudio log)
set_target_properties(ddaudio PROPERTIES NO_SONAME ON)
target_link_options(ddaudio PRIVATE -Wl,-soname,libasound.so.2)

# an executable, named like a library so the APK installs it (executable) next to the libraries
add_executable(ddhost ${NATIVE}/host/main.c)
target_link_libraries(ddhost box64 dl)
add_dependencies(ddhost ddglue ddaudio)   # loaded with dlopen: see host/main.c
set_target_properties(ddhost PROPERTIES OUTPUT_NAME libddhost SUFFIX .so
                      RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})

add_library(ddview SHARED ${NATIVE}/ddview/ddview.c)
target_link_libraries(ddview EGL GLESv2 android nativewindow log)
