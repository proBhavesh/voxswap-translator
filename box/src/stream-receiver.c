#include "stream-receiver.h"

#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <unistd.h>

#include "audio-mixer.h"
#include "settings-server.h"

/* --- Internal helpers --- */

/**
 * Set a file descriptor to non-blocking mode.
 * Required for all fds registered with epoll — a blocking read/accept
 * would stall the entire single-threaded event loop.
 *
 * @return 0 on success, -1 on failure
 */
static int set_nonblocking(int fd)
{
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags == -1) {
        return -1;
    }
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

/* --- Speaker management --- */

/**
 * Disconnect a speaker: remove from epoll, close TCP fd, reset slot.
 * Safe to call on any slot — returns immediately if not active.
 */
static void disconnect_speaker(StreamReceiver *sr, int slot)
{
    Speaker *spk = &sr->speakers[slot];
    if (!spk->active) {
        return;
    }

    if (spk->registered) {
        printf("stream-receiver: speaker %d (%s) disconnected\n",
               slot, spk->speaker_name);
    } else {
        printf("stream-receiver: speaker slot %d disconnected (unregistered)\n", slot);
    }

    epoll_ctl(sr->epoll_fd, EPOLL_CTL_DEL, spk->tcp_fd, NULL);
    close(spk->tcp_fd);
    memset(spk, 0, sizeof(Speaker));
    spk->tcp_fd = -1;

    /* If this speaker was admin, transfer to next registered speaker */
    int next_admin = -1;
    for (int i = 0; i < MAX_SPEAKERS; i++) {
        if (i != slot && sr->speakers[i].active && sr->speakers[i].registered) {
            next_admin = i;
            break;
        }
    }
    settings_on_speaker_disconnect(sr->settings, (uint8_t)slot, next_admin);
}

/**
 * Send an ERROR message on a TCP fd.
 * Format: type (1B=0xFF) | length (2B BE) | error_code (1B) | error_msg (null-term)
 *
 * Best-effort write — caller is responsible for closing the fd.
 */
static void send_error(int fd, uint8_t error_code, const char *msg)
{
    size_t msg_len = strlen(msg) + 1;   /* include null terminator */
    size_t payload_len = 1 + msg_len;   /* error_code + message */
    size_t total = 3 + payload_len;     /* header + payload */

    uint8_t buf[256];
    if (total > sizeof(buf)) {
        return;
    }

    buf[0] = MSG_ERROR;
    buf[1] = (uint8_t)((payload_len >> 8) & 0xFFU);
    buf[2] = (uint8_t)(payload_len & 0xFFU);
    buf[3] = error_code;
    memcpy(&buf[4], msg, msg_len);

    (void)write(fd, buf, total);
}

/* --- TCP accept --- */

/**
 * Accept a new TCP connection and assign a speaker slot.
 *
 * Two-phase activation: the slot is marked active immediately (consuming
 * one of 3 slots) but registered=false until the phone sends REGISTER.
 * If REGISTER doesn't arrive within REGISTER_TIMEOUT_S, the timeout
 * check disconnects the speaker to free the slot.
 */
