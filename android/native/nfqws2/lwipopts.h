#pragma once
/* One poll loop owns all lwIP and nfqws2/Lua state. */
#define NO_SYS 1
#define SYS_LIGHTWEIGHT_PROT 0
#define LWIP_SOCKET 0
#define LWIP_NETCONN 0
#define LWIP_IPV4 1
#define LWIP_IPV6 0
#define LWIP_ARP 0
#define LWIP_ETHERNET 0
#define LWIP_ICMP 1
#define LWIP_UDP 1
#define LWIP_TCP 1
#define LWIP_DNS 1
#define LWIP_DHCP 0
#define LWIP_AUTOIP 0
#define LWIP_NETIF_HOSTNAME 0
#define LWIP_NETIF_STATUS_CALLBACK 0
#define LWIP_NETIF_LINK_CALLBACK 0
#define MEM_LIBC_MALLOC 1
#define MEMP_MEM_MALLOC 1
#define MEM_ALIGNMENT 8
#define MEM_SIZE (16 * 1024 * 1024)
#define MEMP_NUM_TCP_PCB 128
#define MEMP_NUM_TCP_SEG 4096
#define PBUF_POOL_SIZE 1024
#define TCP_MSS 1360
#define TCP_WND (256 * 1024)
#define TCP_SND_BUF (64 * 1024)
#define TCP_SND_QUEUELEN 256
#define LWIP_WND_SCALE 1
#define TCP_RCV_SCALE 3
#define LWIP_TCP_TIMESTAMPS 1
#define LWIP_TCP_SACK_OUT 1
#define LWIP_TCP_MAX_SACK_NUM 4
#define TCP_QUEUE_OOSEQ 1
#define TCP_OOSEQ_MAX_BYTES (128 * 1024)
#define TCP_OOSEQ_MAX_PBUFS 128
#define TCP_TTL 64
#define UDP_TTL 64
#define IP_DEFAULT_TTL 64
#define LWIP_CHECKSUM_ON_COPY 1
#define IP_REASSEMBLY 1
#define IP_FRAG 0
#define IP_FORWARD 0
#define IP_REASS_MAX_PBUFS 64
#define DNS_TABLE_SIZE 32
#define DNS_MAX_REQUESTS 16
#define DNS_MAX_NAME_LENGTH 256
#define DNS_MAX_RETRIES 3
#define LWIP_DNS_SECURE 7
#define LWIP_STATS 0
#define LWIP_DEBUG 0
#define LWIP_RAND() igareck_random()
#define LWIP_HOOK_TCP_ISN(local_ip, local_port, remote_ip, remote_port) igareck_random()
unsigned int igareck_random(void);
