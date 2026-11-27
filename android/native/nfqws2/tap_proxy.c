/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later
 * A single-owner lwIP raw-API loop. OS sockets are used ONLY for loopback SOCKS
 * clients and local FD transfer. Internet TCP, UDP and DNS use the supplied TAP. */
#define _GNU_SOURCE
#include "nfq_bridge.h"
#include "lwip/init.h"
#include "lwip/netif.h"
#include "lwip/ip4.h"
#include "lwip/tcp.h"
#include "lwip/udp.h"
#include "lwip/dns.h"
#include "lwip/timeouts.h"
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/types.h>
#include <sys/random.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <unistd.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#define CLIENTS 64
#define RX_CAP (256 * 1024)
#define TX_CAP 65536
#define QUEUE_BYTES (4 * 1024 * 1024)
#define QUEUE_PACKETS 4096
extern volatile sig_atomic_t bQuit;
static struct netif iface;
static int tap_fd = -1, listen_fd = -1;
static int probe_listeners[4]={-1,-1,-1,-1};
static unsigned char client_ip[4], gateway_ip[4], dns_ip[4], client_mac[6], gateway_mac[6];
static unsigned mtu = 1400;
static uint64_t queue_bytes;
static unsigned queue_packets;
static int fatal_transport;

u32_t sys_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u32_t)((uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}
unsigned int igareck_random(void) {
    unsigned int n;
    if (getrandom(&n, sizeof(n), GRND_NONBLOCK) != (ssize_t)sizeof(n)) {
        fprintf(stderr, "Secure random unavailable\n");
        bQuit = 1;
        return sys_now();
    }
    return n;
}
static uint16_t r16(const unsigned char *p) { return (uint16_t)((p[0] << 8) | p[1]); }
static uint32_t r32(const unsigned char *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
static void w16(unsigned char *p, uint16_t n) {
    p[0] = (unsigned char)(n >> 8);
    p[1] = (unsigned char)n;
}
static void nonblock(int fd) {
    int f = fcntl(fd, F_GETFL, 0);
    if (f >= 0)
        fcntl(fd, F_SETFL, f | O_NONBLOCK);
    fcntl(fd, F_SETFD, FD_CLOEXEC);
}

struct packet {
    struct packet *next;
    size_t length;
    bool stack;
    unsigned char bytes[];
};
static struct packet *qhead, *qtail;
static bool enqueue(const void *data, size_t n, bool stack, bool ethernet) {
    size_t length = n + (ethernet ? 0 : stack ? 0 : 14);
    if (length > 65550 || queue_bytes + length > QUEUE_BYTES || queue_packets >= QUEUE_PACKETS) {
        ig_metrics.queue_errors++;
        return false;
    }
    struct packet *p = malloc(sizeof(*p) + length);
    if (!p) {
        ig_metrics.queue_errors++;
        return false;
    }
    p->next = NULL;
    p->length = length;
    p->stack = stack;
    if (!stack && !ethernet) {
        memcpy(p->bytes, gateway_mac, 6);
        memcpy(p->bytes + 6, client_mac, 6);
        p->bytes[12] = 8;
        p->bytes[13] = 0;
        memcpy(p->bytes + 14, data, n);
    } else
        memcpy(p->bytes, data, n);
    if (qtail)
        qtail->next = p;
    else
        qhead = p;
    qtail = p;
    queue_bytes += length;
    queue_packets++;
    return true;
}
static uint16_t ip_checksum(const unsigned char *p, size_t n) {
    uint32_t sum=0; for(size_t i=0;i+1<n;i+=2)sum+=r16(p+i);
    while(sum>>16)sum=(sum&65535)+(sum>>16);
    return (uint16_t)~sum;
}
static bool enqueue_ip(const unsigned char *p,size_t n,bool reverse) {
    if(reverse || n<=mtu)return enqueue(p,n,reverse,false);
    unsigned ih=(p[0]&15)*4,flags=r16(p+6);
    if(ih!=20 || n<ih || (flags&0x4000) || mtu<=ih+8){ig_metrics.udp_dropped++;return false;}
    size_t step=((mtu-ih)/8)*8,total=n-ih;
    for(size_t offset=0;offset<total;offset+=step){
        size_t take=total-offset;if(take>step)take=step;
        unsigned char fragment[1500];memcpy(fragment,p,ih);memcpy(fragment+ih,p+ih+offset,take);
        w16(fragment+2,(uint16_t)(ih+take));
        w16(fragment+6,(uint16_t)(((flags&0x1fff)+(offset/8)) | ((offset+take<total || (flags&0x2000))?0x2000:0)));
        fragment[10]=fragment[11]=0;w16(fragment+10,ip_checksum(fragment,ih));
        if(!enqueue(fragment,ih+take,false,false))return false;
        if(p[9]==17)ig_metrics.udp_ip_fragments++;
    }
    return true;
}
static void generated_header(const unsigned char *p, size_t n) {
    static unsigned udp_logged;
    if(n>=20 && (p[0]>>4)==4 && p[9]==17){
        ig_metrics.udp_generated++;if(ig_current_voice)ig_metrics.voice_generated++;unsigned ih=(p[0]&15)*4;
        if(udp_logged++<128 && ih>=20 && n>=ih)printf("[IGARECK_UDP_GENERATED] tcp=%s quic=%s discord=%s voice=%u instance=%s len=%zu ip_id=%u fragment_offset=%u more=%u ttl=%u\n",
            ig_profile,ig_quic_profile,ig_discord_profile,ig_current_voice,ig_instance,n,r16(p+4),(r16(p+6)&8191)*8,(r16(p+6)>>13)&1,p[8]);
        return;
    }
    if (ig_metrics.generated > 128 || n < 40 || (p[0] >> 4) != 4 || p[9] != 6)
        return;
    unsigned ih = (p[0] & 15) * 4;
    if (ih < 20 || n < ih + 20)
        return;
    unsigned th = (p[ih + 12] >> 4) * 4;
    if (th < 20 || n < ih + th)
        return;
    uint32_t ts = 0;
    bool has_ts = false;
    for (unsigned i = ih + 20; i < ih + th;) {
        unsigned k = p[i];
        if (!k)
            break;
        if (k == 1) {
            i++;
            continue;
        }
        if (i + 1 >= ih + th || p[i + 1] < 2 || i + p[i + 1] > ih + th)
            break;
        if (k == 8 && p[i + 1] == 10) {
            ts = r32(p + i + 2);
            has_ts = true;
        }
        i += p[i + 1];
    }
    printf("[IGARECK_NFQWS2_GENERATED] profile=%s instance=%s len=%zu sport=%u dport=%u seq=%u "
           "ack=%u ttl=%u flags=%u timestamp_present=%s timestamp=%u payload_len=%zu\n",
           ig_profile, ig_instance, n, r16(p + ih), r16(p + ih + 2), r32(p + ih + 4),
           r32(p + ih + 8), p[8], p[ih + 13], has_ts ? "true" : "false", ts, n - ih - th);
}
bool igareck_emit_generated(const void *data, size_t n) {
    const unsigned char *p = data;
    if (n < 20 || (p[0] >> 4) != 4 || r16(p + 2) != n)
        return false;
    bool reverse = !memcmp(p + 16, client_ip, 4);
    if (!enqueue_ip(p, n, reverse))
        return false;
    ig_metrics.generated++;
    generated_header(p, n);
    return true;
}
static void stack_input(const void *data, size_t n) {
    if (n > 65535)
        return;
    struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t)n, PBUF_POOL);
    if (!p) {
        ig_metrics.queue_errors++;
        return;
    }
    if (pbuf_take(p, data, (u16_t)n) != ERR_OK) {
        pbuf_free(p);
        return;
    }
    if (ip4_input(p, &iface) != ERR_OK)
        fprintf(stderr, "[IGARECK_IP_INPUT_ERROR]\n");
}
static void flush_packets(void) {
    unsigned count = 0;
    while (qhead && count++ < 256) {
        struct packet *p = qhead;
        if (!p->stack) {
            ssize_t n = write(tap_fd, p->bytes, p->length);
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
                break;
            if (n != (ssize_t)p->length) {
                fprintf(stderr, "[IGARECK_TAP_WRITE_ERROR] errno=%d\n", errno);
                fatal_transport = 1;
                return;
            }
            ig_metrics.tap_tx++;
        }
        qhead = p->next;
        if (!qhead)
            qtail = NULL;
        queue_bytes -= p->length;
        queue_packets--;
        if (p->stack)
            stack_input(p->bytes, p->length);
        free(p);
    }
}
static err_t output_ip(struct netif *n, struct pbuf *p, const ip4_addr_t *dest) {
    (void)n;
    (void)dest;
    static unsigned char original[65536], processed[131072];
    pbuf_copy_partial(p, original, p->tot_len, 0);
    size_t len = igareck_process(original, p->tot_len, false, processed, sizeof(processed));
    return !len || enqueue_ip(processed, len, false) ? ERR_OK : ERR_MEM;
}
static err_t init_netif(struct netif *n) {
    n->name[0] = 'i';
    n->name[1] = 'g';
    /* Preserve the baseline TCP MSS calculation. With IP_FRAG=0, lwIP
     * passes whole UDP datagrams to output_ip; physical fragmentation is
     * performed by enqueue_ip only AFTER nfqws2 has seen QUIC Initial. */
    n->mtu = mtu;
    n->output = output_ip;
    n->flags = NETIF_FLAG_BROADCAST;
    return ERR_OK;
}
static void receive_frame(const unsigned char *frame, size_t length) {
    ig_metrics.tap_rx++;
    if (length < 14)
        return;
    size_t offset = 14;
    uint16_t type = r16(frame + 12);
    for (int tags = 0; tags < 2 && (type == 0x8100 || type == 0x88a8); tags++) {
        if (length < offset + 4)
            return;
        type = r16(frame + offset + 2);
        offset += 4;
    }
    if (type == 0x0806) {
        if (length < offset + 28)
            return;
        const unsigned char *a = frame + offset;
        if (r16(a) != 1 || r16(a + 2) != 0x0800 || a[4] != 6 || a[5] != 4 || r16(a + 6) != 1 ||
            memcmp(a + 24, client_ip, 4))
            return;
        unsigned char reply[42] = {0};
        memcpy(reply, a + 8, 6);
        memcpy(reply + 6, client_mac, 6);
        w16(reply + 12, 0x0806);
        w16(reply + 14, 1);
        w16(reply + 16, 0x0800);
        reply[18] = 6;
        reply[19] = 4;
        w16(reply + 20, 2);
        memcpy(reply + 22, client_mac, 6);
        memcpy(reply + 28, client_ip, 4);
        memcpy(reply + 32, a + 8, 6);
        memcpy(reply + 38, a + 14, 4);
        enqueue(reply, sizeof(reply), false, true);
        return;
    }
    if (type != 0x0800 || length < offset + 20)
        return;
    const unsigned char *p = frame + offset;
    size_t size = r16(p + 2), ihl = (p[0] & 15) * 4;
    if ((p[0] >> 4) != 4 || ihl < 20 || size < ihl || size > length - offset)
        return;
    static unsigned char processed[131072];
    size = igareck_process(p, size, true, processed, sizeof(processed));
    if (size)
        stack_input(processed, size);
}

