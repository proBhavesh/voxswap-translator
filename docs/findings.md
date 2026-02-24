# Hardware Findings — 2026-02-22

## Kit Received

### Boards
- **UCM-iMX8M-Plus Evaluation Kit** (CompuLab) — primary target for VoxSwap
  - SoM: UCM-iMX8M-Plus (Cortex-A53 quad-core @ 1800 MHz, 4 GiB RAM, 29.1 GiB eMMC, 2.8 TOPS NPU)
  - Carrier board: SBEV-UCMIMX8PLUS Rev 1.0 (serial B251126)
  - Preloaded: Yocto Linux 4.0 (Scarthgap), kernel 6.6.23, U-Boot 2023.04
- **UCM-iMX8M-Mini Evaluation Kit** (CompuLab) — secondary/spare board
  - Carrier board: SB-UCM-iMX8 Rev2.1 (serial 188C03252)
  - No NPU, not suitable for VoxSwap

### Wireless Modules (M.2 Key E)
- **Intel AX210NGW** — WiFi 6E + BT 5.2, PCIe-based. Pre-installed in M.2 slot P23. Works out of the box with Yocto image. Does NOT support Auracast.
- **Ezurio Sona IF573** (P/N 453-00120, Infineon CYW55573) — WiFi 6E + BT 6.0 with Auracast support. Shipped separately, not installed. This is the module needed for VoxSwap's Auracast broadcast.

### Accessories
- Auracast-branded earbuds (for testing broadcast reception)
- 2x dipole WiFi/BT antennas + 2x SMA-to-UFL pigtail cables
- 5" TFT LCD panel (Startec KD050HDFIA020-C020A) with touch digitizer
- EB-EVCAMRPI adapter board (camera, Rev 1.0)
- EB-EVCAMLR adapter board (camera, Rev 1.0)
- DB9 serial cable, micro-USB OTG cable, 12V power supply, rubber standoffs

## Board Boot — Successful

Connected via USB serial (P3) at 115200/8N1 using minicom. Board boots to root login automatically.

### Boot log confirmed:
- CPU: i.MX8MP rev1.1 @ 1800 MHz, 4 cores
- DRAM: Samsung 4 GiB @ 3200 MHz
- eMMC: 29.1 GiB (HS400 Enhanced strobe)
- Kernel: 6.6.23-compulab-4.0
- Distro: NXP i.MX Release Distro 6.6-scarthgap
- Hostname: ucm-imx8m-plus-sbev
- Services running: NetworkManager, WPA supplicant, Docker/containerd, Weston (Wayland), Avahi mDNS, sshd, dnsmasq

### Network interfaces detected:
- `lo` — loopback
- `eth0` — Ethernet (FEC, random MAC)
- `eth1` — Ethernet (DWMAC, MAC 00:01:c0:3a:47:de)
- `can0` — CAN bus
- `wlan0` — Intel AX210 WiFi (with Intel firmware loaded)

### Not detected:
- No Bluetooth adapter (`hciconfig -a` returned empty)
- Intel AX210 BT may need firmware or jumper config

## Sona IF573 Swap — Failed

### What was done:
1. Powered off board
2. Removed Intel AX210 from M.2 slot P23
3. Installed Sona IF573 in same M.2 slot P23
4. Connected SMA-to-UFL pigtail + dipole antenna to Sona's U.FL connector
5. Booted board

### Result:
- `dmesg | grep -i cyw` — empty
- `dmesg | grep -i sona` — empty
- `dmesg | grep -i infineon` — empty
- `dmesg | grep -i brcm` — empty
- `dmesg | grep -i broadcom` — empty
- `hciconfig -a` — empty
- `ip link show` — no wlan0 interface
- `lspci` — only shows PCIe bridge, no device behind it
- **Critical log line:** `imx6q-pcie 33800000.pcie: Phy link never came up`

The PCIe bus does not see the Sona module at all.

### Analysis — Corrected with Ezurio Documentation

**Important correction:** Initial analysis assumed the Sona IF573 used SDIO for WiFi. This was wrong.

#### Sona IF573 Variants (from Ezurio app note):

| Form Factor | Wi-Fi | BT | Part Number |
|-------------|-------|-----|-------------|
| M.2 2230 | SDIO | UART | 453-00119 |
| M.2 2230 | **PCIe** | **UART** | **453-00120** (ours) |
| M.2 1318 | SDIO/PCIe | UART | 453-00117 / 453-00118 |

**Our module (453-00120) uses PCIe for WiFi and UART for Bluetooth.** Not SDIO, not USB.

