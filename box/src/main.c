#include <errno.h>
#include <getopt.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/timerfd.h>
#include <unistd.h>

#include "wifi-hotspot.h"
#include "stream-receiver.h"
#include "audio-mixer.h"
#include "auracast-broadcast.h"
#include "settings-server.h"

#define DEFAULT_TCP_PORT 7700
#define DEFAULT_UDP_PORT 7701
#define MAX_EVENTS       16

static volatile sig_atomic_t running = 1;

static void signal_handler(int sig)
{
    (void)sig;
    running = 0;
}

/**
 * Parse a port number string. Returns the port (1-65535) or -1 on error.
 * Uses strtol() instead of atoi() to detect invalid input.
 */
static int parse_port(const char *str)
{
    char *endptr;
    errno = 0;
    long val = strtol(str, &endptr, 10);

    if (errno != 0 || *endptr != '\0' || endptr == str) {
        return -1;
    }
    if (val < 1 || val > 65535) {
        return -1;
    }
    return (int)val;
}

static void print_usage(const char *prog_name)
{
    fprintf(stderr,
            "Usage: %s [options]\n"
            "\n"
            "Languages (optional — first phone sets them if omitted):\n"
            "  --target1 LANG      First target language code (e.g. fr)\n"
            "  --target2 LANG      Second target language code (e.g. de)\n"
            "\n"
            "Network:\n"
            "  --tcp-port PORT     TCP control port (default: %d)\n"
            "  --udp-port PORT     UDP audio port (default: %d)\n"
            "\n"
            "WiFi hotspot:\n"
            "  --no-hotspot        Skip WiFi hotspot (for dev machine testing)\n"
            "  --wifi-iface IFACE  Wireless interface (default: wlan0)\n"
            "  --wifi-pass PASS    WPA2 password (default: voxswap123)\n"
            "\n"
            "  --help              Show this message\n",
            prog_name, DEFAULT_TCP_PORT, DEFAULT_UDP_PORT);
}

