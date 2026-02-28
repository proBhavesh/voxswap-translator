# Box Software — TODO

## Overview

C daemon running on Linux (dev machine for now, iMX8M Plus later). Receives translated audio from phones via WiFi, mixes streams, outputs through PipeWire. Auracast broadcast deferred until Sona IF573 hardware works.

## Components

### 1. Stream Receiver (TCP + UDP)
Core networking — counterpart to the phone's BoxConnection + AudioStreamer.

**TCP control server (port 7700):**
- [ ] 1.1 Accept TCP connections from phones (up to 3 simultaneous speakers)
- [ ] 1.2 Parse REGISTER message: length-prefixed, contains speaker name + source language
- [ ] 1.3 Assign speaker_id (0-2), respond with REGISTER_ACK (speaker_id + target languages)
- [ ] 1.4 First phone to connect becomes admin and sets target languages
- [ ] 1.5 Heartbeat tracking: expect heartbeat every 2s, 3 missed (6s) = speaker disconnected
- [ ] 1.6 Handle speaker disconnect: clean up speaker slot, notify mixer

**UDP audio server (port 7701):**
- [ ] 1.7 Receive UDP packets: 10-byte header + 640-byte PCM payload
- [ ] 1.8 Parse header: speaker_id (1B), stream_type (1B), sequence (4B BE), timestamp (4B BE)
- [ ] 1.9 Validate speaker_id against registered speakers
- [ ] 1.10 Route packets to correct mixer channel based on stream_type (0=original, 1=lang1, 2=lang2)
- [ ] 1.11 Detect and log packet loss via sequence number gaps

### 2. Audio Mixer (PipeWire)
Mix multiple speakers per channel. 3 output channels: original, lang1, lang2.

- [ ] 2.1 Initialize PipeWire, create event loop thread
- [ ] 2.2 Create 3 output streams (one per channel)
- [ ] 2.3 Per-speaker PCM buffer: ring buffer for incoming UDP packets, ordered by sequence number
- [ ] 2.4 Feed PCM from ring buffers into PipeWire streams (16-bit, 16kHz, mono)
- [ ] 2.5 Handle multiple speakers on same channel: PipeWire mixes natively when multiple sources feed one sink
- [ ] 2.6 Silence insertion for dropped packets (fill gaps with zeros)
- [ ] 2.7 Per-speaker volume normalization before mixing

### 3. WiFi Hotspot
hostapd configuration for "VoxSwap-XXXX" access point.

- [ ] 3.1 hostapd config: SSID "VoxSwap-XXXX" (XXXX = last 4 of MAC), WPA2, channel auto
- [ ] 3.2 DHCP server (dnsmasq or udhcpd): assign 10.0.0.x to connected phones
- [ ] 3.3 Box static IP: 10.0.0.1
- [ ] 3.4 Startup script: bring up interface, start hostapd, start DHCP
- [ ] 3.5 For dev machine testing: skip hotspot, run on existing local network

### 4. Settings Server
Receive configuration from phone app over TCP.

- [ ] 4.1 Admin phone sets target languages (part of REGISTER flow or separate command)
- [ ] 4.2 Store target languages in shared state (accessible by stream receiver for REGISTER_ACK)
- [ ] 4.3 Broadcast language changes to all connected phones (if languages change mid-session)

### 5. Main Daemon
Entry point, ties everything together.

- [ ] 5.1 Parse command line args (port overrides, dev mode flags)
- [ ] 5.2 Initialize all components: stream receiver, mixer, settings
- [ ] 5.3 epoll event loop for network I/O (TCP accepts, TCP reads, UDP reads)
- [ ] 5.4 Signal handling: SIGINT/SIGTERM for clean shutdown
- [ ] 5.5 Clean shutdown: close sockets, destroy PipeWire streams, free resources

### 6. Auracast Broadcast — Deferred
Needs Sona IF573 hardware (PCIe link currently broken, needs Yocto rebuild).

- [ ] 6.1 BlueZ LE Audio broadcast setup
- [ ] 6.2 LC3 encoding of mixed PCM
- [ ] 6.3 3 broadcast channels: original, lang1, lang2
- [ ] 6.4 Broadcast metadata (channel names, language labels)

## Build Order

1. **Main daemon + stream receiver** — get TCP registration + UDP audio receiving working
2. **Audio mixer** — PipeWire output so we can hear received audio
3. **WiFi hotspot** — config files + startup script (skip for dev machine testing)
4. **Settings server** — language configuration from admin phone
5. **End-to-end testing** — phone → box → speakers
6. **Auracast** — when hardware is ready

## Dev Machine Testing

All box code runs on a regular Linux laptop. Phone and laptop on the same WiFi network. Phone's BOX_HOST (10.0.0.1) needs to be overridden or laptop given that IP on the network. Alternatively, use the laptop's actual IP and temporarily hardcode it in the phone app for testing.

## Tech Details

- Language: C (C11)
- Build: CMake
- Network I/O: epoll (Linux)
- Audio: PipeWire (libpipewire)
- Audio format: 16-bit PCM, 16kHz, mono
- Max speakers: 3
- TCP message format: 4-byte length prefix (big-endian) + JSON or binary payload
- UDP packet: 650 bytes (10B header + 640B PCM = 20ms of audio)