#### M.2 Slot P23 interfaces (from carrier board schematics):

| Interface | Signals | Intel AX210 | Sona IF573 (453-00120) |
|-----------|---------|-------------|------------------------|
| **PCIe** | PCIE_TXN/RXN + ref clock | WiFi | WiFi |
| **SDIO** | SDIO_CLK, CMD, D0-D3 | Not used | Not used (wrong variant) |
| **USB** | MUX_M2_USB1_DP/DN (via E4 mux) | Bluetooth | Not used |
| **UART** | UART_TX, UART_CTS, UART_RTS | Not used | Bluetooth |

#### Why "Phy link never came up":
The Sona 453-00120 DOES use PCIe — so the link failure is a real problem, not a bus mismatch. Likely causes:

1. **Missing firmware** — Confirmed. No `/lib/firmware/brcm/` or `/lib/firmware/cypress/` on the board. The brcmfmac driver cannot initialize the chip without firmware, and the PCIe link may not complete without proper chip initialization.

2. **Missing brcmfmac backports driver** — The stock Yocto image uses the mainline brcmfmac driver, but the CYW55573 requires Ezurio's patched backports driver (`kernel-module-lwb-if-backports`). The mainline driver may not recognize the CYW55573 PCIe device ID.

3. **Device tree / power sequencing** — The Ezurio guide specifies that the M.2 power sequencing (pwrseq) needs a 200ms power-on delay. The W_DISABLE1 signal (WiFi reset) and W_DISABLE2 signal (BT reset) must be controlled by the host platform. Without proper power sequencing, the chip may not initialize on the PCIe bus.

4. **Bluetooth via UART, not USB** — BT firmware is loaded over UART (`/dev/ttymxc0`) using `brcm_patchram_plus` or serdev. The E4 jumper (USB routing) is NOT relevant for this module's Bluetooth.

#### Jumper E4 — NOT needed for Sona IF573:
```
E4 operation:
  Open:    USB routed to M.2 socket P23
  Closed:  USB routed to USB connector J3 (DEFAULT)
```
E4 controls USB routing. Since our Sona variant uses UART for BT (not USB), E4 does not need to be changed. However, E4 explains why **Intel AX210 Bluetooth never worked** — AX210 uses USB for BT, and USB is routed to J3 instead of M.2.

#### Board diagnostics confirmed:
- `ls /lib/firmware/brcm/` — directory doesn't exist
- `ls /lib/firmware/cypress/` — directory doesn't exist
- `ls /sys/bus/sdio/devices/` — empty (expected, our module uses PCIe not SDIO)
- USDHC1 (30b40000, M.2 SDIO) — not enabled in device tree (irrelevant for PCIe variant)
- USDHC2 (30b50000) — active, micro SD card
- USDHC3 (30b60000) — active, eMMC
- `dmesg | grep -i brcm` — empty (no driver loaded, no firmware)

### What's needed to get Sona IF573 working (from Ezurio guide):

#### 1. Yocto image rebuild with Ezurio's meta-layer:
- Add `meta-summit-radio` Yocto layer to bblayers.conf
- Source: Ezurio GitHub (branch matches firmware release version)

#### 2. Firmware release package from Ezurio:
- `regIF573-aarch64-11.171.0.24.tar.bz2` (or latest)
- **Requires download request** — must sign in at ezurio.com
- Place in `~/imx-yocto-bsp/release/`

#### 3. Kernel config changes:
- WiFi: Set `cfg80211` to module (`m`), disable firmware sysfs fallback
- BT: Disable built-in Bluetooth (`< > Bluetooth`), set `i.MX SDMA support` to module (`M`)

#### 4. Packages to add to IMAGE_INSTALL:
```
kernel-module-lwb-if-backports
summit-supplicant-lwb-if
summit-networkmanager-lwb-if
summit-networkmanager-lwb-if-nmcli
if573-pcie-firmware
brcm-patchram-plus
packagegroup-tools-bluetooth
kernel-modules-imx-sdma
firmware-imx-sdma-imx7d
```

#### 5. Device tree changes:
- Power sequencing: `post-power-on-delay-ms = <200>` on usdhc1 pwrseq
- BT UART: Configure `&uart1` with serdev node for `infineon,cyw55572-bt`
- W_DISABLE1 (WiFi reset) and W_DISABLE2 (BT reset) GPIO control
- **Note:** Ezurio's device tree examples are for NXP iMX8MP EVK — must be adapted for CompuLab SBEV-UCMIMX8PLUS carrier board (different GPIO routing)

