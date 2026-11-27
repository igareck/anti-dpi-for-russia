#pragma once

#include <signal.h>

#if defined(__linux__) && !defined(IGARECK_TAP)
#define HAS_FILTER_SSID 1
#endif

extern volatile sig_atomic_t bQuit;
int main(int argc, char *argv[]);

// when something changes that can break LUA compatibility this version should be increased
#define LUA_COMPAT_VER	6