enum client_state { UNUSED, GREETING, REQUEST, DNS_WAIT, CONNECTING, STREAM, REJECT, UDP_ASSOC };
struct client {
    int fd, udp_fd;
    uint32_t probe_mark;
    struct sockaddr_in udp_peer;
    bool udp_peer_set;
    uint64_t udp_received;
    const char *udp_close_reason;
    enum client_state state;
    struct tcp_pcb *pcb;
    unsigned char hello[1024], prefix[10], tx[TX_CAP];
    size_t hello_n, prefix_n, prefix_sent, tx_n, tx_sent, rx_head, rx_n;
    unsigned char *rx;
    uint16_t port;
    uint32_t touched;
    bool eof, fin_sent, remote_fin, write_shutdown, dead;
    unsigned dns_refs;
};
static struct client clients[CLIENTS];
uint32_t igareck_flow_mark(const unsigned char *p,size_t n,bool reverse){
    if(!ig_probe_count||n<40||(p[0]>>4)!=4||p[9]!=6)return 0;
    unsigned ih=(p[0]&15)*4;if(ih<20||n<ih+20)return 0;
    uint16_t local=r16(p+ih+(reverse?2:0));
    for(unsigned i=0;i<CLIENTS;i++){
        struct client *c=&clients[i];
        if(c->state!=UNUSED&&!c->dead&&c->pcb&&c->pcb->local_port==local)return c->probe_mark;
    }
    return 0;
}