#### 6. Regulatory domain:
- Set `LWB_REGDOMAIN = "US"` (or appropriate country code) in local.conf
- Creates `/etc/modprobe.d/brcmfmac.conf` with `options brcmfmac regdomain=US`

### Manual integration attempts — 2026-02-23

We attempted to get the Sona IF573 working without a full Yocto rebuild by manually installing firmware and modifying the device tree. All attempts failed — the PCIe link never came up.

#### What we downloaded (from Ezurio GitHub — publicly available):

Source: https://github.com/Ezurio/SonaIF-Release-Packages/releases/tag/LRD-REL-12.103.0.5

| File | Size | Contents |
|------|------|----------|
| `summit-if573-pcie-firmware-12.103.0.5.tar.bz2` | 841 KB | WiFi firmware, BT firmware, NVRAM config, regulatory data |
| `summit-backports-12.103.0.5.tar.bz2` | 15 MB | Kernel backports driver source (brcmfmac for CYW55573) |
| `EZ-RN-IF573-ezurio-12_103_0_5.pdf` | 146 KB | Release notes |
| `EZ-RRN-SonaIF573.pdf` | 346 KB | Regulatory release notes |

Files saved in: `docs/datasheets/ezurio-release/`

#### Attempt 1 — Install firmware + load stock brcmfmac driver

Copied firmware files to the board via USB drive:
```
/lib/firmware/cypress/cyfmac55572-pcie-prod_v18.53.366.19.trxse  (728,194 bytes — WiFi firmware)
/lib/firmware/cypress/CYW55560A1_v001.002.087.0225.0065.hcd      (112,806 bytes — BT firmware)
/lib/firmware/cypress/cyfmac55572-if573.txt                       (13,872 bytes — NVRAM config)
/lib/firmware/cypress/cyfmac55572-if573_v20241008.clm_blob        (6,950 bytes — regulatory)
/lib/firmware/brcm/CYW55560A1.hcd                                 (symlink → BT firmware)
```

Loaded the stock kernel brcmfmac driver: `modprobe brcmfmac`

**Result:** Driver loaded but only registered as a USB interface driver. No PCIe device detected, no firmware loading attempted, no wlan0 appeared. The stock brcmfmac does not recognize the CYW55573 PCIe device ID.

#### Attempt 2 — Device tree: reduce PCIe link speed to Gen 1

Changed `fsl,max-link-speed` from `<0x03>` (Gen 3) to `<0x01>` (Gen 1) in the PCIe host node (`pcie@33800000`). Hypothesis: CYW55573 might not train at Gen 3 speed.

**Result:** Still `Phy link never came up`. Link speed is not the issue.

#### Attempt 3 — Device tree: enable PCIe reference clock output

Changed PCIe PHY node (`pcie-phy@32f00000`):
- `fsl,refclk-pad-mode` from `<0x02>` (unused) to `<0x01>` (output)
- Removed `fsl,clkreq-unsupported` flag

Hypothesis: The Intel AX210 has its own oscillator and doesn't need the host reference clock. The CYW55573 might need the host to provide the 100MHz PCIe reference clock via the M.2 REF_CLK pins.

**Result:** Still `Phy link never came up`. Reference clock mode is not the issue (or the clock was already being provided despite the "unused" setting).

#### Device tree analysis (from decompiled `sbev-ucmimx8plus.dtb`):

| Property | Value | Notes |
|----------|-------|-------|
| PCIe reset GPIO | PCA9555 pin 0 (gpio-512) | Output HIGH = deasserted. Verified working. |
| PCIe PHY | `fsl,imx8mp-pcie-phy` at 0x32f00000 | Status okay |
| Max link speed | Gen 3 | Changed to Gen 1, no effect |
| Ref clock pad mode | 0x02 (unused) | Changed to 0x01 (output), no effect |
| CLKREQ | unsupported | Removed, no effect |
| BT UART | serial@30860000 (uart1/ttymxc0) | Has CTS/RTS flow control configured |
| W_DISABLE1/W_DISABLE2 | Unknown routing on CompuLab board | Not investigated — may be the issue |

#### Conclusion

The PCIe PHY link failure is happening at the hardware/electrical level — before any software driver or firmware gets involved. The CYW55573's PCIe endpoint is simply not responding to link training.

The Intel AX210 works in the same slot, proving the PCIe hardware (lanes, power, reset GPIO) is functional. Something specific to the CYW55573 prevents it from completing PCIe initialization on this board.