static void handle_tcp_accept(StreamReceiver *sr)
{
    struct sockaddr_in client_addr;
    socklen_t addr_len = sizeof(client_addr);
    int client_fd = accept(sr->tcp_listen_fd,
                           (struct sockaddr *)&client_addr, &addr_len);
    if (client_fd == -1) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return;
        }
        fprintf(stderr, "stream-receiver: accept() failed: %s\n", strerror(errno));
        return;
    }

    if (set_nonblocking(client_fd) == -1) {
        fprintf(stderr, "stream-receiver: client set_nonblocking failed: %s\n",
                strerror(errno));
        close(client_fd);
        return;
    }

    /* Find first inactive speaker slot */
    int slot = -1;
    for (int i = 0; i < MAX_SPEAKERS; i++) {
        if (!sr->speakers[i].active) {
            slot = i;
            break;
        }
    }

    if (slot == -1) {
        send_error(client_fd, ERR_SERVER_FULL, "server full");
        close(client_fd);
        printf("stream-receiver: connection rejected (server full)\n");
        return;
    }

    /* Initialize speaker slot — calloc zeroed it, but be explicit */
    Speaker *spk = &sr->speakers[slot];
    memset(spk, 0, sizeof(Speaker));
    spk->active = true;
    spk->registered = false;
    spk->tcp_fd = client_fd;
    spk->speaker_id = (uint8_t)slot;
    clock_gettime(CLOCK_MONOTONIC, &spk->connected_at);

    struct epoll_event ev;
    ev.events = EPOLLIN;
    ev.data.fd = client_fd;
    if (epoll_ctl(sr->epoll_fd, EPOLL_CTL_ADD, client_fd, &ev) == -1) {
        fprintf(stderr, "stream-receiver: epoll_ctl(client) failed: %s\n", strerror(errno));
        memset(spk, 0, sizeof(Speaker));
        spk->tcp_fd = -1;
        close(client_fd);
        return;
    }

    printf("stream-receiver: speaker slot %d: TCP connection accepted\n", slot);
}

/* --- TCP message handling --- */

/**
 * Send REGISTER_ACK to a speaker.
 * Format: type (1B=0x02) | length (2B BE) | speaker_id (1B)
 *         + target_lang1 (null-term) + target_lang2 (null-term)
 */
static void send_register_ack(StreamReceiver *sr, int slot)
{
    Speaker *spk = &sr->speakers[slot];

    char lang1[LANG_CODE_MAX];
    char lang2[LANG_CODE_MAX];
    settings_get_languages(sr->settings, lang1, lang2);

    size_t lang1_len = strlen(lang1) + 1;   /* +1 for null terminator */
    size_t lang2_len = strlen(lang2) + 1;
    size_t payload_len = 1 + lang1_len + lang2_len;     /* speaker_id + langs */
    size_t total = 3 + payload_len;                     /* header + payload */

    uint8_t buf[256];
    if (total > sizeof(buf)) {
        return;
    }

    buf[0] = MSG_REGISTER_ACK;
    buf[1] = (uint8_t)((payload_len >> 8) & 0xFFU);
    buf[2] = (uint8_t)(payload_len & 0xFFU);
    buf[3] = spk->speaker_id;
    memcpy(&buf[4], lang1, lang1_len);
    memcpy(&buf[4 + lang1_len], lang2, lang2_len);

    ssize_t written = write(spk->tcp_fd, buf, total);
    if (written < 0) {
        fprintf(stderr, "stream-receiver: send_register_ack to speaker %d failed: %s\n",
                slot, strerror(errno));
        disconnect_speaker(sr, slot);
    }
}

/**
 * Handle REGISTER message from a phone.
 * Payload: speaker_name (null-term UTF-8) + source_language (null-term UTF-8)
 *
 * Sets registered=true on success and sends REGISTER_ACK with speaker_id
 * and target languages. Disconnects on malformed payload.
 */
