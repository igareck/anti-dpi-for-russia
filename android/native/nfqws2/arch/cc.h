#pragma once
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#define LWIP_NO_INTTYPES_H 0
#define LWIP_PLATFORM_DIAG(x)                                                                      \
    do {                                                                                           \
        printf x;                                                                                  \
    } while (0)
#define LWIP_PLATFORM_ASSERT(x)                                                                    \
    do {                                                                                           \
        fprintf(stderr, "lwIP assertion: %s\n", x);                                                \
        abort();                                                                                   \
    } while (0)
#ifndef BYTE_ORDER
#define BYTE_ORDER LITTLE_ENDIAN
#endif
