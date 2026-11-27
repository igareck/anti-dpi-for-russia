/* Host-only declarations for glibc's capget/capset syscall wrappers. */
#pragma once
#include <linux/capability.h>
extern int capget(cap_user_header_t header, cap_user_data_t data);
extern int capset(cap_user_header_t header, const struct __user_cap_data_struct *data);
