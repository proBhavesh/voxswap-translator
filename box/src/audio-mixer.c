#include "audio-mixer.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef HAS_PIPEWIRE

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <spa/utils/result.h>
#include <spa/utils/ringbuffer.h>

#define RING_BUF_SAMPLES    131072 /* Power of 2, ~8s at 16kHz — TTS sends full sentences as bursts */
#define SAMPLE_RATE         16000
#define CHANNELS            1      /* Mono */
#define MAX_PROCESS_FRAMES  1024   /* Max frames per on_process call (stack-allocated temp) */

/* Per-speaker per-channel ring buffer.
 * spa_ringbuffer is lock-free SPSC — one writer (epoll thread via
 * mixer_write_audio) and one reader (PipeWire RT thread via on_process). */
typedef struct {
    struct spa_ringbuffer ring;
    int16_t buffer[RING_BUF_SAMPLES];
} SpeakerRing;

/* Per-channel PipeWire stream. Passed as userdata to the process callback
 * so it knows which channel to mix and where to find the mixer state. */
typedef struct {
    struct pw_stream *stream;
    int channel;
    struct AudioMixer *mixer;
} ChannelStream;

typedef struct AudioMixer {
    struct pw_thread_loop *thread_loop;
    ChannelStream channels[MIXER_NUM_CHANNELS];
    SpeakerRing rings[MIXER_MAX_SPEAKERS][MIXER_NUM_CHANNELS];
} AudioMixer;

static AudioMixer *g_mixer = NULL;

/* --- Process callback --- */

/**
 * PipeWire process callback — runs in the PipeWire RT thread.
 *
 * For this channel, reads available samples from each speaker's ring buffer
 * into local temp buffers (batch read), then mixes them with int32
 * accumulation and int16 clamping. Fills silence when no data available.
 */
static void on_process(void *userdata)
{
    ChannelStream *cs = userdata;
    AudioMixer *mixer = cs->mixer;
    int channel = cs->channel;

    struct pw_buffer *pwbuf = pw_stream_dequeue_buffer(cs->stream);
    if (!pwbuf) {
        pw_log_warn("audio-mixer: out of buffers");
        return;
    }

    struct spa_buffer *buf = pwbuf->buffer;
    int16_t *dst = buf->datas[0].data;
    if (!dst) {
        pw_stream_queue_buffer(cs->stream, pwbuf);
        return;
    }

    uint32_t stride = sizeof(int16_t);
    int32_t max_frames = (int32_t)(buf->datas[0].maxsize / stride);
    int32_t n_frames = max_frames;
    if (pwbuf->requested > 0 && (int32_t)pwbuf->requested < n_frames) {
        n_frames = (int32_t)pwbuf->requested;
    }

    /* Phase 1: Batch-read each speaker's available data into local temp
     * buffers. One spa_ringbuffer_read_data + read_update per speaker —
     * cache-friendly and avoids per-sample atomic index reads. */
    int16_t tmp[MIXER_MAX_SPEAKERS][MAX_PROCESS_FRAMES];
    int32_t speaker_avail[MIXER_MAX_SPEAKERS];

    if (n_frames > MAX_PROCESS_FRAMES) {
        n_frames = MAX_PROCESS_FRAMES;
    }

    for (int s = 0; s < MIXER_MAX_SPEAKERS; s++) {
        SpeakerRing *sr = &mixer->rings[s][channel];
        uint32_t index;
        int32_t avail = spa_ringbuffer_get_read_index(&sr->ring, &index);

        int32_t to_read = avail > 0 ? avail : 0;
        if (to_read > n_frames) {
            to_read = n_frames;
        }

        if (to_read > 0) {
            spa_ringbuffer_read_data(&sr->ring,
                sr->buffer, RING_BUF_SAMPLES * sizeof(int16_t),
                (index % RING_BUF_SAMPLES) * sizeof(int16_t),
                tmp[s], (uint32_t)to_read * stride);
            spa_ringbuffer_read_update(&sr->ring, (int32_t)(index + (uint32_t)to_read));
        }

        speaker_avail[s] = to_read;
    }

    /* Phase 2: Mix temp buffers into output.
     * int32 accumulator prevents int16 overflow when summing speakers. */
    for (int32_t i = 0; i < n_frames; i++) {
        int32_t sum = 0;
        for (int s = 0; s < MIXER_MAX_SPEAKERS; s++) {
            if (i < speaker_avail[s]) {
                sum += tmp[s][i];
            }
        }
        /* Clamp to int16 range */
        if (sum > 32767) {
            sum = 32767;
        } else if (sum < -32768) {
            sum = -32768;
        }
        dst[i] = (int16_t)sum;
    }

    buf->datas[0].chunk->offset = 0;
    buf->datas[0].chunk->stride = (int32_t)stride;
    buf->datas[0].chunk->size = (uint32_t)n_frames * stride;

    pw_stream_queue_buffer(cs->stream, pwbuf);
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .process = on_process,
};