static void handle_register(StreamReceiver *sr, int slot,
                            const uint8_t *payload, size_t payload_len)
{
    Speaker *spk = &sr->speakers[slot];

    if (spk->registered) {
        printf("stream-receiver: speaker %d: duplicate REGISTER (ignored)\n", slot);
        return;
    }

    /* Find first null byte — end of speaker_name */
    const uint8_t *name_end = memchr(payload, '\0', payload_len);
    if (!name_end) {
        fprintf(stderr, "stream-receiver: speaker %d: REGISTER missing name terminator\n", slot);
        disconnect_speaker(sr, slot);
        return;
    }

    size_t name_len = (size_t)(name_end - payload);
    size_t lang_offset = name_len + 1;

    if (lang_offset >= payload_len) {
        fprintf(stderr, "stream-receiver: speaker %d: REGISTER missing language field\n", slot);
        disconnect_speaker(sr, slot);
        return;
    }

    /* Find second null byte — end of source_language */
    const uint8_t *lang_end = memchr(payload + lang_offset, '\0',
                                     payload_len - lang_offset);
    if (!lang_end) {
        fprintf(stderr, "stream-receiver: speaker %d: REGISTER missing language terminator\n",
                slot);
        disconnect_speaker(sr, slot);
        return;
    }

    /* Copy with bounds checking */
    strncpy(spk->speaker_name, (const char *)payload, SPEAKER_NAME_MAX - 1);
    spk->speaker_name[SPEAKER_NAME_MAX - 1] = '\0';

    strncpy(spk->source_language, (const char *)(payload + lang_offset), LANG_CODE_MAX - 1);
    spk->source_language[LANG_CODE_MAX - 1] = '\0';

    spk->registered = true;
    clock_gettime(CLOCK_MONOTONIC, &spk->last_heartbeat);

    /* First speaker to register becomes admin (no-op if admin already assigned) */
    settings_assign_admin(sr->settings, (uint8_t)slot);

    printf("stream-receiver: speaker %d registered: name='%s' lang='%s'\n",
           slot, spk->speaker_name, spk->source_language);

    send_register_ack(sr, slot);
}

/** Handle HEARTBEAT message — update last_heartbeat timestamp. */
static void handle_heartbeat(StreamReceiver *sr, int slot)
{
    clock_gettime(CLOCK_MONOTONIC, &sr->speakers[slot].last_heartbeat);
}

/* --- Settings messages --- */

/**
 * Send SESSION_CONFIG to a single speaker.
 * Format: type (1B=0x05) | length (2B BE) | target_lang1 (null-term) + target_lang2 (null-term)
 */
static void send_session_config(StreamReceiver *sr, int slot)
{
    Speaker *spk = &sr->speakers[slot];

    char lang1[LANG_CODE_MAX];
    char lang2[LANG_CODE_MAX];
    settings_get_languages(sr->settings, lang1, lang2);

    size_t lang1_len = strlen(lang1) + 1;
    size_t lang2_len = strlen(lang2) + 1;
    size_t payload_len = lang1_len + lang2_len;
    size_t total = 3 + payload_len;

    uint8_t buf[256];
    if (total > sizeof(buf)) {
        return;
    }

    buf[0] = MSG_SESSION_CONFIG;
    buf[1] = (uint8_t)((payload_len >> 8) & 0xFFU);
    buf[2] = (uint8_t)(payload_len & 0xFFU);
    memcpy(&buf[3], lang1, lang1_len);
    memcpy(&buf[3 + lang1_len], lang2, lang2_len);

    ssize_t written = write(spk->tcp_fd, buf, total);
    if (written < 0) {
        fprintf(stderr, "stream-receiver: send_session_config to speaker %d failed: %s\n",
                slot, strerror(errno));
        disconnect_speaker(sr, slot);
    }
}

/** Send SESSION_CONFIG to all registered speakers. */
static void broadcast_session_config(StreamReceiver *sr)
{
    for (int i = 0; i < MAX_SPEAKERS; i++) {
        if (sr->speakers[i].active && sr->speakers[i].registered) {
            send_session_config(sr, i);
        }
    }
}

/**
 * Handle SET_LANGUAGES message from admin phone.
 * Payload: target_lang1 (null-term UTF-8) + target_lang2 (null-term UTF-8)
 *
 * Only the admin speaker can change languages. On success, broadcasts
 * SESSION_CONFIG to all registered speakers.
 */
