#ifndef VOXSWAP_SETTINGS_SERVER_H
#define VOXSWAP_SETTINGS_SERVER_H

#include <stdint.h>

#include "stream-receiver.h"   /* LANG_CODE_MAX, Settings forward decl */

/**
 * Create settings with initial target languages (from CLI args).
 * @return allocated Settings, or NULL on failure
 */
Settings *settings_create(const char *target_lang1, const char *target_lang2);

/** Free settings. Safe to call with NULL. */
void settings_destroy(Settings *settings);

/**
 * Get current target languages. Copies into caller-provided buffers.
 * Buffers must be at least LANG_CODE_MAX bytes.
 */
void settings_get_languages(const Settings *settings, char *lang1, char *lang2);

/**
 * Set target languages. Validates inputs (non-empty, fits in buffer).
 *
 * @param speaker_id  Speaker attempting the change (must be admin)
 * @return 0 on success, -1 if not admin, -2 if invalid input
 */
int settings_set_languages(Settings *settings, uint8_t speaker_id,
                           const char *lang1, const char *lang2);

/**
 * Get the current admin speaker ID.
 * @return admin speaker_id (0-2), or -1 if no admin assigned
 */
int settings_get_admin(const Settings *settings);

/**
 * Assign admin to a speaker. Called when first speaker registers.
 * No-op if admin is already assigned.
 */
void settings_assign_admin(Settings *settings, uint8_t speaker_id);

/**
 * Called when a speaker disconnects. If the disconnected speaker was
 * admin, transfers admin to next_speaker_id (-1 if no speakers remain).
 */
void settings_on_speaker_disconnect(Settings *settings, uint8_t speaker_id,
                                    int next_speaker_id);

#endif