static const char *channel_names[MIXER_NUM_CHANNELS] = {
    "voxswap-original",
    "voxswap-lang1",
    "voxswap-lang2",
};

/* --- Lifecycle --- */

int audio_mixer_init(void)
{
    pw_init(NULL, NULL);

    AudioMixer *mixer = calloc(1, sizeof(AudioMixer));
    if (!mixer) {
        fprintf(stderr, "audio-mixer: calloc failed\n");
        goto cleanup;
    }

    for (int s = 0; s < MIXER_MAX_SPEAKERS; s++) {
        for (int c = 0; c < MIXER_NUM_CHANNELS; c++) {
            spa_ringbuffer_init(&mixer->rings[s][c].ring);
        }
    }

    mixer->thread_loop = pw_thread_loop_new("audio-mixer", NULL);
    if (!mixer->thread_loop) {
        fprintf(stderr, "audio-mixer: pw_thread_loop_new failed\n");
        goto cleanup;
    }

    pw_thread_loop_lock(mixer->thread_loop);

    if (pw_thread_loop_start(mixer->thread_loop) != 0) {
        fprintf(stderr, "audio-mixer: pw_thread_loop_start failed\n");
        goto cleanup_unlock;
    }

    struct pw_loop *loop = pw_thread_loop_get_loop(mixer->thread_loop);

    for (int i = 0; i < MIXER_NUM_CHANNELS; i++) {
        mixer->channels[i].channel = i;
        mixer->channels[i].mixer = mixer;

        struct pw_properties *props = pw_properties_new(
            PW_KEY_MEDIA_TYPE, "Audio",
            PW_KEY_MEDIA_CATEGORY, "Playback",
            PW_KEY_MEDIA_ROLE, "Communication",
            PW_KEY_NODE_NAME, channel_names[i],
            NULL);

        mixer->channels[i].stream = pw_stream_new_simple(
            loop,
            channel_names[i],
            props,
            &stream_events,
            &mixer->channels[i]);

        if (!mixer->channels[i].stream) {
            fprintf(stderr, "audio-mixer: pw_stream_new_simple failed for channel %d\n", i);
            goto cleanup_unlock;
        }

        uint8_t buf[1024];
        struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buf, sizeof(buf));
        const struct spa_pod *params[1];
        params[0] = spa_format_audio_raw_build(&b, SPA_PARAM_EnumFormat,
            &SPA_AUDIO_INFO_RAW_INIT(
                .format = SPA_AUDIO_FORMAT_S16,
                .channels = CHANNELS,
                .rate = SAMPLE_RATE));

        int res = pw_stream_connect(mixer->channels[i].stream,
            PW_DIRECTION_OUTPUT,
            PW_ID_ANY,
            PW_STREAM_FLAG_AUTOCONNECT |
            PW_STREAM_FLAG_MAP_BUFFERS |
            PW_STREAM_FLAG_RT_PROCESS,
            params, 1);

        if (res < 0) {
            fprintf(stderr, "audio-mixer: pw_stream_connect failed for channel %d: %s\n",
                    i, spa_strerror(res));
            goto cleanup_unlock;
        }
    }

    pw_thread_loop_unlock(mixer->thread_loop);

    g_mixer = mixer;
    printf("audio-mixer: initialized (%d channels, %d Hz, mono)\n",
           MIXER_NUM_CHANNELS, SAMPLE_RATE);
    return 0;

