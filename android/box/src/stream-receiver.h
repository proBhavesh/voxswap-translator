#ifndef VOXSWAP_STREAM_RECEIVER_H
#define VOXSWAP_STREAM_RECEIVER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <time.h>

/* --- Protocol message types --- */

#define MSG_REGISTER       0x01
#define MSG_REGISTER_ACK   0x02
#define MSG_HEARTBEAT      0x03
#define MSG_SET_LANGUAGES  0x04
#define MSG_SESSION_CONFIG 0x05
#define MSG_ERROR          0xFF

/* --- Protocol error codes --- */

#define ERR_SERVER_FULL     0x01
#define ERR_INVALID_MESSAGE 0x02
#define ERR_NOT_ADMIN       0x03

/* --- Size constants --- */

#define MAX_SPEAKERS        3
#define SPEAKER_NAME_MAX    64
#define LANG_CODE_MAX       16
#define TCP_READ_BUF_SIZE   1024
#define NUM_STREAM_TYPES    3     /* 0=original, 1=target_lang1, 2=target_lang2 */

/* --- Timing --- */

#define REGISTER_TIMEOUT_S  10
#define HEARTBEAT_TIMEOUT_S 6

/* --- UDP packet layout --- */

#define UDP_HEADER_SIZE     10
#define UDP_PCM_SIZE        640   /* 320 samples x 2 bytes = 20ms at 16kHz */
#define UDP_PACKET_SIZE     650   /* UDP_HEADER_SIZE + UDP_PCM_SIZE */

/* Forward declaration — full definition in settings-server.h */
typedef struct Settings Settings;

/* --- Data structures --- */

/**
 * Per-speaker state. Tracks one TCP connection from a phone.
 *
 * Two-phase activation:
 *   active=true      → TCP connection exists, slot consumed
 *   registered=true  → REGISTER message received, speaker is usable
 *
 * A speaker that is active but not registered within REGISTER_TIMEOUT_S
 * gets disconnected, freeing the slot.
 */
typedef struct {
    bool active;
    bool registered;
    int tcp_fd;
    uint8_t speaker_id;
    char speaker_name[SPEAKER_NAME_MAX];
    char source_language[LANG_CODE_MAX];
    struct timespec connected_at;
    struct timespec last_heartbeat;

    /* TCP partial read buffer — a single read() may return half a message
     * or multiple messages concatenated. We accumulate bytes here and
     * parse complete messages from the front. */
    uint8_t read_buf[TCP_READ_BUF_SIZE];
    size_t read_buf_len;

    /* Per-stream sequence tracking for packet loss detection */
    uint32_t last_seq[NUM_STREAM_TYPES];
    bool seq_initialized[NUM_STREAM_TYPES];
} Speaker;

typedef struct {
    int tcp_listen_fd;
    int udp_fd;
    int epoll_fd;       /* Borrowed from main — not owned, not closed by us */

    Speaker speakers[MAX_SPEAKERS];

    Settings *settings;   /* Borrowed from main — not owned, not freed by us */
} StreamReceiver;

/* --- Public API --- */

/**
 * Create the stream receiver. Binds TCP listen and UDP sockets,
 * registers them with epoll_fd. Settings pointer is borrowed (not owned).
 * Returns NULL on failure.
 */
StreamReceiver *stream_receiver_create(int epoll_fd, int tcp_port, int udp_port,
                                       Settings *settings);

/** Disconnect all speakers, close sockets, free memory. */
void stream_receiver_destroy(StreamReceiver *sr);

/**
 * Handle an epoll event. Returns true if fd belongs to this receiver
 * and was handled. Called from main's epoll loop.
 */
bool stream_receiver_handle_event(StreamReceiver *sr, int fd, uint32_t events);

/**
 * Check for registration and heartbeat timeouts. Called every ~1 second
 * from main's timerfd handler.
 */
void stream_receiver_check_timeouts(StreamReceiver *sr);

#endif
