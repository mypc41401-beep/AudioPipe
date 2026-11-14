package com.asdfg.soundmaster.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.IBinder;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Actual framework RENDER policies, executed ONLY by a separately launched ADB shell process.
 * No AudioRecord, AudioTrack, MediaProjection, AppOps muting or preferred-device replay.
 * Hidden API definitions verified against AOSP android-14.0.0_r1. OEM support is probed at runtime.
 */
public final class AudioRoutePolicy implements AutoCloseable {
    private final Object service;
    private Object policy;
    private final int selectedUid;
    private final AudioDeviceInfo selectedDevice;
    private final AudioDeviceInfo speaker;
    private Boolean originalMultiFocus;

    public AudioRoutePolicy(int uid, AudioDeviceInfo selected, AudioDeviceInfo builtIn) throws Exception {
        if (Process.myUid() != 2000 && Process.myUid() != 0) {
            throw new SecurityException("Audio policy helper must run as the authorized ADB shell");
        }
        selectedUid = uid;
        selectedDevice = selected;
        speaker = builtIn;
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "audio");
        service = Class.forName("android.media.IAudioService$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    public static AudioDeviceInfo[] outputs() throws Exception {
        return (AudioDeviceInfo[]) AudioManager.class.getMethod("getDevicesStatic", int.class)
                .invoke(null, AudioManager.GET_DEVICES_OUTPUTS);
    }

    public static boolean isMediaUsage(int usage) {
        return usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
                || usage == AudioAttributes.USAGE_UNKNOWN;
    }

    public void install(boolean simultaneousMedia, boolean originalFocus) throws Exception {
        Class<?> builderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object builder = builderClass.getConstructor(Context.class).newInstance((Object) null);
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Method addMix = builderClass.getMethod("addMix", mixClass);
        // Disjoint UID rules: original BitChord tracks -> selected device; every other media UID
        // -> speaker. New apps are covered without enumerating a finite list of installed UIDs.
        addMix.invoke(builder, makeMix(selectedDevice, false));
        addMix.invoke(builder, makeMix(speaker, true));
        Object candidate = builderClass.getMethod("build").invoke(builder);
        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        int result = (Integer) AudioManager.class.getMethod("registerAudioPolicyStatic", policyClass)
                .invoke(null, candidate);
        if (result != 0) {
            throw new IllegalStateException("Android rejected UID audio policy (status " + result
                    + "). Shell permission or this ROM's Bluetooth/speaker outputs are unsupported.");
        }
        policy = candidate;
        if (simultaneousMedia && !originalFocus) {
            // Android's hidden multi-focus handles AUDIOFOCUS_GAIN. Transient focus/calls can
            // still interrupt media; this is not a promise to force every third-party app to play.
            originalMultiFocus = false;
            setMultiFocus(true);
        }
    }

    private Object makeMix(AudioDeviceInfo device, boolean excludeSelected) throws Exception {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> rb = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object rule = rb.getConstructor().newInstance();
        Method add = rb.getMethod("addMixRule", int.class, Object.class);
        // RULE_MATCH_UID = 4; RULE_EXCLUDE_UID = 0x8004, as defined in the API-34 framework.
        add.invoke(rule, excludeSelected ? 0x8004 : 4, Integer.valueOf(selectedUid));
        for (int usage : new int[]{AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN}) {
            add.invoke(rule, 1 /* RULE_MATCH_ATTRIBUTE_USAGE */,
                    new AudioAttributes.Builder().setUsage(usage).build());
        }
        Object builtRule = rb.getMethod("build").invoke(rule);
        Class<?> mb = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mix = mb.getConstructor(ruleClass).newInstance(builtRule);
        mb.getMethod("setFormat", AudioFormat.class).invoke(mix, new AudioFormat.Builder()
                .setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build());
        mb.getMethod("setRouteFlags", int.class).invoke(mix, 1 /* ROUTE_FLAG_RENDER */);
        mb.getMethod("setDevice", AudioDeviceInfo.class).invoke(mix, device);
        return mb.getMethod("build").invoke(mix);
    }

    public void setMultiFocus(boolean enabled) throws Exception {
        service.getClass().getMethod("setMultiAudioFocusEnabled", boolean.class).invoke(service, enabled);
    }

    public JSONObject observe() throws Exception {
        // QUERY_AUDIO_STATE is held by AOSP shell. Ordinary APK callers receive anonymized UIDs;
        // never interpret that redacted data as evidence of successful per-app routing.
        @SuppressWarnings("unchecked") List<AudioPlaybackConfiguration> players =
                (List<AudioPlaybackConfiguration>) service.getClass()
                        .getMethod("getActivePlaybackConfigurations").invoke(service);
        Method uidMethod = AudioPlaybackConfiguration.class.getMethod("getClientUid");
        Method activeMethod = AudioPlaybackConfiguration.class.getMethod("isActive");
        JSONArray observations = new JSONArray();
        for (AudioPlaybackConfiguration player : players) {
            if (!(Boolean) activeMethod.invoke(player)
                    || !isMediaUsage(player.getAudioAttributes().getUsage())) continue;
            int uid = (Integer) uidMethod.invoke(player);
            if (uid < 0) throw new SecurityException("Shell playback UIDs are redacted; cannot verify routing");
            AudioDeviceInfo actual = player.getAudioDeviceInfo();
            int expected = uid == selectedUid ? selectedDevice.getId() : speaker.getId();
            observations.put(new JSONObject().put("uid", uid)
                    .put("expected", expected).put("actual", actual == null ? -1 : actual.getId()));
        }
        boolean selectedAvailable = false, speakerAvailable = false;
        for (AudioDeviceInfo d : outputs()) {
            selectedAvailable |= d.getId() == selectedDevice.getId();
            speakerAvailable |= d.getId() == speaker.getId();
        }
        return new JSONObject().put("type", "status").put("players", observations)
                .put("devicesAvailable", selectedAvailable && speakerAvailable)
                .put("selectedDevice", selectedDevice.getId()).put("speakerDevice", speaker.getId());
    }

    @Override public void close() throws Exception {
        try {
            if (policy != null) {
                Object old = policy;
                policy = null;
                AudioManager.class.getMethod("unregisterAudioPolicyAsyncStatic",
                        Class.forName("android.media.audiopolicy.AudioPolicy")).invoke(null, old);
            }
        } finally {
            if (originalMultiFocus != null) {
                setMultiFocus(originalMultiFocus);
                originalMultiFocus = null;
            }
        }
    }
}