static void handle_set_languages(StreamReceiver *sr, int slot,
                                 const uint8_t *payload, size_t payload_len)
{
    Speaker *spk = &sr->speakers[slot];

    if (!spk->registered) {
        return;
    }

    /* Parse first language (null-terminated) */
    const uint8_t *lang1_end = memchr(payload, '\0', payload_len);
    if (!lang1_end) {
        fprintf(stderr, "stream-receiver: speaker %d: SET_LANGUAGES missing lang1 terminator\n",
                slot);
        send_error(spk->tcp_fd, ERR_INVALID_MESSAGE, "missing lang1 terminator");
        return;
    }

    size_t lang1_len = (size_t)(lang1_end - payload);
    size_t lang2_offset = lang1_len + 1;

    if (lang2_offset >= payload_len) {
        fprintf(stderr, "stream-receiver: speaker %d: SET_LANGUAGES missing lang2 field\n", slot);
        send_error(spk->tcp_fd, ERR_INVALID_MESSAGE, "missing lang2 field");
        return;
    }

    /* Parse second language (null-terminated) */
    const uint8_t *lang2_end = memchr(payload + lang2_offset, '\0',
                                       payload_len - lang2_offset);
    if (!lang2_end) {
        fprintf(stderr, "stream-receiver: speaker %d: SET_LANGUAGES missing lang2 terminator\n",
                slot);
        send_error(spk->tcp_fd, ERR_INVALID_MESSAGE, "missing lang2 terminator");
        return;
    }

    const char *lang1 = (const char *)payload;
    const char *lang2 = (const char *)(payload + lang2_offset);

    int result = settings_set_languages(sr->settings, spk->speaker_id, lang1, lang2);

    if (result == -1) {
        printf("stream-receiver: speaker %d: SET_LANGUAGES rejected (not admin)\n", slot);
        send_error(spk->tcp_fd, ERR_NOT_ADMIN, "not admin");
        return;
    }

    if (result == -2) {
        fprintf(stderr, "stream-receiver: speaker %d: SET_LANGUAGES invalid input\n", slot);
        send_error(spk->tcp_fd, ERR_INVALID_MESSAGE, "invalid language code");
        return;
    }

    broadcast_session_config(sr);
}

/** Dispatch a complete TCP message to the appropriate handler. */
static void handle_tcp_message(StreamReceiver *sr, int slot,
                               uint8_t type, const uint8_t *payload,
                               size_t payload_len)
{
    switch (type) {
    case MSG_REGISTER:
        handle_register(sr, slot, payload, payload_len);
        break;
    case MSG_HEARTBEAT:
        handle_heartbeat(sr, slot);
        break;
    case MSG_SET_LANGUAGES:
        handle_set_languages(sr, slot, payload, payload_len);
        break;
    default:
        printf("stream-receiver: speaker %d: unknown message type 0x%02X\n",
               slot, type);
        break;
    }
}

/**
 * Parse complete messages from a speaker's TCP read buffer.
 *
 * TCP is a byte stream — a single read() may deliver partial messages,
 * multiple messages, or a mix. This function extracts complete messages
 * (type 1B | length 2B BE | payload) and shifts the remainder forward.
 */
static void process_tcp_buffer(StreamReceiver *sr, int slot)
{
    Speaker *spk = &sr->speakers[slot];

    while (spk->read_buf_len >= 3) {
        uint8_t type = spk->read_buf[0];
        size_t payload_len = ((size_t)spk->read_buf[1] << 8u) | spk->read_buf[2];
        size_t msg_len = 3 + payload_len;

        /* Detect messages that will never fit in the buffer — disconnect
         * immediately instead of waiting for the buffer to fill and overflow */
        if (msg_len > TCP_READ_BUF_SIZE) {
            fprintf(stderr, "stream-receiver: speaker %d: message too large "
                    "(%zu bytes, buffer %d)\n", slot, msg_len, TCP_READ_BUF_SIZE);
            disconnect_speaker(sr, slot);
            return;
        }

        if (spk->read_buf_len < msg_len) {
            break;  /* Incomplete message — wait for more data */
        }

        handle_tcp_message(sr, slot, type, &spk->read_buf[3], payload_len);

        /* Message handler may have disconnected the speaker (e.g. malformed
         * REGISTER payload). Check before touching the buffer further. */
        if (!spk->active) {
            return;
        }

        /* Shift remaining data to front of buffer */
        size_t remaining = spk->read_buf_len - msg_len;
        if (remaining > 0) {
            memmove(spk->read_buf, spk->read_buf + msg_len, remaining);
        }
        spk->read_buf_len = remaining;
    }
}

