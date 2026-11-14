package com.asdfg.soundmaster.audio

/**
 * Legacy capture/replay backend deliberately disabled. It muted PLAY_AUDIO, leaked capture/player
 * resources on failure and could not redirect original tracks. Keeping this file as a historical
 * pointer avoids retaining a RECORD_AUDIO-dependent implementation in the current APK.
 * Previous implementation: commit 62ef091, same path. Active backend: AudioRoutePolicy.java.
 */
@Deprecated("Capture/replay is not original-player routing; use the shell UID audio policy")
internal class PlayBackThread private constructor()
