# Cross-Distribution Linux Compatibility & Architecture Guide

This document outlines architectural improvements and installation strategies to make **Igareck AntiDPI (Zapret2)** universally compatible across all major Linux distributions (Arch Linux, openSUSE, Alpine, Void, Debian, Ubuntu, Fedora, RHEL, NixOS, and immutable OSes like SteamOS and Fedora Silverblue).

---

## 1. Friction Points in the Current Architecture

The original Linux design was built specifically for Ubuntu/Debian, introducing assumptions that create friction on other distributions:

### A. Compiling from Source on the Target Machine
- **Current Behavior:** The installer (`install.py`) downloads Bol-Van's C source code and runs `make -C nfq2` directly on the target machine.
- **Problem:**
  - Requires **10+ build tools and development headers** (`gcc`, `make`, `pkg-config`, `libnetfilter-queue-dev`, `libnfnetlink-dev`, `libmnl-dev`, `zlib1g-dev`, `libcap-dev`, `libluajit-5.1-dev`).
  - Package naming varies wildly across distributions:
    | Component | Debian / Ubuntu | Fedora / RHEL | Arch Linux | openSUSE | Alpine Linux |
    | :--- | :--- | :--- | :--- | :--- | :--- |
    | Compiler toolchain | `build-essential` | `gcc` + `make` | `base-devel` | `devel_basis` | `build-base` |
    | Netfilter Queue | `libnetfilter-queue-dev` | `libnetfilter_queue-devel` | `libnetfilter_queue` | `libnetfilter_queue-devel` | `libnetfilter_queue-dev` |
    | Netfilter Netlink | `libnfnetlink-dev` | `libnfnetlink-devel` | `libnfnetlink` | `libnfnetlink-devel` | `libnfnetlink-dev` |
    | MNL Library | `libmnl-dev` | `libmnl-devel` | `libmnl` | `libmnl-devel` | `libmnl-dev` |
    | LuaJIT | `libluajit-5.1-dev` | `luajit-devel` | `luajit` | `libluajit-5_1-devel` | `luajit-dev` |
    | Zlib | `zlib1g-dev` | `zlib-devel` | `zlib` | `zlib-devel` | `zlib-dev` |
    | Cap Library | `libcap-dev` | `libcap-devel` | `libcap` | `libcap-devel` | `libcap-dev` |
  - Arch Linux does not split development headers into `-dev`/`-devel` packages.
  - Alpine Linux uses `musl` libc instead of `glibc`.
  - Forcing compilation requires internet access to GitHub during the installation step and pollutes the target system with compilers and header files.

### B. Hard Dependency on GNOME Terminal
- **Current Behavior:** `terminal_bootstrap.py` searches specifically for `/usr/libexec/gnome-terminal-server`, communicates with GNOME Terminal via DBus, and injects a custom Fontconfig configuration for terminal ASCII art.
- **Problem:** Non-GNOME environments (KDE Plasma with `konsole`, XFCE with `xfce4-terminal`, MATE, Cinnamon, Sway/i3/Hyprland with `kitty`/`alacritty`/`foot`, or headless servers) fail or require installing GNOME Terminal.

### C. Ad-Hoc Script Installation vs Native Package Management
- **Current Behavior:** `INSTALL.sh` invokes a Python script that calls package managers with `sudo`, copies files to `/opt/`, and manually tracks installations in `installation.json`.
- **Problem:** Native system package managers cannot track files, verify package signatures, or handle automatic upgrades.

---

## 2. Recommended Architectural Solutions

### Strategy 1: Precompiled Static `nfqws2` Binary (Highest Impact)

> [!TIP]
> Bol-Van's official Zapret2 release packages already contain **fully statically linked standalone ELF binaries** for `linux-x86_64` and `linux-arm64`.

Bol-Van builds `nfqws2` statically using `musl-libc`, embedding LuaJIT, libnetfilter_queue, libnfnetlink, libmnl, and zlib into a single executable without external shared library dependencies.

The Windows version of this project (`windows/`) already bundles precompiled `winws2.exe`. Adopting the same pattern on Linux eliminates the compilation step completely:

```
Before (13 dependencies):
  gcc, make, pkg-config, libnetfilter-queue-dev(el), libnfnetlink-dev(el),
  libmnl-dev(el), zlib-dev(el), libcap-dev(el), luajit-dev(el),
  python3, iproute2, nftables, kmod

After (3 runtime dependencies):
  python3, iproute2, nftables
```