**Possible remaining causes (not yet tested):**
1. **W_DISABLE1# signal** — If held LOW on the CompuLab board, the WiFi radio is disabled at the M.2 level. AX210 might ignore this pin, but CYW55573 might fully shut down its PCIe endpoint. Need to trace W_DISABLE1 routing on the CompuLab schematics.
2. **PCIe reset timing** — The CYW55573 may need a longer reset hold/release time than the iMX8MP PCIe driver provides. Would require kernel driver patching.
3. **Full Yocto rebuild with Ezurio's meta-layer** — Ezurio's backports package may include PCIe subsystem patches or custom initialization sequences that handle CYW55573-specific quirks.

**Decision: Revert to Intel AX210 and proceed with development.** The Sona IF573 integration requires a full Yocto rebuild with Ezurio's `meta-summit-radio` layer — manual firmware/device-tree changes are not sufficient.

All changes were reverted: original DTB restored, firmware files removed. Board is back to stock.

### Recommended next steps:
1. **Reinstall Intel AX210** — Proceed with WiFi hotspot + stream receiver development
2. **Send findings to client** — Ask for help with Sona integration (Ezurio FAE contact, or pre-built image)
3. **If client can't help** — Set up Yocto build environment, request firmware from Ezurio, adapt device tree from EVK to CompuLab board (significant effort)

## Reference Documents

Located in `docs/datasheets/`:

| File | Contents |
|------|----------|
| `som-reference-guide_ucm-imx8plus-reference-guide.pdf` | SoM reference guide (45 pages) |
| `carrier-board-schematics_sbev-ucmimx8plus_schematics_1v0.pdf` | Carrier board schematics (13 sheets) |
| `carrier-board-assembly-top_sbev-ucmimx8plus_assembly-top_1v0.pdf` | Component placement — top side |
| `carrier-board-assembly-bottom_sbev-ucmimx8plus_assembly-bottom_1v0.pdf` | Component placement — bottom side |
| `camera-adapter-rpi_eb-evcamrpi-datasheet.pdf` | Camera adapter board (RPi) datasheet |
| `camera-adapter-blr_eb-evcamblr-datasheet.pdf` | Camera adapter board (BLR) datasheet |
| `sona-if573-yocto-integration_EZ-AN-SONA-IF573-YOCTO-INTEGRATION_v2_0.pdf` | Ezurio IF573 Yocto integration guide (10 pages) |

Key documents for Sona IF573 integration:
- **Ezurio Yocto guide** — Step-by-step: meta-layer, firmware, kernel config, device tree, BT UART setup
- **Schematic Sheet 5** — USB #1 M.2 mux, E4 jumper
- **Schematic Sheet 11** — M.2 for WiFi module (P23 pinout: PCIe, SDIO, UART, USB)

## Action Items

### Sona IF573 Integration (blocked — needs full Yocto rebuild)
- [x] ~~Check firmware on board~~ — confirmed missing
- [x] ~~Check SDIO controller~~ — irrelevant, our module (453-00120) uses PCIe not SDIO
- [x] ~~Identify root cause~~ — PCIe PHY link failure, not a software/firmware issue
- [x] ~~Download Ezurio firmware + driver packages~~ — saved in `docs/datasheets/ezurio-release/`
- [x] ~~Copy firmware to board~~ — firmware installed, but PCIe link still fails
- [x] ~~Try stock brcmfmac driver~~ — doesn't recognize CYW55573
- [x] ~~Try DT: reduce PCIe link speed~~ — no effect
- [x] ~~Try DT: enable PCIe ref clock output~~ — no effect
- [x] ~~Revert all changes~~ — board restored to stock
- [ ] Send findings to client, ask for Ezurio FAE contact or pre-built image
- [ ] Set up Yocto build environment with `meta-summit-radio` layer
- [ ] Adapt device tree from NXP iMX8MP EVK to CompuLab SBEV-UCMIMX8PLUS
- [ ] Investigate W_DISABLE1 signal routing on CompuLab board
- [ ] Rebuild Yocto image with Ezurio packages + firmware + device tree
- [ ] Flash and test Sona IF573 WiFi (PCIe) and Bluetooth (UART)

### Development (can proceed now with Intel AX210)
- [ ] Reinstall Intel AX210 for WiFi hotspot development
- [ ] Test WiFi hotspot: `nmcli device wifi hotspot ssid VoxSwap-0001 password voxswap123 con-name HotspotCon`
- [ ] Check BlueZ version and PipeWire availability on the board
- [ ] Begin Phase 2: stream receiver + audio mixer (doesn't need Auracast)
