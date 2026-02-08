#ifndef VOXSWAP_MAIN_C
#define VOXSWAP_MAIN_C

#include <stdio.h>
#include <stdlib.h>
#include <signal.h>

#include "wifi-hotspot.h"
#include "stream-receiver.h"
#include "audio-mixer.h"
#include "auracast-broadcast.h"
#include "settings-server.h"

static volatile int running = 1;

static void signal_handler(int sig)
{
    (void)sig;
    running = 0;
}

int main(void)
{
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    printf("VoxSwap Box starting...\n");

    /* TODO: Initialize components */
    /* wifi_hotspot_init(); */
    /* stream_receiver_init(); */
    /* audio_mixer_init(); */
    /* auracast_broadcast_init(); */
    /* settings_server_init(); */

    printf("VoxSwap Box ready.\n");

    while (running) {
        /* TODO: epoll event loop */
    }

    printf("VoxSwap Box shutting down.\n");

    /* TODO: Cleanup components */

    return EXIT_SUCCESS;
}

#endif