/* --- TCP read --- */

/**
 * Read available data from a speaker's TCP fd into their partial
 * read buffer, then parse any complete messages.
 */
static void handle_tcp_read(StreamReceiver *sr, int slot)
{
    Speaker *spk = &sr->speakers[slot];

    size_t space = sizeof(spk->read_buf) - spk->read_buf_len;
    if (space == 0) {
        fprintf(stderr, "stream-receiver: speaker %d: read buffer overflow\n", slot);
        disconnect_speaker(sr, slot);
        return;
    }

    ssize_t n = read(spk->tcp_fd, spk->read_buf + spk->read_buf_len, space);

    if (n == 0) {
        /* EOF — peer closed connection */
        disconnect_speaker(sr, slot);
        return;
    }

    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return;
        }
        fprintf(stderr, "stream-receiver: speaker %d: read() failed: %s\n",
                slot, strerror(errno));
        disconnect_speaker(sr, slot);
        return;
    }

    spk->read_buf_len += (size_t)n;
    process_tcp_buffer(sr, slot);
}

/* --- UDP audio --- */

/**
 * Receive and validate a UDP audio packet, hand off to mixer.
 *
 * Packet layout: speaker_id (1B) | stream_type (1B) | sequence (4B BE)
 *                | timestamp (4B BE) | PCM payload (640B)
 *
 * Drops packets from unknown/unregistered speakers and invalid stream
 * types silently. Logs sequence gaps for packet loss visibility.
 */
static void handle_udp(StreamReceiver *sr)
{
    uint8_t buf[UDP_PACKET_SIZE];
    ssize_t received = recvfrom(sr->udp_fd, buf, sizeof(buf), 0, NULL, NULL);

    if (received < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return;
        }
        fprintf(stderr, "stream-receiver: recvfrom() failed: %s\n", strerror(errno));
        return;
    }

    if (received < UDP_HEADER_SIZE) {
        return;
    }

    /* Parse header */
    uint8_t speaker_id = buf[0];
    uint8_t stream_type = buf[1];
    uint32_t sequence = ((uint32_t)buf[2] << 24u) | ((uint32_t)buf[3] << 16u)
                        | ((uint32_t)buf[4] << 8u) | (uint32_t)buf[5];
    uint32_t timestamp = ((uint32_t)buf[6] << 24u) | ((uint32_t)buf[7] << 16u)
                         | ((uint32_t)buf[8] << 8u) | (uint32_t)buf[9];

    /* Validate speaker: must be a valid, registered slot */
    if (speaker_id >= MAX_SPEAKERS) {
        return;
    }
    Speaker *spk = &sr->speakers[speaker_id];
    if (!spk->active || !spk->registered) {
        return;
    }

    if (stream_type >= NUM_STREAM_TYPES) {
        return;
    }

    /* Sequence gap detection — log when packets are lost or reordered */
    if (spk->seq_initialized[stream_type]) {
        uint32_t expected = spk->last_seq[stream_type] + 1;
        if (sequence != expected) {
            int32_t gap = (int32_t)(sequence - expected);
            printf("stream-receiver: speaker %u stream %u: seq gap %d "
                   "(expected %u got %u)\n",
                   (unsigned)speaker_id, (unsigned)stream_type,
                   gap, expected, sequence);
        }
    }
    spk->last_seq[stream_type] = sequence;
    spk->seq_initialized[stream_type] = true;

    /* Hand audio to mixer */
    size_t pcm_len = (size_t)(received - UDP_HEADER_SIZE);
    mixer_write_audio(speaker_id, stream_type,
                      &buf[UDP_HEADER_SIZE], pcm_len,
                      sequence, timestamp);
}