int main(int argc, char *argv[])
{
    const char *target1 = NULL;
    const char *target2 = NULL;
    int tcp_port = DEFAULT_TCP_PORT;
    int udp_port = DEFAULT_UDP_PORT;
    bool no_hotspot = false;
    const char *wifi_iface = "wlan0";
    const char *wifi_password = NULL;

    static struct option long_options[] = {
        {"target1",    required_argument, NULL, 't'},
        {"target2",    required_argument, NULL, 'u'},
        {"tcp-port",   required_argument, NULL, 'p'},
        {"udp-port",   required_argument, NULL, 'd'},
        {"no-hotspot", no_argument,       NULL, 'n'},
        {"wifi-iface", required_argument, NULL, 'i'},
        {"wifi-pass",  required_argument, NULL, 'w'},
        {"help",       no_argument,       NULL, 'h'},
        {NULL,         0,                 NULL,  0 }
    };

    int opt;
    while ((opt = getopt_long(argc, argv, "", long_options, NULL)) != -1) {
        switch (opt) {
        case 't':
            target1 = optarg;
            break;
        case 'u':
            target2 = optarg;
            break;
        case 'p':
            tcp_port = parse_port(optarg);
            if (tcp_port == -1) {
                fprintf(stderr, "Error: invalid TCP port '%s' (must be 1-65535)\n", optarg);
                return EXIT_FAILURE;
            }
            break;
        case 'd':
            udp_port = parse_port(optarg);
            if (udp_port == -1) {
                fprintf(stderr, "Error: invalid UDP port '%s' (must be 1-65535)\n", optarg);
                return EXIT_FAILURE;
            }
            break;
        case 'n':
            no_hotspot = true;
            break;
        case 'i':
            wifi_iface = optarg;
            break;
        case 'w':
            wifi_password = optarg;
            break;
        case 'h':
            print_usage(argv[0]);
            return EXIT_SUCCESS;
        default:
            print_usage(argv[0]);
            return EXIT_FAILURE;
        }
    }

    /* Target languages are optional — if not provided, the first phone
     * to connect will set them via the settings protocol. Use empty strings
     * as placeholders until then. */
    if (!target1) target1 = "";
    if (!target2) target2 = "";

    printf("VoxSwap Box starting...\n");
    printf("  target1=%s target2=%s tcp=%d udp=%d\n",
           target1[0] ? target1 : "(pending)", target2[0] ? target2 : "(pending)",
           tcp_port, udp_port);

    /* Install signal handlers with sigaction() — well-defined POSIX semantics
     * unlike signal() which varies across platforms (SysV auto-resets handler).
     *
     * sa_flags = 0: no SA_RESTART so epoll_wait returns EINTR on signal,
     * letting the loop check `running`. No SA_RESETHAND so handler stays
     * installed for subsequent signals. */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = signal_handler;
    sa.sa_flags = 0;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);

    /* Ignore SIGPIPE — writing to a TCP socket after the peer disconnects
     * delivers SIGPIPE which terminates the process by default. Without this,
     * send_register_ack() or send_error() would crash the daemon. */
    sa.sa_handler = SIG_IGN;
    sigaction(SIGPIPE, &sa, NULL);

    int ret = EXIT_FAILURE;
    int epoll_fd = -1;
    int timer_fd = -1;
    Settings *settings = NULL;
    StreamReceiver *sr = NULL;

    /* Init order: hotspot → settings → receiver → mixer.
     * Hotspot must be up before receiver starts (phones need WiFi to connect).
     * Settings must exist before receiver (receiver borrows the pointer).
     * Shutdown is reverse: mixer → receiver → settings → hotspot. */
    if (!no_hotspot) {
        if (wifi_hotspot_init(wifi_iface, wifi_password) != 0) {
            fprintf(stderr, "wifi_hotspot_init failed\n");
            goto cleanup;
        }
    }

    epoll_fd = epoll_create1(0);
    if (epoll_fd == -1) {
        fprintf(stderr, "epoll_create1 failed: %s\n", strerror(errno));
        goto cleanup;
    }

    timer_fd = timerfd_create(CLOCK_MONOTONIC, 0);
    if (timer_fd == -1) {
        fprintf(stderr, "timerfd_create failed: %s\n", strerror(errno));
        goto cleanup;
    }

    /* Arm timerfd: fires every 1 second for timeout checks.
     * Both it_value (first fire) and it_interval (repeat) set to 1s. */
    struct itimerspec ts;
    memset(&ts, 0, sizeof(ts));
    ts.it_value.tv_sec = 1;
    ts.it_interval.tv_sec = 1;
    if (timerfd_settime(timer_fd, 0, &ts, NULL) == -1) {
        fprintf(stderr, "timerfd_settime failed: %s\n", strerror(errno));
        goto cleanup;
    }

    struct epoll_event ev;
    ev.events = EPOLLIN;
    ev.data.fd = timer_fd;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, timer_fd, &ev) == -1) {
        fprintf(stderr, "epoll_ctl(timerfd) failed: %s\n", strerror(errno));
        goto cleanup;
    }

    settings = settings_create(target1, target2);
    if (!settings) {
        fprintf(stderr, "settings_create failed\n");
        goto cleanup;
    }

    sr = stream_receiver_create(epoll_fd, tcp_port, udp_port, settings);
    if (!sr) {
        fprintf(stderr, "stream_receiver_create failed\n");
        goto cleanup;
    }

    /* Non-fatal — daemon still receives and logs packets without audio output */
    if (audio_mixer_init() != 0) {
        fprintf(stderr, "audio_mixer_init failed (continuing without audio output)\n");
    }

    printf("VoxSwap Box ready.\n");

    struct epoll_event events[MAX_EVENTS];

    while (running) {
        int n = epoll_wait(epoll_fd, events, MAX_EVENTS, -1);

        if (n < 0) {
            if (errno == EINTR) {
                continue;   /* Signal interrupted — loop checks running */
            }
            fprintf(stderr, "epoll_wait failed: %s\n", strerror(errno));
            break;
        }

        for (int i = 0; i < n; i++) {
            int fd = events[i].data.fd;

            if (fd == timer_fd) {
                uint64_t expirations;
                (void)read(timer_fd, &expirations, sizeof(expirations));
                stream_receiver_check_timeouts(sr);
            } else if (!stream_receiver_handle_event(sr, fd, events[i].events)) {
                fprintf(stderr, "epoll: unknown fd %d (bug)\n", fd);
            }
        }
    }

    ret = EXIT_SUCCESS;

cleanup:
    audio_mixer_shutdown();
    if (sr) {
        stream_receiver_destroy(sr);
    }
    settings_destroy(settings);
    if (timer_fd != -1) {
        close(timer_fd);
    }
    if (epoll_fd != -1) {
        close(epoll_fd);
    }
    wifi_hotspot_shutdown();

    printf("VoxSwap Box shut down.\n");
    return ret;
}
