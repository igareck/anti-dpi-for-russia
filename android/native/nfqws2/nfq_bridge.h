/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
struct igareck_metrics {
    uint64_t input_out, input_in, generated, passed, dropped, modified, lua_calls;
    uint64_t tap_tx, tap_rx, tcp_open, tcp_fail, client_tx, client_rx, queue_errors, socks_rejected;
    uint64_t udp_associations, udp_associations_active, udp_flows_active, udp_tx, udp_rx, udp_tx_bytes, udp_rx_bytes;
    uint64_t udp_rejected, udp_dropped, udp_dns_fail, udp_generated, udp_ip_fragments, udp_lua_calls;
    uint64_t udp_local_rx, udp_local_rx_bytes, udp_socket_errors, udp_assoc_closed, udp_assoc_empty;
    uint64_t voice_stun_packets, voice_discovery_packets, voice_lua_calls, voice_generated;
};
extern struct igareck_metrics ig_metrics;
extern unsigned ig_port;
extern unsigned ig_probe_count, ig_probe_ids[5];
uint32_t igareck_flow_mark(const unsigned char *packet,size_t length,bool reverse);
extern bool ig_udp_enabled;
extern char ig_quic_profile[8];
extern char ig_discord_profile[8];
extern unsigned ig_current_voice;
extern unsigned ig_current_proto;
extern char ig_profile[16], ig_instance[80], ig_socket[110];
extern int ig_test_fd;
extern int ig_test_config_fd;
int igareck_tap_loop(void);
bool igareck_emit_generated(const void *packet, size_t length);
size_t igareck_process(const void *packet, size_t length, bool reverse, void *result,
                       size_t capacity);
int igareck_core_init(void);
void igareck_core_tick(void);
void igareck_core_close(void);
void igareck_audit_lua(const char *name);
void igareck_print_metrics(const char *event);