/* --- Lifecycle --- */

StreamReceiver *stream_receiver_create(int epoll_fd, int tcp_port, int udp_port,
                                       Settings *settings)
{
    StreamReceiver *sr = calloc(1, sizeof(StreamReceiver));
    if (!sr) {
        fprintf(stderr, "stream-receiver: calloc failed\n");
        return NULL;
    }

    sr->tcp_listen_fd = -1;
    sr->udp_fd = -1;
    sr->epoll_fd = epoll_fd;
    sr->settings = settings;

    /* --- TCP listen socket --- */

    sr->tcp_listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (sr->tcp_listen_fd == -1) {
        fprintf(stderr, "stream-receiver: TCP socket() failed: %s\n", strerror(errno));
        goto cleanup;
    }

    /* SO_REUSEADDR prevents "address already in use" when restarting quickly
     * (TCP TIME_WAIT lingers for ~60s after previous instance closes) */
    int opt = 1;
    if (setsockopt(sr->tcp_listen_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) == -1) {
        fprintf(stderr, "stream-receiver: SO_REUSEADDR failed: %s\n", strerror(errno));
        goto cleanup;
    }

    if (set_nonblocking(sr->tcp_listen_fd) == -1) {
        fprintf(stderr, "stream-receiver: TCP set_nonblocking failed: %s\n", strerror(errno));
        goto cleanup;
    }

    struct sockaddr_in tcp_addr;
    memset(&tcp_addr, 0, sizeof(tcp_addr));
    tcp_addr.sin_family = AF_INET;
    tcp_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    tcp_addr.sin_port = htons((uint16_t)tcp_port);

    if (bind(sr->tcp_listen_fd, (struct sockaddr *)&tcp_addr, sizeof(tcp_addr)) == -1) {
        fprintf(stderr, "stream-receiver: TCP bind(port %d) failed: %s\n",
                tcp_port, strerror(errno));
        goto cleanup;
    }

    /* Backlog 4: at most 3 speakers + 1 pending accept */
    if (listen(sr->tcp_listen_fd, 4) == -1) {
        fprintf(stderr, "stream-receiver: listen() failed: %s\n", strerror(errno));
        goto cleanup;
    }

    struct epoll_event ev;
    ev.events = EPOLLIN;
    ev.data.fd = sr->tcp_listen_fd;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, sr->tcp_listen_fd, &ev) == -1) {
        fprintf(stderr, "stream-receiver: epoll_ctl(TCP listen) failed: %s\n", strerror(errno));
        goto cleanup;
    }

    /* --- UDP audio socket --- */

    sr->udp_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sr->udp_fd == -1) {
        fprintf(stderr, "stream-receiver: UDP socket() failed: %s\n", strerror(errno));
        goto cleanup;
    }

    if (set_nonblocking(sr->udp_fd) == -1) {
        fprintf(stderr, "stream-receiver: UDP set_nonblocking failed: %s\n", strerror(errno));
        goto cleanup;
    }

    struct sockaddr_in udp_addr;
    memset(&udp_addr, 0, sizeof(udp_addr));
    udp_addr.sin_family = AF_INET;
    udp_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    udp_addr.sin_port = htons((uint16_t)udp_port);

    if (bind(sr->udp_fd, (struct sockaddr *)&udp_addr, sizeof(udp_addr)) == -1) {
        fprintf(stderr, "stream-receiver: UDP bind(port %d) failed: %s\n",
                udp_port, strerror(errno));
        goto cleanup;
    }

    ev.events = EPOLLIN;
    ev.data.fd = sr->udp_fd;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, sr->udp_fd, &ev) == -1) {
        fprintf(stderr, "stream-receiver: epoll_ctl(UDP) failed: %s\n", strerror(errno));
        goto cleanup;
    }

    char lang1[LANG_CODE_MAX];
    char lang2[LANG_CODE_MAX];
    settings_get_languages(sr->settings, lang1, lang2);
    printf("stream-receiver: listening TCP=%d UDP=%d target1=%s target2=%s\n",
           tcp_port, udp_port, lang1, lang2);

    return sr;

