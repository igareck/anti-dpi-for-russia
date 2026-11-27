/* SOCKS5 UDP relay. Included in the single-owner lwIP/TAP event loop.
 * OS UDP sockets bind only to loopback; internet datagrams use lwIP and nfqws2. */
#define UDP_FLOWS 256
#define UDP_IDLE_MS 120000
struct udp_flow {
    struct client *owner;
    struct udp_pcb *pcb;
    ip_addr_t address;
    char host[256];
    uint16_t port;
    uint32_t touched;
    bool active;
    unsigned dns_refs;
    unsigned char *pending;
    size_t pending_n;
};
static struct udp_flow udp_flows[UDP_FLOWS];

static void udp_flow_close(struct udp_flow *f) {
    if (f->pcb) { udp_remove(f->pcb); f->pcb = NULL; }
    free(f->pending); f->pending = NULL; f->pending_n = 0;
    if (f->active) { f->active = false; ig_metrics.udp_flows_active--; }
    f->owner = NULL;
}
static void udp_dispose_client(struct client *c) {
    for (unsigned i = 0; i < UDP_FLOWS; i++)
        if (udp_flows[i].active && udp_flows[i].owner == c) udp_flow_close(&udp_flows[i]);
    if (c->udp_fd >= 0) {
        ig_metrics.udp_assoc_closed++;
        if(!c->udp_received)ig_metrics.udp_assoc_empty++;
        if(ig_metrics.udp_assoc_closed<=64)printf("[IGARECK_UDP_ASSOC_CLOSE] discord=%s received=%llu reason=%s instance=%s\n",
            ig_discord_profile,(unsigned long long)c->udp_received,c->udp_close_reason?c->udp_close_reason:"cleanup",ig_instance);
        close(c->udp_fd); c->udp_fd = -1;
        ig_metrics.udp_associations_active--;
    }
}
static void udp_answer(void *arg, struct udp_pcb *pcb, struct pbuf *p,
                       const ip_addr_t *from, u16_t port) {
    (void)pcb;
    struct udp_flow *f = arg;
    struct client *c = f->owner;
    if (!p) return;
    if (!f->active || !c || c->dead || c->state != UDP_ASSOC || !c->udp_peer_set ||
        !IP_IS_V4(from) || p->tot_len > 65497) {
        ig_metrics.udp_dropped++; pbuf_free(p); return;
    }
    static unsigned char reply[65507];
    memset(reply, 0, 3); reply[3] = 1;
    memcpy(reply + 4, &ip_2_ip4(from)->addr, 4); w16(reply + 8, port);
    pbuf_copy_partial(p, reply + 10, p->tot_len, 0);
    ssize_t n = sendto(c->udp_fd, reply, p->tot_len + 10, MSG_NOSIGNAL,
                       (struct sockaddr *)&c->udp_peer, sizeof(c->udp_peer));
    if (n == p->tot_len + 10) {
        ig_metrics.udp_rx++; ig_metrics.udp_rx_bytes += p->tot_len;
        f->touched = c->touched = sys_now();
    } else ig_metrics.udp_dropped++;
    pbuf_free(p);
}
static bool udp_flow_connect(struct udp_flow *f, const ip_addr_t *address) {
    if (!address || !IP_IS_V4(address)) return false;
    unsigned first=ntohl(ip_2_ip4(address)->addr)>>24;
    if (!first || first==127 || first>=224) return false;
    f->pcb = udp_new_ip_type(IPADDR_TYPE_V4);
    if (!f->pcb) return false;
    udp_bind_netif(f->pcb, &iface);
    if (udp_bind(f->pcb, IP_ANY_TYPE, 0) != ERR_OK ||
        udp_connect(f->pcb, address, f->port) != ERR_OK) {
        udp_remove(f->pcb); f->pcb = NULL; return false;
    }
    f->address = *address;
    udp_recv(f->pcb, udp_answer, f);
    return true;
}
static void udp_flow_send(struct udp_flow *f, const void *data, size_t length) {
    if (!f->active || !f->pcb || !f->owner || f->owner->dead) return;
    struct pbuf *p = pbuf_alloc(PBUF_TRANSPORT, (u16_t)length, PBUF_RAM);
    if (!p) { ig_metrics.udp_dropped++; return; }
    err_t e = length ? pbuf_take(p, data, (u16_t)length) : ERR_OK;
    if (e == ERR_OK) e = udp_send(f->pcb, p);
    pbuf_free(p);
    if (e == ERR_OK) {
        ig_metrics.udp_tx++; ig_metrics.udp_tx_bytes += length;
        f->touched = f->owner->touched = sys_now();
    } else ig_metrics.udp_dropped++;
}
static void udp_dns_ready(const char *name, const ip_addr_t *address, void *arg) {
    (void)name;
    struct udp_flow *f = arg;
    if (f->dns_refs) f->dns_refs--;
    if (!f->active) return;
    if (!udp_flow_connect(f, address)) {
        ig_metrics.udp_dns_fail++; udp_flow_close(f); return;
    }
    udp_flow_send(f, f->pending, f->pending_n);
    free(f->pending); f->pending = NULL; f->pending_n = 0;
}
static void udp_associate(struct client *c, const ip_addr_t *requested, uint16_t port) {
    if (!ig_udp_enabled) { reject(c, 7); return; }
    socklen_t size = sizeof(c->udp_peer);
    if (getpeername(c->fd, (struct sockaddr *)&c->udp_peer, &size) ||
        c->udp_peer.sin_family != AF_INET ||
        (!ip4_addr_isany_val(*ip_2_ip4(requested)) &&
         ip_2_ip4(requested)->addr != c->udp_peer.sin_addr.s_addr)) { reject(c, 2); return; }
    c->udp_peer.sin_port = htons(port); c->udp_peer_set = port != 0;
    c->udp_fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (c->udp_fd < 0) { reject(c, 1); return; }
    struct sockaddr_in local = {.sin_family=AF_INET, .sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    if (bind(c->udp_fd, (struct sockaddr *)&local, sizeof(local))) {
        close(c->udp_fd); c->udp_fd = -1; reject(c, 1); return;
    }
    size = sizeof(local);
    if (getsockname(c->udp_fd, (struct sockaddr *)&local, &size)) {
        close(c->udp_fd); c->udp_fd = -1; reject(c, 1); return;
    }
    nonblock(c->udp_fd);
    int buffer = 1024 * 1024;
    setsockopt(c->udp_fd, SOL_SOCKET, SO_RCVBUF, &buffer, sizeof(buffer));
    c->state = UDP_ASSOC; c->touched = sys_now();
    c->prefix[0]=5; c->prefix[1]=0; c->prefix[2]=0; c->prefix[3]=1;
    memcpy(c->prefix+4, &local.sin_addr.s_addr, 4);
    memcpy(c->prefix+8, &local.sin_port, 2);
    c->prefix_n=10; c->prefix_sent=0;
    ig_metrics.udp_associations++; ig_metrics.udp_associations_active++;
    printf("[IGARECK_UDP_ASSOCIATE] profile=%s quic=%s discord=%s relay=127.0.0.1:%u instance=%s\n",
           ig_profile, ig_quic_profile,ig_discord_profile,ntohs(local.sin_port),ig_instance);
}
static void relay_udp_input(struct client *c) {
    static unsigned char data[65536];
    for (unsigned count=0; count<32; count++) {
        struct sockaddr_in source; socklen_t sl=sizeof(source);
        ssize_t got=recvfrom(c->udp_fd, data, sizeof(data), 0, (struct sockaddr *)&source, &sl);
        if (got<0) {
            if (errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR) {
                ig_metrics.udp_socket_errors++;
                if(ig_metrics.udp_socket_errors<=24)printf("[IGARECK_UDP_SOCKET_ERROR] stage=recv errno=%d instance=%s\n",errno,ig_instance);
                c->udp_close_reason="udp_recv_error";c->dead=true;
            }
            return;
        }
        c->udp_received++;ig_metrics.udp_local_rx++;ig_metrics.udp_local_rx_bytes+=(size_t)got;
        if(ig_metrics.udp_local_rx<=24)printf("[IGARECK_UDP_LOCAL_RX] size=%zd source_port=%u atyp=%u instance=%s\n",got,ntohs(source.sin_port),got>=4?data[3]:0,ig_instance);
        size_t n=(size_t)got, header;
        if (source.sin_addr.s_addr != c->udp_peer.sin_addr.s_addr ||
            (c->udp_peer_set && source.sin_port != c->udp_peer.sin_port) ||
            n<4 || data[0] || data[1] || data[2]) { ig_metrics.udp_rejected++; continue; }
        ip_addr_t destination; char host[256]="";
        if (data[3]==1) {
            header=10; if(n<header){ig_metrics.udp_rejected++;continue;}
            IP_ADDR4(&destination,data[4],data[5],data[6],data[7]);
            if(!data[4] || data[4]==127 || data[4]>=224){ig_metrics.udp_rejected++;continue;}
        } else if(data[3]==3) {
            if(n<5 || !data[4]){ig_metrics.udp_rejected++;continue;}
            header=7+data[4];
            if(n<header || memchr(data+5,0,data[4])){ig_metrics.udp_rejected++;continue;}
            memcpy(host,data+5,data[4]); host[data[4]]=0;
        } else { ig_metrics.udp_rejected++; continue; }
        uint16_t port=r16(data+header-2);
        if(!port){ig_metrics.udp_rejected++;continue;}
        if(!c->udp_peer_set){c->udp_peer.sin_port=source.sin_port;c->udp_peer_set=true;}
        struct udp_flow *flow=NULL,*slot=NULL;
        uint32_t now=sys_now();
        for(unsigned i=0;i<UDP_FLOWS;i++) {
            struct udp_flow *f=&udp_flows[i];
            if(f->active && now-f->touched>UDP_IDLE_MS) udp_flow_close(f);
            if(!f->active && !f->dns_refs){if(!slot)slot=f;continue;}
            if(f->active && f->owner==c && f->port==port && !strcmp(f->host,host) &&
                (host[0] || ip_addr_cmp(&f->address,&destination))) {flow=f;break;}
        }
        if(!flow) {
            if(!slot){ig_metrics.udp_dropped++;continue;}
            flow=slot;memset(flow,0,sizeof(*flow));flow->active=true;flow->owner=c;
            flow->port=port;flow->touched=now;strcpy(flow->host,host);ig_metrics.udp_flows_active++;
            if(host[0]) {
                flow->pending_n=n-header;flow->pending=malloc(flow->pending_n?flow->pending_n:1);
                if(!flow->pending){ig_metrics.udp_dropped++;udp_flow_close(flow);continue;}
                memcpy(flow->pending,data+header,flow->pending_n);flow->dns_refs++;
                err_t e=dns_gethostbyname_addrtype(host,&destination,udp_dns_ready,flow,LWIP_DNS_ADDRTYPE_IPV4);
                if(e!=ERR_INPROGRESS){flow->dns_refs--;if(e==ERR_OK && udp_flow_connect(flow,&destination)){
                    udp_flow_send(flow,flow->pending,flow->pending_n);free(flow->pending);flow->pending=NULL;flow->pending_n=0;
                } else {ig_metrics.udp_dns_fail++;udp_flow_close(flow);}}
                continue;
            }
            if(!udp_flow_connect(flow,&destination)){ig_metrics.udp_dropped++;udp_flow_close(flow);continue;}
        }
        if(flow->pcb) udp_flow_send(flow,data+header,n-header);
        else ig_metrics.udp_dropped++; /* bounded one-datagram DNS wait */
    }
}
