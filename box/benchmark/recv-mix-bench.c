/*
 * Benchmark: receive 9 UDP audio streams and mix them per channel.
 * Simulates the box's actual workload without PipeWire dependency.
 *
 * Build on board: gcc -O2 -o recv-mix-bench recv-mix-bench.c -lm
 * Run: ./recv-mix-bench
 */

#include <arpa/inet.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define UDP_PORT         7701
#define UDP_HEADER_SIZE  10
#define PCM_SAMPLES      320    /* 20ms at 16kHz */
#define PCM_BYTES        640
#define PACKET_SIZE      650
#define MAX_SPEAKERS     3
#define NUM_CHANNELS     3      /* original, lang1, lang2 */
#define MIX_BUF_SAMPLES  320

static volatile int running = 1;

static void handle_signal(int sig)
{
    (void)sig;
    running = 0;
}

/* Mix: sum int16 samples with clipping */
static void mix_into(int16_t *dst, const int16_t *src, int n)
{
    for (int i = 0; i < n; i++) {
        int32_t sum = (int32_t)dst[i] + (int32_t)src[i];
        if (sum > 32767) sum = 32767;
        if (sum < -32768) sum = -32768;
        dst[i] = (int16_t)sum;
    }
}

static double time_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}

int main(void)
{
    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);
    setvbuf(stdout, NULL, _IONBF, 0);

    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) { perror("socket"); return 1; }

    /* Increase receive buffer to handle bursts */
    int bufsize = 1024 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &bufsize, sizeof(bufsize));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(UDP_PORT);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        close(fd);
        return 1;
    }

    printf("listening on udp port %d\n", UDP_PORT);
    printf("waiting for packets... (ctrl+c to stop)\n\n");

    uint8_t buf[PACKET_SIZE];
    int16_t mix_buf[NUM_CHANNELS][MIX_BUF_SAMPLES];

    uint64_t total_packets = 0;
    uint64_t total_bytes = 0;
    uint64_t packets_per_speaker[MAX_SPEAKERS] = {0};
    double mix_time_total_us = 0;
    double start_time = time_ms();
    double last_report = start_time;

    while (running) {
        ssize_t n = recv(fd, buf, sizeof(buf), 0);
        if (n < UDP_HEADER_SIZE + PCM_BYTES) continue;

        uint8_t speaker_id = buf[0];
        uint8_t stream_type = buf[1];
        /* seq and timestamp at buf[2..9], not needed for benchmark */

        if (speaker_id >= MAX_SPEAKERS || stream_type >= NUM_CHANNELS)
            continue;

        total_packets++;
        total_bytes += (uint64_t)n;
        packets_per_speaker[speaker_id]++;

        /* Simulate per-channel mixing */
        int16_t *pcm = (int16_t *)(buf + UDP_HEADER_SIZE);

        double t0 = time_ms();
        mix_into(mix_buf[stream_type], pcm, PCM_SAMPLES);
        double t1 = time_ms();
        mix_time_total_us += (t1 - t0) * 1000.0;

        /* Report every 5 seconds */
        double now = time_ms();
        if (now - last_report >= 5000.0) {
            double elapsed = (now - start_time) / 1000.0;
            double pps = total_packets / elapsed;
            double mbps = (total_bytes * 8.0) / (elapsed * 1000000.0);
            double avg_mix_us = total_packets > 0
                ? mix_time_total_us / (double)total_packets : 0;

            /* Get CPU and memory usage */
            struct rusage usage;
            getrusage(RUSAGE_SELF, &usage);
            double user_s = usage.ru_utime.tv_sec
                + usage.ru_utime.tv_usec / 1000000.0;
            double sys_s = usage.ru_stime.tv_sec
                + usage.ru_stime.tv_usec / 1000000.0;
            double cpu_pct = ((user_s + sys_s) / elapsed) * 100.0;
            long rss_kb = usage.ru_maxrss;

            printf("[%.0fs] packets: %lu (%.0f pps) | "
                   "bw: %.2f mbps | "
                   "mix: %.1f us/pkt | "
                   "cpu: %.1f%% | "
                   "rss: %ld kb | "
                   "speakers: %lu/%lu/%lu\n",
                   elapsed, total_packets, pps,
                   mbps, avg_mix_us, cpu_pct, rss_kb,
                   packets_per_speaker[0],
                   packets_per_speaker[1],
                   packets_per_speaker[2]);

            /* Clear mix buffers periodically (simulate output consumed) */
            memset(mix_buf, 0, sizeof(mix_buf));
            last_report = now;
        }
    }

    double elapsed = (time_ms() - start_time) / 1000.0;
    struct rusage usage;
    getrusage(RUSAGE_SELF, &usage);
    double user_s = usage.ru_utime.tv_sec
        + usage.ru_utime.tv_usec / 1000000.0;
    double sys_s = usage.ru_stime.tv_sec
        + usage.ru_stime.tv_usec / 1000000.0;

    printf("\n=== final results ===\n");
    printf("duration:     %.1f s\n", elapsed);
    printf("packets:      %lu\n", total_packets);
    printf("avg rate:     %.0f pps\n", total_packets / elapsed);
    printf("bandwidth:    %.2f mbps\n",
           (total_bytes * 8.0) / (elapsed * 1000000.0));
    printf("avg mix time: %.1f us/packet\n",
           total_packets > 0
               ? mix_time_total_us / (double)total_packets : 0);
    printf("cpu user:     %.3f s\n", user_s);
    printf("cpu system:   %.3f s\n", sys_s);
    printf("cpu total:    %.1f%%\n",
           ((user_s + sys_s) / elapsed) * 100.0);
    printf("peak rss:     %ld kb\n", usage.ru_maxrss);

    close(fd);
    return 0;
}
