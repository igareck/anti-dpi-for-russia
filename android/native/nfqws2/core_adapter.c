/* Transport adaptation only; actual packet processing and Lua are upstream nfqws2. */
#include "nfq_bridge.h"
#include "params.h"
#include "desync.h"
#include "../../vendor/nfqws2/nfq2/lua.h"
#include "timer.h"
#include "helpers.h"
#include "protocol.h"
#include <stdio.h>
#include <string.h>

struct igareck_metrics ig_metrics;
static uint64_t gc_at;
static uint32_t current_probe_mark;
static unsigned current_probe_port;
static unsigned probe_audits[5];
int igareck_core_init(void) {
    if (!lua_test_init_script_files() || !lua_init())
        return -1;
    printf("[IGARECK_NFQWS2_CORE] commit=038c97e5819df10f8e8c22a2f1fffe57f2f7379b lua_compat=6 "
           "transport=TAP raw_socket=false profile=%s instance=%s\n",
           ig_profile, ig_instance);
    return 0;
}
size_t igareck_process(const void *p, size_t n, bool reverse, void *out, size_t cap) {
    /* Ethernet's 14-byte header leaves IP unaligned. Upstream uses IP/TCP structs. */
    static unsigned char aligned_packet[65536] __attribute__((aligned(16)));
    static unsigned char modified[RECONSTRUCT_MAX_SIZE] __attribute__((aligned(16)));
    size_t size = sizeof(modified);
    if (n > sizeof(aligned_packet))
        return 0;
    ig_current_proto=n>=20?((const unsigned char *)p)[9]:0;
    memcpy(aligned_packet, p, n);
    p = aligned_packet;
    ig_current_voice=0;
    if (!reverse && n>=28 && (aligned_packet[0]>>4)==4 && ig_current_proto==17 &&
        !(aligned_packet[6]&0x1f) && !aligned_packet[7]) {
        unsigned ih=(aligned_packet[0]&15)*4;
        if (ih>=20 && n>=ih+8) {
            const unsigned char *payload=aligned_packet+ih+8;size_t length=n-ih-8;
            if (IsDiscordIpDiscoveryRequest(payload,length)) {
                ig_current_voice=2;ig_metrics.voice_discovery_packets++;
            } else if (IsStunMessage(payload,length)) {
                ig_current_voice=1;ig_metrics.voice_stun_packets++;
            }
        }
    }
    if (reverse)
        ig_metrics.input_in++;
    else
        ig_metrics.input_out++;
    uint32_t mark=igareck_flow_mark(p,n,reverse);current_probe_mark=mark;current_probe_port=0;
    if(mark&&n>=40){unsigned ih=(aligned_packet[0]&15)*4;unsigned off=ih+(reverse?2:0);if(off+2<=n)current_probe_port=((unsigned)aligned_packet[off]<<8)|aligned_packet[off+1];}
    uint8_t v = dpi_desync_packet(mark, reverse ? "igareck-tap" : NULL, reverse ? NULL : "igareck-tap",
                                  p, n, modified, &size);
    /* Packet audit context must not leak into later timer callbacks. */
    ig_current_proto=0;ig_current_voice=0;current_probe_mark=0;
    switch (v & VERDICT_MASK) {
    case VERDICT_DROP:
        ig_metrics.dropped++;
        return 0;
    case VERDICT_MODIFY:
        ig_metrics.modified++;
        if (size > cap)
            return 0;
        memcpy(out, modified, size);
        return size;
    default:
        ig_metrics.passed++;
        if (n > cap)
            return 0;
        memcpy(out, p, n);
        return n;
    }
}
void igareck_audit_lua(const char *name) {
    ig_metrics.lua_calls++;
    if(current_probe_mark)for(unsigned i=0;i<ig_probe_count;i++)if(ig_probe_ids[i]==current_probe_mark&&probe_audits[i]++<12){
        printf("[IGARECK_YOUTUBE_LUA] strategy=T%u function=%s source_port=%u instance=%s\n",current_probe_mark,name,current_probe_port,ig_instance);break;
    }
    if (ig_current_voice) {
        ig_metrics.voice_lua_calls++;
        if (ig_metrics.voice_lua_calls<=32)
            printf("[IGARECK_DISCORD_LUA] discord=%s payload=%s function=%s instance=%s\n",
                ig_discord_profile,ig_current_voice==2?"discord_ip_discovery":"stun",name,ig_instance);
    }
    if(ig_current_proto==17){ig_metrics.udp_lua_calls++;
        if(ig_metrics.udp_lua_calls<=24)printf("[IGARECK_UDP_LUA] quic=%s discord=%s function=%s instance=%s\n",ig_quic_profile,ig_discord_profile,name,ig_instance);
    }
    if (ig_metrics.lua_calls <= 24)
        printf("[IGARECK_NFQWS2_LUA] function=%s profile=%s instance=%s\n", name, ig_profile,
               ig_instance);
}
void igareck_core_tick(void) {
    uint64_t now = boottime_ms();
    if (params.timers)
        TimerPoolRun(&params.timers, &params.timers_dirty, now);
    if (now >= gc_at) {
        lua_do_gc();
        gc_at = now + 500;
    }
}
void igareck_core_close(void) { lua_shutdown(); }
void igareck_print_metrics(const char *event) {
    printf("[IGARECK_NFQWS2_METRICS] "
           "{\"event\":\"%s\",\"profile\":\"%s\",\"instance\":\"%s\",\"core\":\"nfqws2\",\"input_"
           "out\":%llu,\"input_in\":%llu,\"generated\":%llu,\"lua_calls\":%llu,\"passed\":%llu,"
           "\"dropped\":%llu,\"modified\":%llu,\"tap_tx\":%llu,\"tap_rx\":%llu,\"tcp_open\":%llu,"
           "\"tcp_fail\":%llu,\"client_tx\":%llu,\"client_rx\":%llu,\"queue_errors\":%llu,\"socks_"
           "rejected\":%llu,\"udp_enabled\":%s,\"quic_profile\":\"%s\","
           "\"udp_associations\":%llu,\"udp_associations_active\":%llu,\"udp_flows_active\":%llu,"
           "\"udp_tx\":%llu,\"udp_rx\":%llu,\"udp_tx_bytes\":%llu,\"udp_rx_bytes\":%llu,"
           "\"udp_rejected\":%llu,\"udp_dropped\":%llu,\"udp_dns_fail\":%llu,"
           "\"udp_generated\":%llu,\"udp_ip_fragments\":%llu,\"udp_lua_calls\":%llu,"
           "\"discord_profile\":\"%s\",\"udp_local_rx\":%llu,\"udp_local_rx_bytes\":%llu,"
           "\"udp_socket_errors\":%llu,\"udp_assoc_closed\":%llu,\"udp_assoc_empty\":%llu,"
           "\"voice_stun_packets\":%llu,\"voice_discovery_packets\":%llu,\"voice_lua_calls\":%llu,\"voice_generated\":%llu}\n",
           event, ig_profile, ig_instance, (unsigned long long)ig_metrics.input_out,
           (unsigned long long)ig_metrics.input_in, (unsigned long long)ig_metrics.generated,
           (unsigned long long)ig_metrics.lua_calls, (unsigned long long)ig_metrics.passed,
           (unsigned long long)ig_metrics.dropped, (unsigned long long)ig_metrics.modified,
           (unsigned long long)ig_metrics.tap_tx, (unsigned long long)ig_metrics.tap_rx,
           (unsigned long long)ig_metrics.tcp_open, (unsigned long long)ig_metrics.tcp_fail,
           (unsigned long long)ig_metrics.client_tx, (unsigned long long)ig_metrics.client_rx,
           (unsigned long long)ig_metrics.queue_errors,
           (unsigned long long)ig_metrics.socks_rejected,ig_udp_enabled?"true":"false",ig_quic_profile,
           (unsigned long long)ig_metrics.udp_associations,(unsigned long long)ig_metrics.udp_associations_active,
           (unsigned long long)ig_metrics.udp_flows_active,(unsigned long long)ig_metrics.udp_tx,(unsigned long long)ig_metrics.udp_rx,
           (unsigned long long)ig_metrics.udp_tx_bytes,(unsigned long long)ig_metrics.udp_rx_bytes,(unsigned long long)ig_metrics.udp_rejected,
           (unsigned long long)ig_metrics.udp_dropped,(unsigned long long)ig_metrics.udp_dns_fail,(unsigned long long)ig_metrics.udp_generated,
           (unsigned long long)ig_metrics.udp_ip_fragments,(unsigned long long)ig_metrics.udp_lua_calls,
           ig_discord_profile,(unsigned long long)ig_metrics.udp_local_rx,(unsigned long long)ig_metrics.udp_local_rx_bytes,
           (unsigned long long)ig_metrics.udp_socket_errors,(unsigned long long)ig_metrics.udp_assoc_closed,
           (unsigned long long)ig_metrics.udp_assoc_empty,(unsigned long long)ig_metrics.voice_stun_packets,
           (unsigned long long)ig_metrics.voice_discovery_packets,(unsigned long long)ig_metrics.voice_lua_calls,
           (unsigned long long)ig_metrics.voice_generated);
    fflush(stdout);
}
