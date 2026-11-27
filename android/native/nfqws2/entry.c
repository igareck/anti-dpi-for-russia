#define _GNU_SOURCE
#include "nfq_bridge.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/prctl.h>
unsigned ig_port = 1080;
unsigned ig_probe_count,ig_probe_ids[5];
bool ig_udp_enabled;
char ig_quic_profile[8]="OFF";
char ig_discord_profile[8]="OFF";
unsigned ig_current_voice;
unsigned ig_current_proto;
char ig_profile[16] = "T0", ig_instance[80] = "unset", ig_socket[110];
int ig_test_fd = -1;
int ig_test_config_fd = -1;
extern int nfqws2_main(int, char **);
static void take(char *dst, size_t cap, const char *s) {
    if (strlen(s) >= cap) {
        fprintf(stderr, "Argument too long\n");
        exit(2);
    }
    strcpy(dst, s);
}
int main(int argc, char **argv) {
    pid_t parent = getppid();
    prctl(PR_SET_PDEATHSIG, SIGTERM);
    if (getppid() != parent)
        return 2;
    signal(SIGPIPE, SIG_IGN);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);
    char **clean = calloc((size_t)argc + 1, sizeof(char *));
    if (!clean)
        return 2;
    int n = 1;
    clean[0] = argv[0];
    for (int i = 1; i < argc; i++) {
        if (!strncmp(argv[i], "--igareck-socket=", 17))
            take(ig_socket, sizeof(ig_socket), argv[i] + 17);
        else if (!strncmp(argv[i], "--igareck-probes=", 17)) {
            char list[64];take(list,sizeof(list),argv[i]+17);char *save=NULL;
            for(char *s=strtok_r(list,",",&save);s;s=strtok_r(NULL,",",&save)){
                if(ig_probe_count>=5||!s[0]||strspn(s,"0123456789")!=strlen(s))return 2;
                unsigned v=(unsigned)strtoul(s,NULL,10);if(v<1||v>484)return 2;
                for(unsigned j=0;j<ig_probe_count;j++)if(ig_probe_ids[j]==v)return 2;
                ig_probe_ids[ig_probe_count++]=v;
            }
            if(!ig_probe_count)return 2;
        }
        else if (!strncmp(argv[i], "--igareck-port=", 15))
            ig_port = (unsigned)strtoul(argv[i] + 15, NULL, 10);
        else if (!strncmp(argv[i], "--igareck-profile=", 18))
            take(ig_profile, sizeof(ig_profile), argv[i] + 18);
        else if (!strncmp(argv[i], "--igareck-instance=", 19))
            take(ig_instance, sizeof(ig_instance), argv[i] + 19);
#ifdef IGARECK_HOST_TEST
        else if (!strncmp(argv[i], "--igareck-test-fd=", 18))
            ig_test_fd = atoi(argv[i] + 18);
        else if (!strncmp(argv[i], "--igareck-config-fd=", 20))
            ig_test_config_fd = atoi(argv[i] + 20);
#endif
        else if (!strncmp(argv[i], "--igareck-udp=", 14)) {
            if (strcmp(argv[i]+14,"0") && strcmp(argv[i]+14,"1")) return 2;
            ig_udp_enabled = argv[i][14]=='1';
        } else if (!strncmp(argv[i], "--igareck-quic=", 15))
            take(ig_quic_profile,sizeof(ig_quic_profile),argv[i]+15);
        else if (!strncmp(argv[i], "--igareck-discord=", 18))
            take(ig_discord_profile,sizeof(ig_discord_profile),argv[i]+18);
        else
            clean[n++] = argv[i];
    }
    if (ig_probe_count && ig_udp_enabled) return 2;
    if (ig_udp_enabled && strcmp(ig_quic_profile,"OFF") && (strlen(ig_quic_profile)!=2 || ig_quic_profile[0]!='Q' || ig_quic_profile[1]<'1' || ig_quic_profile[1]>'5')) return 2;
    if (ig_udp_enabled) {
        if (!strcmp(ig_discord_profile,"OFF")) strcpy(ig_discord_profile,"D0");
        if (strlen(ig_discord_profile)!=2 || ig_discord_profile[0]!='D' || ig_discord_profile[1]<'0' || ig_discord_profile[1]>'8') return 2;
    }
    if ((ig_port < 1024 && !(ig_port==0 && ig_probe_count)) || ig_port > 65535 ||
        strspn(ig_profile, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_") != strlen(ig_profile) ||
        strspn(ig_instance, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-") !=
            strlen(ig_instance)) {
        fprintf(stderr, "Invalid transport configuration\n");
        return 2;
    }
#ifndef IGARECK_HOST_TEST
    if (getuid() != 2000 || !ig_socket[0]) {
        fprintf(stderr, "Use ADB-backed Shizuku UID 2000 with an owned TAP\n");
        return 2;
    }
#endif
    int result = nfqws2_main(n, clean);
    free(clean);
    return result;
}
