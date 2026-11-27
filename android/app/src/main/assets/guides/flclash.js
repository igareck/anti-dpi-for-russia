function main(config) {
  const localName = "Igareck_AntiDPI_Local";
  const proxies = (config.proxies || [])
    .filter((proxy) => proxy.name !== localName);

  for (const proxy of proxies) {
    proxy["dialer-proxy"] = localName;
  }

  proxies.push({
    name: localName,
    type: "socks5",
    server: "127.0.0.1",
    port: 1080,
    udp: true
  });
  config.proxies = proxies;

  const localPattern = "^" + localName + "$";
  for (const group of config["proxy-groups"] || []) {
    if (!group["include-all"] && !group["include-all-proxies"]) continue;

    const previous = group["exclude-filter"] || "";
    const filters = previous.split("`").filter(Boolean);
    if (!filters.includes(localPattern)) {
      filters.push(localPattern);
    }
    group["exclude-filter"] = filters.join("`");
  }

  return config;
}