static void udp_dispose_client(struct client *c);
static void dispose(struct client *c) {
    udp_dispose_client(c);
    if (c->pcb) {
        struct tcp_pcb *p = c->pcb;
        c->pcb = NULL;
        tcp_arg(p, NULL);
        tcp_recv(p, NULL);
        tcp_err(p, NULL);
        tcp_sent(p, NULL);
        tcp_abort(p);
    }
    if (c->fd >= 0)
        close(c->fd);
    c->fd = -1;
    free(c->rx);
    c->rx = NULL;
    c->state = UNUSED;
    c->dead = true;
    /* dns_refs prevents slot reuse until all asynchronous DNS callbacks return. */
}
static void reject(struct client *c, unsigned char status) {
    c->prefix[0] = 5;
    c->prefix[1] = status;
    c->prefix[2] = 0;
    c->prefix[3] = 1;
    memset(c->prefix + 4, 0, 6);
    c->prefix_n = 10;
    c->prefix_sent = 0;
    c->state = REJECT;
    c->touched = sys_now();
    ig_metrics.socks_rejected++;
}
#include "udp_relay.inc"

static void tcp_error(void *arg, err_t error) {
    struct client *c = arg;
    if (!c)
        return;
    c->pcb = NULL;
    if (error == ERR_CLSD) {
        c->remote_fin = true;
        return;
    }
    printf("[IGARECK_TCP_ERROR] profile=%s err=%d state=%u\n", ig_profile, error, c->state);
    ig_metrics.tcp_fail++;
    if (c->state == CONNECTING)
        reject(c, 5);
    else
        c->dead = true;
}
static err_t tcp_received(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t error) {
    (void)pcb;
    struct client *c = arg;
    if (!c) {
        if (p)
            pbuf_free(p);
        return ERR_OK;
    }
    if (!p) {
        c->remote_fin = true;
        return ERR_OK;
    }
    if (error != ERR_OK)
        return error;
    if (p->tot_len > RX_CAP - c->rx_n)
        return ERR_MEM;
    size_t tail = (c->rx_head + c->rx_n) % RX_CAP, left = p->tot_len;
    size_t first = left < RX_CAP - tail ? left : RX_CAP - tail;
    pbuf_copy_partial(p, c->rx + tail, (u16_t)first, 0);
    if (left > first)
        pbuf_copy_partial(p, c->rx, (u16_t)(left - first), (u16_t)first);
    c->rx_n += left;
    ig_metrics.client_rx += left;
    c->touched = sys_now();
    pbuf_free(p);
    return ERR_OK;
}
static err_t tcp_connected(void *arg, struct tcp_pcb *pcb, err_t error) {
    struct client *c = arg;
    if (!c)
        return ERR_ABRT;
    if (error != ERR_OK) {
        reject(c, 5);
        return error;
    }
    c->state = STREAM;
    c->prefix[0] = 5;
    c->prefix[1] = 0;
    c->prefix[2] = 0;
    c->prefix[3] = 1;
    memcpy(c->prefix + 4, client_ip, 4);
    w16(c->prefix + 8, pcb->local_port);
    c->prefix_n = 10;
    c->prefix_sent = 0;
    c->touched = sys_now();
    ig_metrics.tcp_open++;
    return ERR_OK;
}
static void connect_ip(struct client *c, const ip_addr_t *ip) {
    if (c->dead || c->state == UNUSED)
        return;
    if (!ip || !IP_IS_V4(ip)) {
        reject(c, 4);
        return;
    }
    c->pcb = tcp_new_ip_type(IPADDR_TYPE_V4);
    if (!c->pcb) {
        reject(c, 1);
        return;
    }
    tcp_bind_netif(c->pcb, &iface);
    tcp_arg(c->pcb, c);
    tcp_recv(c->pcb, tcp_received);
    tcp_err(c->pcb, tcp_error);
    tcp_nagle_disable(c->pcb);
    c->state = CONNECTING;
    c->touched = sys_now();
    err_t e = tcp_connect(c->pcb, ip, c->port, tcp_connected);
    if (e != ERR_OK) {
        ig_metrics.tcp_fail++;
        tcp_arg(c->pcb, NULL);
        tcp_err(c->pcb, NULL);
        tcp_abort(c->pcb);
        c->pcb = NULL;
        reject(c, 5);
    }
}
static void dns_ready(const char *name, const ip_addr_t *address, void *arg) {
    (void)name;
    struct client *c = arg;
    if (c->dns_refs)
        c->dns_refs--;
    if (c->state == DNS_WAIT && !c->dead)
        connect_ip(c, address);
}
static void consume(struct client *c, size_t n) {
    memmove(c->hello, c->hello + n, c->hello_n - n);
    c->hello_n -= n;
}
static void parse_socks(struct client *c) {
    if (c->state == GREETING) {
        if (c->hello_n < 2)
            return;
        unsigned n = c->hello[1];
        if (c->hello[0] != 5 || n == 0) {
            c->dead = true;
            return;
        }
        if (c->hello_n < n + 2)
            return;
        bool okay = memchr(c->hello + 2, 0, n) != NULL;
        c->prefix[0] = 5;
        c->prefix[1] = okay ? 0 : 255;
        c->prefix_n = 2;
        c->prefix_sent = 0;
        consume(c, n + 2);
        c->state = okay ? REQUEST : REJECT;
    }
    if (c->state != REQUEST || c->prefix_sent < c->prefix_n || c->hello_n < 4)
        return;
    if (c->hello[0] != 5 || c->hello[2] != 0) {
        reject(c, 1);
        return;
    }
    unsigned command=c->hello[1];
    if (command != 1 && command != 3) {
        reject(c, 7);
        return;
    }
    unsigned type = c->hello[3];
    size_t end;
    char name[256];
    ip_addr_t ip;
    if (type == 1) {
        end = 10;
        if (c->hello_n < end)
            return;
        IP_ADDR4(&ip, c->hello[4], c->hello[5], c->hello[6], c->hello[7]);
    } else if (type == 3) {
        if (c->hello_n < 5)
            return;
        unsigned size = c->hello[4];
        if (!size) {
            reject(c, 8);
            return;
        }
        end = 7 + size;
        if (c->hello_n < end)
            return;
        if (memchr(c->hello + 5, 0, size)) {
            reject(c, 8);
            return;
        }
        memcpy(name, c->hello + 5, size);
        name[size] = 0;
    } else {
        reject(c, 8);
        return;
    }
    c->port = r16(c->hello + end - 2);
    if (!c->port && command!=3) {
        reject(c, 1);
        return;
    }
    consume(c, end);
    if(command==3){
        if(type!=1 || c->hello_n){reject(c,8);return;}
        udp_associate(c,&ip,c->port);return;
    }
    /* A pipelined ClientHello is retained and enters the TCP stream after connect. */
    if (c->hello_n) {
        memcpy(c->tx, c->hello, c->hello_n);
        c->tx_n = c->hello_n;
        c->hello_n = 0;
    }
    if (type == 1)
        connect_ip(c, &ip);
    else {
        c->state = DNS_WAIT;
        c->dns_refs++;
        err_t e = dns_gethostbyname_addrtype(name, &ip, dns_ready, c, LWIP_DNS_ADDRTYPE_IPV4);
        if (e != ERR_INPROGRESS) {
            c->dns_refs--;
            if (e == ERR_OK)
                connect_ip(c, &ip);
            else
                reject(c, 4);
        }
    }
}
static void socket_output(struct client *c) {
    if (c->prefix_sent < c->prefix_n) {
        ssize_t n =
            send(c->fd, c->prefix + c->prefix_sent, c->prefix_n - c->prefix_sent, MSG_NOSIGNAL);
        if (n > 0)
            c->prefix_sent += (size_t)n;
        else if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            c->dead = true;
        if (c->prefix_sent < c->prefix_n || c->dead)
            return;
    }
    if (c->state == REJECT) {
        c->dead = true;
        return;
    }
    if (c->state == REQUEST) {
        parse_socks(c);
        return;
    }
    if (c->state != STREAM)
        return;
    if (c->rx_n) {
        size_t first = c->rx_n < RX_CAP - c->rx_head ? c->rx_n : RX_CAP - c->rx_head;
        if (first > 65535)
            first = 65535;
        ssize_t n = send(c->fd, c->rx + c->rx_head, first, MSG_NOSIGNAL);
        if (n > 0) {
            c->rx_head = (c->rx_head + (size_t)n) % RX_CAP;
            c->rx_n -= (size_t)n;
            c->touched = sys_now();
            if (c->pcb)
                tcp_recved(c->pcb, (u16_t)n);
        } else if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            c->dead = true;
    }
    if (c->remote_fin && !c->rx_n && !c->write_shutdown) {
        shutdown(c->fd, SHUT_WR);
        c->write_shutdown = true;
    }
}
static void tcp_output_pending(struct client *c) {
    if (c->state != STREAM)
        return;
    if (!c->pcb) {
        if (c->remote_fin && c->eof && !c->rx_n)
            c->dead = true;
        return;
    }
    if (c->tx_sent < c->tx_n) {
        size_t n = c->tx_n - c->tx_sent;
        u16_t space = tcp_sndbuf(c->pcb);
        if (n > space)
            n = space;
        if (n > 65535)
            n = 65535;
        if (n) {
            err_t e = tcp_write(c->pcb, c->tx + c->tx_sent, (u16_t)n, TCP_WRITE_FLAG_COPY);
            if (e == ERR_OK) {
                c->tx_sent += n;
                ig_metrics.client_tx += n;
            } else if (e != ERR_MEM)
                c->dead = true;
        }
        tcp_output(c->pcb);
        if (c->tx_sent == c->tx_n)
            c->tx_sent = c->tx_n = 0;
    }
    if (c->eof && !c->tx_n && !c->fin_sent) {
        if (tcp_shutdown(c->pcb, 0, 1) == ERR_OK)
            c->fin_sent = true;
    }
    if (c->remote_fin && c->eof && !c->rx_n && !c->tx_n) {
        struct tcp_pcb *p = c->pcb;
        tcp_arg(p, NULL);
        tcp_recv(p, NULL);
        tcp_err(p, NULL);
        if (tcp_close(p) == ERR_OK) {
            c->pcb = NULL;
            c->dead = true;
        } else {
            tcp_arg(p, c);
            tcp_recv(p, tcp_received);
            tcp_err(p, tcp_error);
        }
    }
}
static void socket_input(struct client *c) {
    if (c->state == GREETING || c->state == REQUEST) {
        if (c->hello_n == sizeof(c->hello)) {
            c->dead = true;
            return;
        }
        ssize_t n = recv(c->fd, c->hello + c->hello_n, sizeof(c->hello) - c->hello_n, 0);
        if (n > 0) {
            c->hello_n += (size_t)n;
            c->touched = sys_now();
            parse_socks(c);
        } else if (!n)
            c->dead = true;
        else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            c->dead = true;
    } else if(c->state==UDP_ASSOC){
        unsigned char bytes[64];ssize_t n=recv(c->fd,bytes,sizeof(bytes),0);
        if(n>=0 || (errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR)){
            c->udp_close_reason=!n?"tcp_control_eof":n>0?"unexpected_tcp_control_data":"tcp_control_error";
            c->dead=true;
        }
    } else if (c->state == STREAM && !c->eof && !c->tx_n) {
        ssize_t n = recv(c->fd, c->tx, sizeof(c->tx), 0);
        if (n > 0) {
            c->tx_n = (size_t)n;
            c->tx_sent = 0;
            c->touched = sys_now();
            tcp_output_pending(c);
        } else if (!n) {
            c->eof = true;
            tcp_output_pending(c);
        } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            c->dead = true;
    }
}
static int receive_config(void) {
    unsigned char cfg[34];
    int carrier = -1;
    ssize_t n;
#ifdef IGARECK_HOST_TEST
    if (ig_test_fd >= 0) {
        tap_fd = ig_test_fd;
        n = read(tap_fd, cfg, sizeof(cfg));
    } else
#endif
    {
#ifdef IGARECK_HOST_TEST
        if (ig_test_config_fd >= 0)
            carrier = ig_test_config_fd;
        else
#endif
        {
            carrier = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
            if (carrier < 0)
                return -1;
            struct sockaddr_un addr = {.sun_family = AF_UNIX};
            size_t len = strlen(ig_socket);
            if (!len || len >= sizeof(addr.sun_path) - 1) {
                close(carrier);
                return -1;
            }
            memcpy(addr.sun_path + 1, ig_socket, len);
            if (connect(carrier, (struct sockaddr *)&addr,
                        (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + len))) {
                close(carrier);
                return -1;
            }
        }
        struct ucred peer;
        socklen_t pl = sizeof(peer);
        if (getsockopt(carrier, SOL_SOCKET, SO_PEERCRED, &peer, &pl) || peer.uid != getuid()) {
            close(carrier);
            return -1;
        }
        struct timeval timeout = {.tv_sec = 8};
        setsockopt(carrier, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        char control[CMSG_SPACE(sizeof(int))];
        struct iovec io = {.iov_base = cfg, .iov_len = sizeof(cfg)};
        struct msghdr msg = {.msg_iov = &io,
                             .msg_iovlen = 1,
                             .msg_control = control,
                             .msg_controllen = sizeof(control)};
        n = recvmsg(carrier, &msg, MSG_WAITALL | MSG_CMSG_CLOEXEC);
        if (!(msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC)))
            for (struct cmsghdr *cm = CMSG_FIRSTHDR(&msg); cm; cm = CMSG_NXTHDR(&msg, cm))
                if (cm->cmsg_level == SOL_SOCKET && cm->cmsg_type == SCM_RIGHTS &&
                    cm->cmsg_len == CMSG_LEN(sizeof(int)))
                    memcpy(&tap_fd, CMSG_DATA(cm), sizeof(tap_fd));
        close(carrier);
    }
    if (n != sizeof(cfg) || tap_fd < 0 || memcmp(cfg, "IGTAP4\0\1", 8)) {
        fprintf(stderr, "[IGARECK_TAP_CONFIG_ERROR]\n");
        return -1;
    }
    memcpy(client_ip, cfg + 8, 4);
    memcpy(gateway_ip, cfg + 12, 4);
    memcpy(dns_ip, cfg + 16, 4);
    memcpy(client_mac, cfg + 20, 6);
    memcpy(gateway_mac, cfg + 26, 6);
    mtu = r16(cfg + 32);
    if (mtu < 576 || mtu > 1500 || !client_ip[0] || client_ip[0] >= 224 || !dns_ip[0])
        return -1;
    nonblock(tap_fd);
    return 0;
}
int igareck_tap_loop(void) {
    int result = 1;
    if (receive_config())
        goto out;
    if (igareck_core_init())
        goto out;
    lwip_init();
    ip4_addr_t local, mask, gw;
    memcpy(&local.addr, client_ip, 4);
    IP4_ADDR(&mask, 255, 255, 255, 0);
    memcpy(&gw.addr, gateway_ip, 4);
    if (!netif_add(&iface, &local, &mask, &gw, NULL, init_netif, ip4_input))
        goto core_out;
    netif_set_default(&iface);
    netif_set_up(&iface);
    netif_set_link_up(&iface);
    ip_addr_t dns;
    IP_ADDR4(&dns, dns_ip[0], dns_ip[1], dns_ip[2], dns_ip[3]);
    dns_setserver(0, &dns);
    listen_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listen_fd < 0)
        goto net_out;
    int reuse = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in address = {.sin_family = AF_INET,
                                  .sin_port = htons((uint16_t)ig_port),
                                  .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (bind(listen_fd, (struct sockaddr *)&address, sizeof(address))) {
        fprintf(stderr, "[IGARECK_BIND_ERROR] errno=%d\n", errno);
        goto net_out;
    }
    if (listen(listen_fd, 64))
        goto net_out;
    nonblock(listen_fd);
    if(ig_probe_count){
        socklen_t length=sizeof(address);
        if(getsockname(listen_fd,(struct sockaddr *)&address,&length))goto net_out;
        ig_port=ntohs(address.sin_port);
        printf("[IGARECK_PROBE_PORT] profile=T%u port=%u instance=%s\n",ig_probe_ids[0],ig_port,ig_instance);
        for(unsigned li=1;li<ig_probe_count;li++){
            int fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);probe_listeners[li-1]=fd;
            address.sin_port=0;
            if(fd<0||bind(fd,(struct sockaddr *)&address,sizeof(address))||listen(fd,16))goto net_out;
            nonblock(fd);length=sizeof(address);
            if(getsockname(fd,(struct sockaddr *)&address,&length))goto net_out;
            printf("[IGARECK_PROBE_PORT] profile=T%u port=%u instance=%s\n",ig_probe_ids[li],ntohs(address.sin_port),ig_instance);
        }
    }

    for (int i = 0; i < CLIENTS; i++)
        clients[i].fd = clients[i].udp_fd = -1;
    printf("[IGARECK_LISTEN_READY] profile=%s instance=%s port=%u core=nfqws2 tcp_stack=lwIP-2.2.1 "
           "ipv4=true udp_associate=%s quic=%s discord=%s ready=true\n",
           ig_profile, ig_instance, ig_port, ig_udp_enabled?"true":"false",ig_quic_profile,ig_discord_profile);
    igareck_print_metrics("READY");
    uint32_t metrics_at = sys_now() + 5000;
    int control_fd = STDIN_FILENO;
    char control_line[128];
    size_t control_n = 0;
    nonblock(control_fd);
    while (!bQuit && !fatal_transport) {
        sys_check_timeouts();
        for(unsigned i=0;i<UDP_FLOWS;i++)if(udp_flows[i].active && sys_now()-udp_flows[i].touched>UDP_IDLE_MS)udp_flow_close(&udp_flows[i]);
        igareck_core_tick();
        flush_packets();
        struct pollfd pf[CLIENTS * 2 + 7];
        bool udp_event[CLIENTS * 2 + 7]={0};
        struct client *map[CLIENTS * 2 + 7];
        unsigned used = 3;
        pf[0] =
            (struct pollfd){tap_fd, (short)(POLLIN | (qhead && !qhead->stack ? POLLOUT : 0)), 0};
        pf[1] = (struct pollfd){listen_fd, POLLIN, 0};
        map[0] = map[1] = NULL;
        pf[2] = (struct pollfd){control_fd, POLLIN, 0};
        map[2] = NULL;
        for(unsigned li=1;li<ig_probe_count;li++){
            pf[used]=(struct pollfd){probe_listeners[li-1],POLLIN,0};map[used++]=NULL;
        }
        unsigned clients_start=used;

        for (int i = 0; i < CLIENTS; i++) {
            struct client *c = &clients[i];
            if (c->state == UNUSED)
                continue;
            if (c->dead) {
                dispose(c);
                continue;
            }
            tcp_output_pending(c);
            if (c->dead) {
                dispose(c);
                continue;
            }
            uint32_t age = sys_now() - c->touched;
            if (c->state!=UDP_ASSOC && age > (c->state == STREAM ? 180000 : 20000)) {
                if (c->state == STREAM)
                    c->dead = true;
                else
                    reject(c, 6);
            }
            short events = 0;
            if ((c->state == GREETING || c->state == REQUEST || c->state==UDP_ASSOC) ||
                (c->state == STREAM && !c->eof && !c->tx_n))
                events |= POLLIN;
            if (c->prefix_sent < c->prefix_n || c->rx_n)
                events |= POLLOUT;
            pf[used] = (struct pollfd){c->fd, events, 0};
            map[used] = c;
            used++;
            if(c->udp_fd>=0){pf[used]=(struct pollfd){c->udp_fd,POLLIN,0};map[used]=c;udp_event[used++]=true;}
        }
        int ready = poll(pf, used, 10);
        if (ready < 0 && errno != EINTR) {
            fprintf(stderr, "poll failed errno=%d\n", errno);
            break;
        }
        if (pf[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            fprintf(stderr, "[IGARECK_TAP_CLOSED]\n");
            fatal_transport = 1;
            break;
        }
        if (pf[0].revents & POLLIN) {
            for (unsigned i = 0; i < 64; i++) {
                unsigned char frame[65550];
                ssize_t n = read(tap_fd, frame, sizeof(frame));
                if (n > 0)
                    receive_frame(frame, (size_t)n);
                else {
                    if (!n || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR))
                        fatal_transport = 1;
                    break;
                }
            }
        }
        if (pf[2].revents & (POLLIN | POLLHUP)) {
            char bytes[128];
            ssize_t n = read(control_fd, bytes, sizeof(bytes));
            if (n <= 0)
                control_fd = -1;
            else
                for (ssize_t i = 0; i < n; i++) {
                    if (bytes[i] == '\n') {
                        control_line[control_n] = 0;
                        if (!strcmp(control_line, "snapshot"))
                            igareck_print_metrics("SNAPSHOT");
                        control_n = 0;
                    } else if (control_n < sizeof(control_line) - 1)
                        control_line[control_n++] = bytes[i];
                    else
                        control_n = 0;
                }
        }
        for(unsigned li=0;li<(ig_probe_count?ig_probe_count:1);li++){
        unsigned pi=li?2+li:1;
        if (pf[pi].revents & POLLIN) {
            for (;;) {
                int listener=li?probe_listeners[li-1]:listen_fd;
                int fd = accept4(listener, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
                if (fd < 0)
                    break;
                struct client *slot = NULL;
                for (int i = 0; i < CLIENTS; i++)
                    if (clients[i].state == UNUSED && !clients[i].dns_refs) {
                        slot = &clients[i];
                        break;
                    }
                if (!slot) {
                    close(fd);
                    continue;
                }
                memset(slot, 0, sizeof(*slot));
                slot->fd = fd;slot->udp_fd=-1;slot->probe_mark=ig_probe_count?ig_probe_ids[li]:0;
                slot->rx = malloc(RX_CAP);
                slot->state = GREETING;
                slot->touched = sys_now();
                if (!slot->rx)
                    dispose(slot);
            }
        }
        }
        for (unsigned i = clients_start; i < used; i++) {
            struct client *c = map[i];
            if (c->state == UNUSED || c->dead)
                continue;
            if(udp_event[i]){
                if(pf[i].revents & POLLIN)relay_udp_input(c);
                if(pf[i].revents & (POLLERR|POLLHUP|POLLNVAL)){
                    if(!c->dead){
                        int error=0;socklen_t length=sizeof(error);getsockopt(c->udp_fd,SOL_SOCKET,SO_ERROR,&error,&length);
                        ig_metrics.udp_socket_errors++;
                        if(ig_metrics.udp_socket_errors<=24)printf("[IGARECK_UDP_SOCKET_ERROR] stage=poll errno=%d revents=%u instance=%s\n",error,(unsigned)pf[i].revents,ig_instance);
                    }
                    c->udp_close_reason="udp_poll_error";c->dead=true;
                }
                if(c->dead)dispose(c);
                continue;
            }
            if (pf[i].revents & POLLOUT)
                socket_output(c);
            if (!c->dead && (pf[i].revents & POLLIN))
                socket_input(c);
            if (pf[i].revents & (POLLERR | POLLNVAL)) {
                if(c->state==UDP_ASSOC)c->udp_close_reason="tcp_poll_error";
                c->dead = true;
            }
            if ((pf[i].revents & POLLHUP) && !(pf[i].revents & POLLIN)) {
                if (c->state == STREAM) {
                    c->eof = true;
                    tcp_output_pending(c);
                } else {
                    if(c->state==UDP_ASSOC)c->udp_close_reason="tcp_control_hup";
                    c->dead = true;
                }
            }
            if (c->dead)
                dispose(c);
        }
        flush_packets();
        if ((int32_t)(sys_now() - metrics_at) >= 0) {
            igareck_print_metrics("PERIODIC");
            metrics_at = sys_now() + 5000;
        }
    }
    result = fatal_transport ? 1 : 0;
    for (int i = 0; i < CLIENTS; i++)
        if (clients[i].state != UNUSED)
            dispose(&clients[i]);
    igareck_print_metrics("STOP");
net_out:
    netif_set_down(&iface);
    netif_remove(&iface);
core_out:
    igareck_core_close();
out:
    if (listen_fd >= 0)
        close(listen_fd);
    for(unsigned li=0;li<4;li++)if(probe_listeners[li]>=0)close(probe_listeners[li]);
    if (tap_fd >= 0)
        close(tap_fd);
    while (qhead) {
        struct packet *p = qhead;
        qhead = p->next;
        free(p);
    }
    qtail = NULL;
    return result;
}
