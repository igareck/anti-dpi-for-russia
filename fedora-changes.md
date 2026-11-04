# Fedora Support Changes Summary

This document describes the changes made to adapt the **Igareck AntiDPI CLI (Zapret2)** tool for **Fedora Linux** (and RHEL-family distributions) while preserving full compatibility with **Ubuntu/Debian**.

---

## 1. Overview of Problem & Identified Issues

Initially, the Linux implementation targeted Ubuntu/Debian (`apt-get`) only. When running on Fedora, several blockers were identified:

1. **Hardcoded Package Manager & Distro Assumptions:**
   - The installer checked exclusively for `/etc/debian_version` and `apt-get`.
   - Package names on Debian differ significantly from Fedora/RHEL packages (e.g. `build-essential` vs `gcc` + `make`, `zlib1g-dev` vs `zlib-devel`, `iproute2` vs `iproute`, `lib*-dev` vs `lib*-devel`).
   - A naive string replacement approach previously attempted had critical errors (e.g. replacing `fontconfig` with `fontconfig2` which does not exist in Fedora, failing to replace `zlib1g-dev`, and omitting `make`).

2. **Network Interface Discovery Crash (`iproute-6.17+`):**
   - On Fedora 44 (and other distributions running newer versions of `iproute2`), interfaces that are in an `UP` device state but lack assigned IPv4 addresses (e.g. disconnected Ethernet ports) cause `ip -j -4 addr show up` to output empty JSON objects (`{}`).
   - `igareck.py` assumed every item in the JSON array contained `'ifindex'` and `'ifname'`, causing an immediate `KeyError: 'ifindex'` during startup.

3. **Compiler & Dependency Verification:**
   - Ubuntu provides `cc` by default through `build-essential`. On Fedora, while `/usr/bin/cc` is typically present when `gcc` is installed, dependency checks needed to gracefully support both `cc` and `gcc`.

---

## 2. Detailed Technical Changes

### A. Installer & Package Management (`linux/app/install.py`)
- **Distribution Detection (`detect_distro`):**
  Added a robust distribution detection helper reading `/etc/os-release` (`ID`, `ID_LIKE`) with fallback checks for `/etc/fedora-release`, `/etc/redhat-release`, `/etc/debian_version`, and tool presence (`dnf` vs `apt-get`).
- **Clean Package Definitions:**
  Replaced brittle in-place string replacements with explicit package lists per distribution family:
  - **Debian/Ubuntu (`PACKAGES_DEBIAN`):**
    `python3`, `ca-certificates`, `curl`, `iproute2`, `nftables`, `kmod`, `fontconfig`, `build-essential`, `pkg-config`, `libnetfilter-queue-dev`, `libnfnetlink-dev`, `libmnl-dev`, `zlib1g-dev`, `libcap-dev`, `libluajit-5.1-dev`
  - **Fedora/RHEL (`PACKAGES_FEDORA`):**
    `python3`, `ca-certificates`, `curl`, `iproute`, `nftables`, `kmod`, `fontconfig`, `gcc`, `make`, `pkg-config`, `libnetfilter_queue-devel`, `libnfnetlink-devel`, `libmnl-devel`, `zlib-devel`, `libcap-devel`, `luajit-devel`
- **Package Manager Commands:**
  - Debian: `apt-get update` & `apt-get install -y --no-install-recommends`
  - Fedora: `dnf makecache` & `dnf install -y --setopt=install_weak_deps=False`
- **Compiler Validation:**
  Checks for `cc` or `gcc`, ensuring no false negatives if one alias is missing.

### B. Network Interface Discovery (`linux/app/igareck.py`)
- In `interfaces()`, guarded the loop iteration:
  ```python
  for item in addresses:
      if not item or 'ifindex' not in item or 'ifname' not in item:
          continue
  ```
  and ensured `links` lookup safely filters entries with `ifindex`.
- Updated CLI parser description from `Igareck + Zapret2 CLI 1.0 — Linux Ubuntu/Debian` to `Igareck + Zapret2 CLI 1.0 — Linux`.

### C. UI & Presentation (`linux/app/cli_ui.py`)
- Updated header banner from `Linux Ubuntu/Debian <arch>` to `Linux <arch>`.

### D. Documentation & Metadata
- **`linux/README_Установка_Linux_v1.0.txt`:**
  - Updated title and environment sections to reflect Ubuntu/Debian and Fedora/RHEL support.
  - Added installation instructions for GNOME Terminal on Fedora (`sudo dnf install gnome-terminal`).
  - Added the exact Fedora dependency package list for reference.
- **`linux/packaging/io.github.igareck.Zapret2.metainfo.xml`:**
  - Updated application description to include Fedora alongside Ubuntu and Debian.

---

## 3. Build & Packaging

- Ran `packaging/linux/build.sh --force` to generate:
  - `dist/Igareck_AntiDPI_CLI_v1.0_Linux_x64.tar.gz`
  - `dist/SHA256SUMS_Linux_v1.0.txt`
- Synchronized and verified unpacked files in `dist/Igareck_AntiDPI_CLI_v1.0_Linux_x64/`.

---

## 4. Verification & Testing on Fedora 44

The entire installation and runtime lifecycle was validated on a Fedora 44 workstation:

1. **Package Verification:**
   All 16 required Fedora packages resolved cleanly without missing symbols or conflicts.
2. **End-to-End Installation Test:**
   - Ran installer with `--prefix /tmp/test-igareck-antidpi --engine compatible`.
   - Downloaded official Zapret2 upstream sources.
   - Built `nfqws2` with GCC and linked against Fedora's system `luajit-devel`, `zlib-ng-compat-devel`, `libnetfilter_queue-devel`, `libnfnetlink-devel`, and `libmnl-devel`.
   - Successfully performed dry-run validation of all 484 Golden and Silver AntiDPI strategies.
3. **Interface Detection:**
   Verified that `interfaces()` correctly discovers active network adapters (`enp1s0`) while safely bypassing interfaces with no IPv4 address and virtual tun/docker devices.
4. **Uninstallation Test:**
   Ran `DELETE.sh --purge --yes` to confirm clean removal of files, state directories, and logs.