cleanup:
    /* close() on a fd automatically removes it from epoll when no other
     * references to the underlying file description exist (which is the case
     * here since we just created these sockets) */
    if (sr->tcp_listen_fd != -1) {
        close(sr->tcp_listen_fd);
    }
    if (sr->udp_fd != -1) {
        close(sr->udp_fd);
    }
    free(sr);
    return NULL;
}

void stream_receiver_destroy(StreamReceiver *sr)
{
    if (!sr) {
        return;
    }

    /* Disconnect all active speakers */
    for (int i = 0; i < MAX_SPEAKERS; i++) {
        disconnect_speaker(sr, i);
    }

    /* Remove and close listening sockets — explicit epoll removal since
     * the epoll fd is borrowed from main and will outlive us */
    if (sr->tcp_listen_fd != -1) {
        epoll_ctl(sr->epoll_fd, EPOLL_CTL_DEL, sr->tcp_listen_fd, NULL);
        close(sr->tcp_listen_fd);
    }

    if (sr->udp_fd != -1) {
        epoll_ctl(sr->epoll_fd, EPOLL_CTL_DEL, sr->udp_fd, NULL);
        close(sr->udp_fd);
    }

    printf("stream-receiver: destroyed\n");
    free(sr);
}

/* --- Event dispatch --- */

bool stream_receiver_handle_event(StreamReceiver *sr, int fd, uint32_t events)
{
    /* EPOLLHUP/EPOLLERR are handled indirectly — read()/accept()/recvfrom()
     * return errors which the handlers already process correctly */
    (void)events;

    if (fd == sr->tcp_listen_fd) {
        handle_tcp_accept(sr);
        return true;
    }

    if (fd == sr->udp_fd) {
        handle_udp(sr);
        return true;
    }

    /* Check if fd belongs to a connected speaker */
    for (int i = 0; i < MAX_SPEAKERS; i++) {
        if (sr->speakers[i].active && sr->speakers[i].tcp_fd == fd) {
            handle_tcp_read(sr, i);
            return true;
        }
    }

    return false;
}

/* --- Timeout checking --- */

/** @return seconds elapsed between two CLOCK_MONOTONIC timespecs. */
static double timespec_diff_s(const struct timespec *a, const struct timespec *b)
{
    return (double)(a->tv_sec - b->tv_sec)
         + (double)(a->tv_nsec - b->tv_nsec) / 1e9;
}

/**
 * Check all speaker slots for timeout conditions. Called every ~1 second.
 *
 * Two cases:
 *   1. Active but not registered — REGISTER_TIMEOUT_S since TCP accept.
 *      Prevents malformed clients from permanently consuming a slot.
 *   2. Active and registered — HEARTBEAT_TIMEOUT_S since last heartbeat.
 *      Detects dead connections (phone crash, WiFi drop, etc.).
 */
void stream_receiver_check_timeouts(StreamReceiver *sr)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);

    for (int i = 0; i < MAX_SPEAKERS; i++) {
        Speaker *spk = &sr->speakers[i];
        if (!spk->active) {
            continue;
        }

        if (!spk->registered) {
            if (timespec_diff_s(&now, &spk->connected_at) > REGISTER_TIMEOUT_S) {
                printf("stream-receiver: speaker slot %d: registration timeout\n", i);
                disconnect_speaker(sr, i);
            }
        } else {
            if (timespec_diff_s(&now, &spk->last_heartbeat) > HEARTBEAT_TIMEOUT_S) {
                printf("stream-receiver: speaker %d (%s): heartbeat timeout\n",
                       i, spk->speaker_name);
                disconnect_speaker(sr, i);
            }
        }
    }
}