cleanup_unlock:
    /* Destroy streams while holding the lock — prevents the PipeWire RT thread
     * from firing on_process on a partially-destroyed stream */
    for (int i = 0; i < MIXER_NUM_CHANNELS; i++) {
        if (mixer->channels[i].stream) {
            pw_stream_destroy(mixer->channels[i].stream);
        }
    }
    pw_thread_loop_unlock(mixer->thread_loop);

cleanup:
    if (mixer) {
        if (mixer->thread_loop) {
            pw_thread_loop_stop(mixer->thread_loop);
            pw_thread_loop_destroy(mixer->thread_loop);
        }
        free(mixer);
    }
    pw_deinit();
    return -1;
}

void audio_mixer_shutdown(void)
{
    if (!g_mixer) {
        return;
    }

    AudioMixer *mixer = g_mixer;
    g_mixer = NULL;

    pw_thread_loop_lock(mixer->thread_loop);
    for (int i = 0; i < MIXER_NUM_CHANNELS; i++) {
        if (mixer->channels[i].stream) {
            pw_stream_destroy(mixer->channels[i].stream);
            mixer->channels[i].stream = NULL;
        }
    }
    pw_thread_loop_unlock(mixer->thread_loop);

    pw_thread_loop_stop(mixer->thread_loop);
    pw_thread_loop_destroy(mixer->thread_loop);
    free(mixer);

    pw_deinit();
    printf("audio-mixer: shut down\n");
}

/* --- Write --- */

void mixer_write_audio(uint8_t speaker_id, uint8_t stream_type,
                       const uint8_t *pcm, size_t pcm_len,
                       uint32_t sequence, uint32_t timestamp)
{
    (void)sequence;
    (void)timestamp;

    if (!g_mixer) {
        return;
    }

    if (speaker_id >= MIXER_MAX_SPEAKERS || stream_type >= MIXER_NUM_CHANNELS) {
        return;
    }

    SpeakerRing *sr = &g_mixer->rings[speaker_id][stream_type];
    uint32_t n_samples = (uint32_t)(pcm_len / sizeof(int16_t));

    uint32_t index;
    int32_t filled = spa_ringbuffer_get_write_index(&sr->ring, &index);

    /* Drop incoming packet if ring buffer is full — cannot overwrite oldest
     * data because that would modify the read index from the writer side,
     * breaking the SPSC lock-free contract */
    uint32_t avail = RING_BUF_SAMPLES - (uint32_t)filled;
    if (avail < n_samples) {
        return;
    }

    spa_ringbuffer_write_data(&sr->ring,
        sr->buffer, RING_BUF_SAMPLES * sizeof(int16_t),
        (index % RING_BUF_SAMPLES) * sizeof(int16_t),
        pcm, (uint32_t)pcm_len);

    spa_ringbuffer_write_update(&sr->ring, (int32_t)(index + n_samples));
}

#else /* !HAS_PIPEWIRE */

int audio_mixer_init(void)
{
    printf("audio-mixer: PipeWire not available, audio output disabled\n");
    return 0;
}

void audio_mixer_shutdown(void)
{
}

void mixer_write_audio(uint8_t speaker_id, uint8_t stream_type,
                       const uint8_t *pcm, size_t pcm_len,
                       uint32_t sequence, uint32_t timestamp)
{
    (void)pcm;
    (void)timestamp;
    printf("audio: speaker=%u stream=%u seq=%u len=%zu\n",
           speaker_id, stream_type, sequence, pcm_len);
}

#endif /* HAS_PIPEWIRE */
