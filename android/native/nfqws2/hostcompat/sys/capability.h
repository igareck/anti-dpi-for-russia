#pragma once
#include <stdint.h>
#include <linux/capability.h>
int capget(struct __user_cap_header_struct *,struct __user_cap_data_struct *);
int capset(struct __user_cap_header_struct *,const struct __user_cap_data_struct *);
