#ifndef VOXSWAP_AUDIO_MIXER_H
#define VOXSWAP_AUDIO_MIXER_H

#include <stddef.h>
#include <stdint.h>

#define MIXER_MAX_SPEAKERS  3
#define MIXER_NUM_CHANNELS  3   /* 0=original, 1=target_lang1, 2=target_lang2 */

/**
 * Initialize the audio mixer. Creates PipeWire playback streams
 * (one per channel) and starts the PipeWire thread loop.
 *
 * @return 0 on success, -1 on failure. Failure is non-fatal —
 *         mixer_write_audio() becomes a no-op without PipeWire.
 */
int audio_mixer_init(void);

/** Shut down PipeWire streams and free resources. Safe to call if init failed. */
void audio_mixer_shutdown(void);

/**
 * Write received audio to the mixer for playback.
 * Called by the stream receiver for each UDP audio packet.
 * Thread-safe: writes to a lock-free ring buffer read by PipeWire's RT thread.
 *
 * @param speaker_id  Speaker slot (0-2)
 * @param stream_type 0=original, 1=target_lang1, 2=target_lang2
 * @param pcm         Raw 16-bit LE PCM data
 * @param pcm_len     Length of pcm in bytes (typically 640 = 20ms at 16kHz)
 * @param sequence    Packet sequence number (for loss detection)
 * @param timestamp   Sample offset (reserved for future use)
 */
void mixer_write_audio(uint8_t speaker_id, uint8_t stream_type,
                       const uint8_t *pcm, size_t pcm_len,
                       uint32_t sequence, uint32_t timestamp);

#endif
