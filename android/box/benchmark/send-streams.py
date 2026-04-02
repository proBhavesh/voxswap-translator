#!/usr/bin/env python3
"""
Simulates 3 phones sending 9 UDP audio streams to the box.
Uses a real PCM audio file instead of generated tones.
PCM: 16-bit 16kHz mono, 20ms packets (320 samples = 640 bytes).
Rate: 50 packets/sec per stream, 450 packets/sec total.
"""

import socket
import struct
import time
import threading
import sys

BOX_IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.10"
AUDIO_FILE = sys.argv[2] if len(sys.argv) > 2 else "/tmp/audio-60s-16k-mono.raw"
BOX_PORT = 7701
PCM_CHUNK = 640  # 320 samples x 2 bytes = 20ms at 16kHz
PACKET_INTERVAL = 0.02  # 20ms
NUM_SPEAKERS = 3
NUM_STREAMS = 3


def send_stream(speaker_id, stream_type, pcm_data):
    """Send one audio stream at 50 packets/sec using real PCM data."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    seq = 0
    offset = 0
    start = time.monotonic()

    while offset + PCM_CHUNK <= len(pcm_data):
        timestamp = int((time.monotonic() - start) * 1000) & 0xFFFFFFFF
        header = struct.pack("<BBII", speaker_id, stream_type, seq, timestamp)
        chunk = pcm_data[offset:offset + PCM_CHUNK]
        sock.sendto(header + chunk, (BOX_IP, BOX_PORT))

        seq += 1
        offset += PCM_CHUNK

        expected = start + seq * PACKET_INTERVAL
        now = time.monotonic()
        if expected > now:
            time.sleep(expected - now)

    sock.close()
    return seq


def main():
    with open(AUDIO_FILE, "rb") as f:
        pcm_data = f.read()

    duration = len(pcm_data) / (16000 * 2)
    total_packets_per_stream = len(pcm_data) // PCM_CHUNK

    print(f"audio file: {AUDIO_FILE} ({duration:.1f}s, {len(pcm_data)} bytes)")
    print(f"sending to {BOX_IP}:{BOX_PORT}")
    print(f"  {NUM_SPEAKERS} speakers x {NUM_STREAMS} streams = {NUM_SPEAKERS * NUM_STREAMS} streams")
    print(f"  {total_packets_per_stream} packets per stream, {total_packets_per_stream * NUM_SPEAKERS * NUM_STREAMS} total")
    print(f"  target rate: {NUM_SPEAKERS * NUM_STREAMS * 50} packets/sec")
    print()

    threads = []
    for speaker_id in range(NUM_SPEAKERS):
        for stream_type in range(NUM_STREAMS):
            t = threading.Thread(
                target=send_stream,
                args=(speaker_id, stream_type, pcm_data),
                daemon=True,
            )
            threads.append(t)

    start = time.monotonic()
    for t in threads:
        t.start()

    print("all streams running...")
    for t in threads:
        t.join()

    elapsed = time.monotonic() - start
    print(f"done. sent for {elapsed:.1f}s")


if __name__ == "__main__":
    main()
