# Hardware Images

Photos of devices and accessories received from the client.

## auracast-earbuds.jpg

Auracast-branded open-ear earbuds for testing Auracast broadcast reception. These support LE Audio and will be used to verify the box's Auracast broadcast output across all 3 channels (original, language 1, language 2).

## accessories-antenna-cables.jpg

Kit accessories laid out:
- **WiFi/Bluetooth antenna** (308R5R000) — two dipole antennas in a bag
- **SMA-to-UFL pigtail cable** (199D10530) — connects antenna to the wireless module's U.FL connector
- **Micro-USB to USB-A OTG cable** — for attaching USB peripherals (keyboard, mouse, storage) to the board
- **Rubber standoffs/feet** — small bag with mounting hardware

## getting-started-imx8m-mini.jpg

Getting started guide for the **UCM-iMX8M-Mini Evaluation Kit** (CompuLab). Key details:
- Preloaded with Yocto Linux image
- Jumpers E6, E9, E14 should be populated (default)
- Jumpers E4, E7, E8, E15 should be removed (default)
- LCD panel connects to P22 and P45
- USB console via micro-USB connector P13
- Serial: 115200 baud, 8 data bits, 1 stop bit, no parity, no flow control
- 12V DC power via connector J2

## kit-contents-manifest.jpg

Full evaluation kit contents manifest:
- **Boards:** UCM-iMX8M-Mini SoM (UCM-iMX8M-C1800QM-D2-N16-E-WB) + SB-UCMIMX8 carrier board
- **Accessories:** 5" TFT LCD panel (Startec KD050HDFIA020-C020A), 2x serial cables, WiFi/BT antenna, SMA-to-UFL cable, 12V power supply, EU + US AC blades, micro-USB OTG adapter, micro-USB console cable

## getting-started-imx8m-plus.jpg

Getting started guide for the **UCM-iMX8M-Plus Evaluation Kit** (CompuLab). This is the primary target board for VoxSwap. Key details:
- Preloaded with UCM-iMX8M-Plus Yocto Linux image
- Jumper E5 should be populated (default)
- Jumper E3 should not be populated (default)
- Power switch SW1 must be OFF before connecting power
- LCD panel connects via EB-HDRLVDS adapter to P13 and P14
- USB console via connector P3
- Serial: 115200 baud, 8 data bits, 1 stop bit, no parity, no flow control
- 12V DC power via connector J1, then toggle SW1 to ON

## board-imx8m-plus-carrier.jpg

Close-up photo of the **SB-EV UCMIMX8PLUS** carrier board (serial B251126). Shows the board from above with visible Ethernet port, USB ports, HDMI connector, and the iMX8M-Plus SoM mounted in the center under a heatsink.

## board-sb-ucm-imx8-rev2.jpg

Photo of the **SB-UCM-iMX8 Rev2.1** carrier board (serial 188C03252) on antistatic foam. This is the CompuLab reference carrier board. Visible components include STM-5 chip, coin cell battery (CR2032), SIM card slot, USB connectors, and various pin headers.

## board-plus-edge-closeup.jpg

Close-up of the iMX8M-Plus carrier board edge showing **J6 audio out**, **audio input** jack, **SW4 PWRBTN** (power button), **SW3 RESET** button, **SD ALT BOOT** switch, and the SBEV-UCMIMX8PLUS Rev 1.0 label. Also visible: MIPI-DSI connector (P12), test points (TP2-TP4).

## board-plus-bottom-closeup.jpg

Close-up of the iMX8M-Plus carrier board bottom area showing **P7 SIM** card slot, **SP5** speaker pad, USB ports (USB #2), **P22** connector for LCD, and the board held in hand for scale.

## modules-intel-ax210-and-sona-if573-front.jpg

Both M.2 wireless modules side by side (front view). Left: **Intel AX210** (green PCB, currently installed in the board). Right: **Ezurio Sona IF573** (blue PCB, Infineon CYW55573 — the Auracast-capable module needed for VoxSwap). Both are M.2 Key E form factor.

## modules-intel-ax210-and-sona-if573-back.jpg

Both M.2 wireless modules side by side (back view). Left: Intel AX210 back. Right: Sona IF573 back showing the "Laird Connectivity" branding (Ezurio was formerly Laird Connectivity).

## module-sona-if573-label.jpg

Close-up of the **Ezurio Sona IF573** label. M/N: Sona IF573, P/N: 453-00120, FCC ID: SQG-SONAIF573, IC: 3147A-SONAIF573, Date Code: 1125096, Rev 2. This is the WiFi 6E + BT 6.0 module with Auracast support — the key component for VoxSwap's broadcast capability.

## module-intel-ax210-label.jpg

Close-up of the **Intel AX210NGW** label. P/N: G86C0008O410, WFM: E0E258AC2C04. Shows two U.FL antenna connectors labeled **MAIN** (2) and **AUX** (1). This module provides WiFi 6E + BT 5.2 but does not support Auracast.

## accessories-full-spread.jpg

Full spread of all accessories: DB9 serial cable (top), SMA-to-UFL pigtail cable, micro-USB OTG cable, two dipole antennas in bag, second SMA-to-UFL pigtail, USB cable in bag, and rubber standoffs.

## adapter-eb-evcamrpi.jpg

**EB-EVCAMRPI** adapter board (Rev 1.0, serial 188C04750) on antistatic bubble wrap. Has PCIe edge connector (J3) and two flat cable connectors (J1, J2). Used for connecting camera modules to the evaluation board.

## adapter-eb-evcamlr.jpg

**EB-EVCAMLR** adapter board (Rev 1.0, serial 188C04610) on antistatic bubble wrap. Has PCIe edge connector (J1) and two flat cable connectors (J2, J3). Another camera/display adapter board for the evaluation kit.

## lcd-panel-front.jpg

**5" TFT LCD panel** with capacitive touchscreen (Startec KD050HDFIA020-C020A) — front view. Shows the glass display face with two ribbon cables extending from the right side (one for display data, one for touch digitizer).

## lcd-panel-back-ribbons.jpg

Back view of the LCD panel showing the two ribbon cable connectors. The wider ribbon is the display data cable, the narrower one is the capacitive touch digitizer cable. Both connect to the carrier board via the EB-HDRLVDS adapter.
