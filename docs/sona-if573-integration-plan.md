# Sona IF573 Integration Plan

## Context

The CompuLab SBEV-UCMIMX8PLUS carrier board ships with an Intel AX210 WiFi/BT module in M.2 slot P23. The Intel module works out of the box but does NOT support Auracast (BLE Audio broadcast), which VoxSwap needs.

The Ezurio Sona IF573 (P/N 453-00120, Infineon CYW55573) supports Auracast but doesn't work when plugged in because the board's software was built for Intel, not Sona.

### What's wrong (confirmed by diagnostics)

1. **No firmware** — `/lib/firmware/brcm/` and `/lib/firmware/cypress/` don't exist on the board
2. **No driver** — The stock kernel's brcmfmac driver doesn't recognize the CYW55573. Ezurio provides a patched backports driver.
3. **No device tree config** — Linux doesn't know how to power up the Sona module (power sequencing, reset GPIOs)
4. **PCIe link fails** — `dmesg` shows `"Phy link never came up"` because without firmware/driver, the chip can't complete PCIe initialization

### Our module variant

| Property | Value |
|----------|-------|
| Part Number | 453-00120 |
| WiFi interface | PCIe |
| Bluetooth interface | UART |
| Form factor | M.2 2230 Key E |

### What we downloaded (from Ezurio's official GitHub)

Source: https://github.com/Ezurio/SonaIF-Release-Packages/releases/tag/LRD-REL-12.103.0.5

| File | Size | Contents |
|------|------|----------|
| `summit-if573-pcie-firmware-12.103.0.5.tar.bz2` | 841 KB | WiFi firmware, BT firmware, NVRAM config, regulatory data |
| `summit-backports-12.103.0.5.tar.bz2` | 15 MB | Kernel backports driver source (includes `brcmfmac` for CYW55573) |
| `EZ-RN-IF573-ezurio-12_103_0_5.pdf` | 146 KB | Release notes |
| `EZ-RRN-SonaIF573.pdf` | 346 KB | Regulatory release notes |

Files located in: `docs/datasheets/ezurio-release/`

Reference guide: `docs/datasheets/sona-if573-yocto-integration_EZ-AN-SONA-IF573-YOCTO-INTEGRATION_v2_0.pdf`

### Board info

| Property | Value |
|----------|-------|
| SoM | UCM-iMX8M-Plus |
| Carrier board | SBEV-UCMIMX8PLUS Rev 1.0 |
| Kernel | 6.6.23-compulab-4.0 |
| Distro | NXP i.MX Release Distro 6.6-scarthgap (Yocto) |
| Architecture | aarch64 (ARM Cortex-A53) |
| Serial console | USB serial on P3 at 115200/8N1 via minicom |

## Plan

### Step 1 — Copy firmware to the board

Extract the firmware archive and copy files to the board via serial console (using base64 encoding over minicom, or via USB drive / SD card / SSH over Ethernet).

Files to copy:
```
/lib/firmware/cypress/cyfmac55572-pcie.trxse        → WiFi firmware
/lib/firmware/cypress/cyfmac55572-pcie.clm_blob      → regulatory/country data
/lib/firmware/cypress/cyfmac55572-pcie.txt            → NVRAM config
/lib/firmware/cypress/cyfmac55572-if573.txt           → IF573-specific NVRAM
/lib/firmware/cypress/cyfmac55572-if573_v20241008.clm_blob → IF573-specific regulatory
/lib/firmware/brcm/CYW55560A1.hcd                    → Bluetooth firmware
/lib/firmware/cypress/CYW55560A1_v001.002.087.0225.0065.hcd → BT firmware (versioned)
```

**Reversible:** Yes — just delete the files. Nothing existing gets overwritten.

### Step 2 — Cross-compile backports driver

On our dev PC (Linux x86_64), cross-compile the backports brcmfmac kernel module for:
- Target kernel: 6.6.23-compulab-4.0
- Target arch: aarch64
- Defconfig: `brcmfmac` or `som8mplus`

