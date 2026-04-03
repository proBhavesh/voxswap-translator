#include "settings-server.h"

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct Settings {
    char target_lang1[LANG_CODE_MAX];
    char target_lang2[LANG_CODE_MAX];
    int admin_speaker_id;   /* -1 = no admin */
};

Settings *settings_create(const char *target_lang1, const char *target_lang2)
{
    if (!target_lang1 || !target_lang2) {
        return NULL;
    }

    Settings *s = calloc(1, sizeof(Settings));
    if (!s) {
        fprintf(stderr, "settings: calloc failed\n");
        return NULL;
    }

    strncpy(s->target_lang1, target_lang1, LANG_CODE_MAX - 1);
    s->target_lang1[LANG_CODE_MAX - 1] = '\0';
    strncpy(s->target_lang2, target_lang2, LANG_CODE_MAX - 1);
    s->target_lang2[LANG_CODE_MAX - 1] = '\0';

    s->admin_speaker_id = -1;

    printf("settings: created (target1=%s target2=%s)\n",
           s->target_lang1, s->target_lang2);
    return s;
}

void settings_destroy(Settings *settings)
{
    if (!settings) {
        return;
    }
    free(settings);
    printf("settings: destroyed\n");
}

void settings_get_languages(const Settings *settings, char *lang1, char *lang2)
{
    strncpy(lang1, settings->target_lang1, LANG_CODE_MAX - 1);
    lang1[LANG_CODE_MAX - 1] = '\0';
    strncpy(lang2, settings->target_lang2, LANG_CODE_MAX - 1);
    lang2[LANG_CODE_MAX - 1] = '\0';
}

int settings_set_languages(Settings *settings, uint8_t speaker_id,
                           const char *lang1, const char *lang2,
                           bool force)
{
    if (settings->admin_speaker_id < 0
        || (uint8_t)settings->admin_speaker_id != speaker_id) {
        return -1;
    }

    if (!force) {
        if (!lang1 || !lang2 || lang1[0] == '\0' || lang2[0] == '\0') {
            return -2;
        }
        if (strlen(lang1) >= LANG_CODE_MAX || strlen(lang2) >= LANG_CODE_MAX) {
            return -2;
        }
    }

    strncpy(settings->target_lang1, lang1 ? lang1 : "", LANG_CODE_MAX - 1);
    settings->target_lang1[LANG_CODE_MAX - 1] = '\0';
    strncpy(settings->target_lang2, lang2 ? lang2 : "", LANG_CODE_MAX - 1);
    settings->target_lang2[LANG_CODE_MAX - 1] = '\0';

    printf("settings: languages set to target1=%s target2=%s (by speaker %u)\n",
           settings->target_lang1, settings->target_lang2, (unsigned)speaker_id);
    return 0;
}

int settings_get_admin(const Settings *settings)
{
    return settings->admin_speaker_id;
}

void settings_assign_admin(Settings *settings, uint8_t speaker_id)
{
    if (settings->admin_speaker_id >= 0) {
        return;
    }
    settings->admin_speaker_id = (int)speaker_id;
    printf("settings: speaker %u is now admin\n", (unsigned)speaker_id);
}

void settings_on_speaker_disconnect(Settings *settings, uint8_t speaker_id,
                                    int next_speaker_id)
{
    if (settings->admin_speaker_id < 0
        || (uint8_t)settings->admin_speaker_id != speaker_id) {
        return;
    }

    settings->admin_speaker_id = next_speaker_id;

    if (next_speaker_id >= 0) {
        printf("settings: admin transferred from speaker %u to speaker %d\n",
               (unsigned)speaker_id, next_speaker_id);
    } else {
        printf("settings: admin speaker %u disconnected, no speakers remain\n",
               (unsigned)speaker_id);
    }
}