#### Benefits:
- **Zero compiler toolchain required** on the user's machine.
- **Zero development headers required**.
- Compatible out-of-the-box with Arch, Fedora, Debian, Ubuntu, openSUSE, Alpine, Void, etc.
- Installation takes under 2 seconds (no compile or package repository cache wait time).

---

### Strategy 2: Terminal-Agnostic Launcher & Graceful CLI Fallback

Replace the GNOME-specific launch mechanism with a standard FreeDesktop approach:

1. **Adopt `xdg-terminal-exec` & Emulator Detection:**
   ```python
   def get_terminal_emulator():
       candidates = [
           ('xdg-terminal-exec', ['--']),
           ('x-terminal-emulator', ['-e']),
           ('gnome-terminal', ['--']),
           ('konsole', ['-e']),
           ('xfce4-terminal', ['-x']),
           ('kitty', ['--']),
           ('alacritty', ['-e']),
           ('foot', ['--']),
       ]
       for binary, flag in candidates:
           if shutil.which(binary):
               return binary, flag
       return None, []
   ```
2. **Graceful Fallback:**
   - If a graphical terminal is available and the user clicks a desktop shortcut, open their preferred terminal emulator.
   - If running from an existing shell, run directly inside the current terminal without spawning a new window.
   - Treat custom ASCII font rendering as an optional enhancement rather than a hard requirement.

---

### Strategy 3: Native Systemd Service (Headless & Autostart Support)

Currently, the user must keep an interactive terminal session running in the foreground. Providing a systemd unit file allows:
- Background daemon execution.
- Automatic startup on boot.
- Clean process management via `systemctl`.

#### Example Unit File (`/etc/systemd/system/igareck-antidpi.service`):
```ini
[Unit]
Description=Igareck AntiDPI Service (Zapret2)
After=network-online.target nftables.service
Wants=network-online.target
Documentation=https://github.com/igareck/anti-dpi-for-russia

[Service]
Type=simple
ExecStart=/usr/bin/python3 /opt/igareck-antidpi/app/igareck.py start --strategy 1 --quiet
ExecStop=/usr/bin/python3 /opt/igareck-antidpi/app/igareck.py recover
Restart=on-failure
RestartSec=5s
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW

[Install]
WantedBy=multi-user.target
```

---

### Strategy 4: Native Package Formats (`.deb`, `.rpm`, `PKGBUILD`)

Instead of a Python script invoking `apt-get` or `dnf`:

1. **Debian / Ubuntu (`.deb`):**
   - Built using standard `dpkg-deb`.
   - Declares runtime dependencies: `Depends: python3, nftables, iproute2, kmod`.
2. **Fedora / RHEL / openSUSE (`.rpm`):**
   - Built using standard `rpmbuild` or `fpm`.
   - Declares runtime dependencies: `Requires: python3, nftables, iproute, kmod`.
3. **Arch Linux (`PKGBUILD` for AUR):**
   - Declares runtime dependencies: `depends=('python' 'nftables' 'iproute2' 'kmod')`.
   - Installable with standard AUR helpers: `yay -S igareck-antidpi`.

---

### Strategy 5: Containerized Deployment (Docker / Podman)

For immutable distributions (Fedora Silverblue, SteamOS, MicroOS, NixOS) or users who want zero modifications to their host system:

- Package `nfqws2`, Python, Lua scripts, and strategy data into an Alpine-based OCI image.
- Run with host networking and network admin capabilities:
  ```bash
  podman run -d \
    --name igareck-antidpi \
    --net=host \
    --cap-add=NET_ADMIN \
    --restart=always \
    ghcr.io/igareck/anti-dpi:latest --strategy 1
  ```
- Works identically on any Linux system with Docker or Podman installed.

---

## 3. Recommended Phased Implementation Plan

| Phase | Milestone | Expected Impact |
| :---: | :--- | :--- |
| **Phase 1** | **Switch to prebuilt static `nfqws2`** | Eliminates 10 compiler & development library dependencies. Instantly broadens compatibility across Arch, Alpine, SUSE, Void. |
| **Phase 2** | **Terminal emulator abstraction** | Removes GNOME Terminal lock-in, enabling seamless launch on KDE Plasma, XFCE, tiling WMs, and SSH sessions. |
| **Phase 3** | **Systemd unit integration** | Allows users to run the tool as a background service and start on boot without an open terminal. |
| **Phase 4** | **Packaging (.deb, .rpm, PKGBUILD)** | Provides clean integration with native system package managers and straightforward updates. |