Requirements:
- aarch64 cross-compiler (`aarch64-linux-gnu-gcc`)
- Kernel headers for 6.6.23-compulab-4.0 (may need to get from CompuLab's BSP or build from source)

Output: `brcmfmac.ko` (and dependencies like `cfg80211.ko`, `compat.ko`)

Copy the `.ko` files to the board.

**Reversible:** Yes — kernel modules loaded via `insmod` only persist until reboot.

### Step 3 — Load driver and test WiFi

On the board:
```bash
# Create firmware directories
mkdir -p /lib/firmware/brcm /lib/firmware/cypress

# Copy firmware files (method depends on how we transfer - USB/SD/SSH/base64)
# ... copy files ...

# Load the backports modules
insmod /path/to/compat.ko
insmod /path/to/cfg80211.ko
insmod /path/to/brcmfmac.ko

# Check if WiFi comes up
dmesg | grep -i brcm
dmesg | grep -i cyw
ip link show
iw dev
```

**Success criteria:** `wlan0` interface appears, `dmesg` shows firmware loaded successfully, no "Phy link never came up".

**Reversible:** Yes — reboot clears loaded modules.

### Step 4 — Device tree modification (if needed)

If Step 3 doesn't work because of power sequencing issues, we need to modify the device tree:

1. Backup current device tree:
   ```bash
   cp /boot/imx8mp-*.dtb /boot/imx8mp-*.dtb.backup
   ```

2. Decompile the current dtb:
   ```bash
   dtc -I dtb -O dts -o current.dts /boot/imx8mp-*.dtb
   ```

3. Add power sequencing from Ezurio guide (200ms power-on delay on usdhc1 pwrseq)

4. Recompile and flash:
   ```bash
   dtc -I dts -O dtb -o modified.dtb current.dts
   cp modified.dtb /boot/imx8mp-*.dtb
   ```

5. Reboot and test

**Reversible:** Yes — restore from `.dtb.backup` if anything goes wrong. Board can also boot from SD card as recovery.

### Step 5 — Test Bluetooth (if WiFi works)

Bluetooth uses UART (`/dev/ttymxc0`). Load BT firmware:
```bash
brcm_patchram_plus --no2bytes --autobaud_mode --baudrate 921600 \
    --use_baudrate_for_download --enable_hci \
    --patchram /lib/firmware/cypress/CYW55560A1_v001.002.087.0225.0065.hcd \
    /dev/ttymxc0 &
```

Then test with BlueZ:
```bash
hciconfig -a
bluetoothctl
```

**Note:** BT may also need device tree changes for UART and W_DISABLE2 GPIO control. The exact GPIO mapping on CompuLab's board needs to be figured out (differs from NXP EVK).

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Firmware copy doesn't help PCIe link | WiFi still won't work | Move to Step 4 (device tree) |
| Backports driver doesn't compile for kernel 6.6.23 | Can't load driver | Try older Ezurio release, or get CompuLab kernel source |
| Device tree change breaks boot | Board won't start | Restore backup dtb, or boot from SD card |
| UART mapping differs from NXP EVK | BT won't work | Check CompuLab schematics for UART routing to M.2 |
| Entire approach fails | Sona unusable | Put Intel AX210 back, proceed with development, do full Yocto rebuild later |

## What we're NOT doing

- NOT rebuilding the entire Yocto image (saves days)
- NOT modifying the existing kernel (we add a separate module)
- NOT deleting any existing files (only adding new ones)
- NOT changing any boot configuration permanently (backup everything first)

## Transfer method — TBD

Need to figure out how to get files onto the board. Options:
1. **SSH over Ethernet** — If we connect Ethernet, we can scp files. Board has `eth0` and `eth1`.
2. **USB drive** — Copy files to USB stick, plug into board, mount and copy.
3. **SD card** — Same as USB but via micro SD slot.
4. **Base64 over serial** — Encode files as base64, paste into minicom, decode on board. Slow but works without any extra hardware.

Ethernet + SSH is the fastest and easiest option.
