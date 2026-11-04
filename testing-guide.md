# Step-by-Step Testing Guide: Igareck AntiDPI on Fedora

This guide walks through testing **Igareck AntiDPI (Zapret2)** on a Fedora Linux system, from initial installation through strategy scanning, manual verification, and clean uninstallation.

---

## 1. Prerequisites & Preparation

1. **Disable Active VPNs & Proxies:**
   Turn off any active VPNs, WireGuard/OpenVPN tunnels, Tor, or TUN proxies (e.g., Clash, Sing-box, Hiddify) before testing. Active VPN tunnels redirect packets away from physical adapters and will bypass or conflict with local AntiDPI rules.
2. **Ensure Network Connectivity:**
   Confirm that your physical network connection (Ethernet or Wi-Fi) is active and connected to your ISP.
3. **Sudo Privileges:**
   You will need `sudo` access to manage `nftables` packet queues and build `nfqws2`.

---

## 2. Step 1: Install the Tool

Open a terminal and run the installation script:

```bash
cd /home/dz/tmp/anti-dpi-for-russia/dist/Igareck_AntiDPI_CLI_v1.0_Linux_x64
sudo bash INSTALL.sh
```

*(Alternatively, you can run directly from the repository source: `cd /home/dz/tmp/anti-dpi-for-russia/linux && sudo bash INSTALL.sh`)*

### Interactive Prompts During Installation:
1. **Notice & Notes:** Press **Enter** to acknowledge the advisory screens.
2. **Installation Directory:** Press **Enter** to accept the default `/opt/igareck-antidpi` (or type a custom path).
3. **Zapret2 Engine:** Press **Enter** to select option `1` (*Compatible release with pinned commit*).
4. **Desktop Shortcuts:** Type `Да` or `Yes` to generate Desktop and Application Menu icons (or `Нет`/`No` to skip).
5. **Global Command:** Type `Да` or `Yes` to register `/usr/local/bin/igareck-antidpi`.

### What Happens Under the Hood:
- Automatically detects Fedora and resolves dependencies using `dnf` (`gcc`, `make`, `iproute`, `nftables`, `luajit-devel`, `zlib-devel`, etc.).
- Downloads official Zapret2 upstream sources.
- Compiles `nfqws2` optimized for your CPU and links against system libraries.
- Dry-run verifies parameters across all 484 strategies.

---

## 3. Step 2: First Launch & Configuration

Launch the program using your preferred method:

- **Global CLI command:**
  ```bash
  igareck-antidpi
  ```
- **Direct script execution:**
  ```bash
  sudo bash /opt/igareck-antidpi/START.sh
  ```
- **Desktop Launcher:** Double-click **`START_Igareck_AntiDPI`** on your Desktop or open it from the GNOME Application Menu.

### Initial Configuration Prompts:
1. **Network Adapter Selection:**
   The tool displays all active IPv4 interfaces. Press **Enter** to accept the recommended physical adapter (e.g. `enp1s0`).
2. **TCP Port Scope Selection:**
   - Press **Enter** for `all` (recommended, intercepts all TCP traffic; required for VPN subscriptions).
   - Alternatively, type `443` to limit interception strictly to HTTPS web traffic.

---

## 4. Step 3: Testing Strategies

Once the main menu appears, choose one of the testing workflows:

### Method A: Automated YouTube Scanner (Fastest & Recommended)
Use the automated multi-worker probe to discover which strategies work best on your ISP:

1. In the menu, enter option **`2`** (*«Автоматическая проверка стратегий на YouTube»*).
2. **Select Strategy Set:**
   - Press **`1`** to scan the **Golden Tier (Strategies 1–45)**. This takes ~1–2 minutes.
3. **Set Parallel Workers:**
   - Enter **`3`** (runs 3 parallel probes concurrently).
4. **Read Real-Time Results:**
   The scanner isolates traffic per worker via dedicated port blocks and outputs passing strategies in green:
   ```text
   7 (PASS), 14 (PASS), 22 (PASS)
   ```
5. Press **`Esc`** once you see working strategies to stop the scan early.

---

### Method B: Interactive Strategy Execution
Manually activate a strategy to test real-world browsing:

1. From the main menu, type any strategy number (e.g. `1` or a number that passed in Method A) and press **Enter**.
2. The strategy activates in the background, redirecting traffic through `nfqws2`.
3. **Verify in your browser:**
   - Open [YouTube](https://www.youtube.com/) in Firefox, Chrome, or LibreWolf.
   - Play a high-definition video (1080p/4K) to confirm continuous throughput.
   - Verify previously blocked or throttled services.
4. **Keyboard Controls While Active:**
   - **`s`**: Switch to a different strategy number without restarting.
   - **`r`**: Restart the current strategy.
   - **`i`**: Reconfigure the network adapter.
   - **`q`** or **`Ctrl+C`**: Cleanly terminate `nfqws2` and flush `nftables` rules.

---

### Method C: Testing with VPN & Tor Blacklists
To unblock VPN protocols or Tor bridges targeting Russian network restrictions:

1. Launch a strategy using Method B.
2. Open your client software (e.g., V2Ray, Xray, Sing-box, Hiddify, Tor Browser).
3. Ping nodes or connect to your subscription endpoints.
4. If some configurations fail to establish a handshake, switch to another strategy using the **`s`** key in the terminal and test again.

---

## 5. Step 4: Verifying Clean Firewall State

When the program exits (via `q` or `Ctrl+C`), it automatically removes its temporary `nftables` table. You can verify that no orphaned firewall rules remain:

```bash
sudo nft list table ip igareck_z2_* 2>&1
```

*Expected output:* `No such file or directory` or `does not exist`.

---

## 6. Step 5: Clean Uninstallation

When testing is complete, you can remove the installation:

### Standard Uninstall (Preserves logs):
```bash
sudo bash /opt/igareck-antidpi/DELETE.sh
```

### Full Purge (Removes `/opt/igareck-antidpi`, logs, and desktop icons):
```bash
sudo bash /opt/igareck-antidpi/DELETE.sh --purge
```
