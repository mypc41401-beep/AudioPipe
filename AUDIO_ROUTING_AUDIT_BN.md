# AudioPipe 1.2: প্রকৃত routing audit ও ফোনে পরীক্ষার নির্দেশনা

এই কাজ `mypc41401-beep/AudioPipe` fork-এর `62ef091` থেকে চলেছে। Working Wireless
ADB pairing, saved-key reconnect এবং `Connected — ADB shell verified` পথ রাখা হয়েছে।
মূল upstream বদলানো হয়নি। এই সংস্করণ একটি **UID-policy পরীক্ষামূলক build**;
Redmi 11 Prime / Android 14 / X-906-এর acoustic isolation ডেভেলপার পরীক্ষা করেননি।

## পুরোনো routing কেন ব্যর্থ

- `PlayBackThread.start()` asynchronous `appops … PLAY_AUDIO deny` পাঠাত; command
  সফল হয়েছে কি না অপেক্ষা করত না। এটি source-এর মূল playback mute করার চেষ্টা,
  কোনো নির্দিষ্ট Bluetooth/speaker output-এ original track redirect নয়।
- `AudioPlaybackCaptureConfiguration` selected UID-এর capturable audio copy করত;
  `AudioPlayer` নতুন AudioTrack দিয়ে সেই copy replay করত। মূল track-কে সরাত না।
- অন্য app-গুলোর default media route speaker করার কোনো policy ছিল না। Bluetooth
  system default হলে Facebook/game-ও Bluetooth-এ যাওয়াই প্রত্যাশিত।
- `preferredDevice` ফল যাচাই বা actual `routedDevice`/routing callback ছিল না।
- UI service initialization-এর আগেই “Routing” দেখাত। Activity-র `selectedApp` ও
  `selectedOutput` নতুন instance-এ null থাকায় “Routing null → Default” আসত।
- static MediaProjection intent, `START_STICKY`, projection revocation callback না
  থাকা, AudioRecord read error/count উপেক্ষা, unsynchronized player map, incomplete
  release এবং crash-এর পরে PLAY_AUDIO deny থেকে যাওয়ার সমস্যা ছিল।
- Usage loop শুধু MEDIA/GAME/ALARM/NOTIFICATION দিত; capture-সমর্থিত UNKNOWN বাদ
  পড়ত। Alarm/notification arbitrary app capture-এর supported media usages নয়।
- Loop-cycle হিসাবকে latency বলা হয়েছিল; সেটি end-to-end audio latency মাপত না।
- পুরোনো audio unit tests শুধু resource/service creation পরীক্ষা করত; split audio নয়।

## Android 14-এ কী সম্ভব

**সাধারণ APK + সাধারণ runtime permissions দিয়ে অন্য অপরিবর্তিত app-এর original
tracks-এর output নিয়ন্ত্রণ করা যায় না।** MediaProjection অনুমতি এই ক্ষমতা দেয় না।
`setCommunicationDevice` calls/communication-এর জন্য; এই media split-এর বিকল্প নয়।
Shizuku Running থাকলেই APK privileged হয় না; বর্তমান AudioPipe নিজের ADB ব্যবহার করে।

তবে **root ছাড়া authenticated ADB shell** একটি আলাদা privileged identity। AOSP
Android 14-এর Shell manifest-এ `MODIFY_AUDIO_ROUTING` ও `QUERY_AUDIO_STATE` আছে।
সেই shell process-এর `AudioPolicy` / `AudioMix` RENDER rules selected UID এবং
other UID-দের original PCM media tracks-কে আলাদা available output-এ পাঠাতে পারে।
এটি capture/replay নয়। সাধারণ AudioPipe process-কে ওই privileged permissions
grant করা হয় না। Xiaomi firmware-এ এই API/permission/HAL support বাস্তবে পরীক্ষা
করতে হবে; AOSP source উপস্থিত থাকা device compatibility guarantee নয়।

সীমা:

- Policy registration সফল হওয়া speaker/Bluetooth hardware isolation-এর প্রমাণ নয়।
- Android 14 native source-এ explicit per-track device preference primary dynamic
  policy-এর চেয়ে অগ্রাধিকার পেতে পারে। BitChord-এ output থাকলে X-906 অথবা System
  default বেছে নাও; অন্য app-এ explicit Bluetooth preference থাকলে সরাও। এমন
  override দেখা গেলে app success দেখাবে না। প্রত্যেক arbitrary app-এর যেকোনো
  routing choice জোর করে ঠেকানো এই build-এর supported guarantee নয়।
- PCM-format RENDER mixes compressed/offloaded/exclusive output-এর সব পথ match
  নাও করতে পারে। Android audio policy/HAL একই output thread-এ দুই device চালাতে
  না পারলে বা OEM policy override করলে এই split ব্যর্থ হতে পারে।
- Multi-audio-focus `AUDIOFOCUS_GAIN`-এর concurrent media-তে সাহায্য করে। Call,
  assistant, transient focus এবং app-এর নিজস্ব pause আচরণ এখনও interruption ঘটাতে
  পারে। এই build calls/alarms/notifications redirect করে না।
- Xiaomi Sound Assistant-এর official বর্ণনা individual volume/multiple sources এবং
  call/notification speaker নিয়ে; Redmi 11 Prime-এ Samsung-এর মতো per-app output
  selector আছে—এমন নিশ্চিত official প্রমাণ পাওয়া যায়নি।
- User force-stop/process death/Bluetooth loss-এর পরে system default ফিরে আসে;
  বাইরের app-এর audio চলতে থাকলে সেই default-এ শোনা যেতে পারে। এটি fail-closed
  audio privacy barrier নয়। Disconnect-এর পরে reconnect করে আবার Start করতে হবে।

## নতুন active implementation

- `AudioRoutePolicy.java`: ADB shell process-এ real RENDER mixes—selected UID-এর
  MEDIA/GAME/UNKNOWN → selected Bluetooth; অন্য UID-এর একই usages → speaker।
- `RouteDaemon.java` ও `RouteSession.kt`: APK-এর helper-কে local ADB দিয়ে launch;
  loopback-only random endpoint, 256-bit authentication token, bounded messages,
  timeouts ও heartbeat। Arbitrary shell command দেওয়ার network interface নেই।
- Original playback UID এবং native audio-policy current-output/client dump মিলিয়ে actual device IDs query
  করা হয়। দুই app active না হলে শুধু **Policy accepted; split not yet observed**।
  Wrong/unknown device দীর্ঘস্থায়ী হলে error দিয়ে policy সরানো হয়।
- Accepted configuration-কে physical listening-test success বলা হয় না।
- UI service-owned immutable StateFlow পড়ে; active অবস্থায় selection lock থাকে।
  Selection save হয়, কিন্তু transient audio port ID পরের process-এ ধরে নেওয়া হয় না।
- User-started `specialUse` foreground service ও Stop notification। PCM worker,
  MediaProjection, AudioRecord ও replay AudioTrack active পথে নেই। RECORD_AUDIO ও
  mediaProjection service permission বাদ; specialUse FGS permission যোগ হয়েছে।
- পুরোনো file paths রাখা হয়েছে; unsafe capture backend একটি disabled historical stub। Capture
  volume/balance/EQ controls direct routing-এর controls হিসেবে দেখানো হয় না।
- Multi-focus আগের অবস্থায় restore করা হয়। Helper crash/kill হলে pending recovery
  ADB reconnect-এর পরে চলে। ফোন reboot/force-stop/ADB বন্ধ অবস্থায় অবিলম্বে recovery
  সবসময় সম্ভব নয়; diagnostics-এ cleanup failure জানানো হয়।
- “Restore selected app playback” শুধু selected app-এর PLAY_AUDIO=allow করে। পুরোনো
  capture build-এর deny থাকলে এটি ব্যবহার করো; native routing mute hack ব্যবহার করে না।

## Diagnostics

**Export diagnostics** চাপলে Android file picker দিয়ে `.txt` save করা যায়। কোনো
server-এ নিজে upload করে না। এতে output port ID/type/name, native original-player
routes, service/foreground state, capture-disabled status, last observation time,
state transitions/errors, শেষ recorded fatal error এবং CPU/heap snapshot থাকে।
ADB private key, pairing code, authentication token, MAC, Wi-Fi identity বা পুরো
installed-app list রাখা হয় না। Sharing-এর আগে report নিজে দেখে নাও। CPU/heap
snapshot battery benchmark বা দীর্ঘসময় stability test-এর বিকল্প নয়।

## Build এবং পরীক্ষার সীমা

GitHub Actions Java 17 / Gradle 8.9 / SDK 34 ব্যবহার করে APK, JVM tests ও lint চালায়।
API-34 secure emulator-এ আগের real adbd pairing/authentication test রাখা হয়েছে।
নতুন native policy test ordinary app-এর **original AudioTrack**-এর native route
observation, policy registration, UI background/recreation এবং cleanup/focus restoration
পরীক্ষা করে। Emulator HAL দ্বিতীয় output না দিলে অন্য device-এ সরানোর test skipped
থাকে; এটিকে successful split routing বলা যায় না।
এতে Bluetooth HAL, X-906, Redmi acoustic output বা দুই physical output-এর isolation
পরীক্ষা হয় না। CI result না দেখে planned test-কে passed ধরা যাবে না।

## শুধু ফোন দিয়ে install ও guided test

1. GitHub-এ sign in করে successful run-এর **AudioPipe-debug** artifact ZIP download
   করো। মোবাইলে Artifacts না দেখালে browser-এর Desktop site ব্যবহার করো। ZIP extract
   করে `app-debug.apk` install করো; Android চাইলে শুধু installer-এর unknown-app
   অনুমতি দাও। এটি debug APK; runner signing key বদলালে পুরোনো AudioPipe uninstall
   করতে হতে পারে। তাতে AudioPipe-এর saved pairing মুছবে; BitChord মুছতে হবে না।
2. Notifications অনুমতি দাও। Screen capture বা microphone অনুমতি লাগবে না। Xiaomi
   app battery setting-এ AudioPipe-এর জন্য No restrictions ব্যবহার করে পরীক্ষা করো।
3. Wireless debugging চালু করে প্রয়োজনে AudioPipe-কে নতুন করে Setup / Pair করো।
   Shizuku Running এই authorization-এর বিকল্প নয়। “ADB shell verified” নিশ্চিত করো।
4. X-906 connect করো এবং Bluetooth-এর **Media audio** চালু রাখো। BitChord-এর output
   preference থাকলে X-906/System default বেছে নাও; exclusive/offload/bit-perfect
   mode সমস্যা করলে standard PCM playback দিয়ে পরীক্ষা করো।
5. AudioPipe-তে `com.music.bitchord` ও X-906-এর **A2DP** output বেছে নাও। পুরোনো
   PLAY_AUDIO deny-এর error থাকলে Stop → Restore selected app playback → Start।
6. প্রথমে AudioPipe Start করো, **তারপর** BitChord play করো। Multi-focus enable-এর
   সময় আগের player pause হতে পারে; নিজে resume করো। Facebook/YouTube-তে অন্য চেনা
   audio চালাও। “Policy accepted” একা success নয়।
7. শুনে যাচাই করো: BitChord pause করলে Bluetooth থামবে, phone-এর দ্বিতীয় audio
   চলবে। দ্বিতীয় app pause করলে phone থামবে, Bluetooth music চলবে। দুটো চালালে
   speaker-দুটিতে যথাক্রমে শুধু নির্ধারিত app শোনা চাই। কোনো duplicate/leak নয়।
8. Home চাপো, ২ মিনিট অন্য app ব্যবহার করো, AudioPipe আবার খোলো। Status/selection
   বদলেছে কি না দেখো। পাঁচবার Start/Stop করো। Speaker volume কম থাকলে Android/
   Xiaomi-এর volume controls দেখে নাও; AudioPipe original PCM-এর gain বদলায় না।
9. Bluetooth disconnect করো: error/stop প্রত্যাশিত, মিথ্যা success নয়। Reconnect
   করে নতুন output নির্বাচন ও Start করো। Force-stop করলে routing চলার দাবি নেই।
10. Normal playback, failure, এবং background test-এর পরে আলাদা diagnostics export
    করো। কোন app-এ কোন শব্দ কোথায় শুনলে ও timestamp-সহ report দিলে পরের hardware
    diagnosis করা যাবে। Pairing code/key বা Wireless debugging screenshot দিতে হবে না।
11. কাজ শেষে Stop চাপো। Android default routing ফিরে আসবে; তারপর Wireless debugging
    বন্ধ করতে পারো।

## বাস্তব বিকল্প

এই ফোনের firmware/HAL split প্রত্যাখ্যান করলে শুধু UI fix করে সেটি চালানো যাবে না।
তখন supported vendor per-app-output feature (ফোনে সত্যিই থাকলে), compatible system/
root integration, অথবা অন্য source device থেকে Bluetooth-এ music চালিয়ে এই ফোনের
speaker অন্য app-এর জন্য রাখা বাস্তব বিকল্প। Root/custom ROM একটি আলাদা পরিবর্তন;
এই কাজ সেটি করেনি। BitChord-এর নিজের output selector একা বাকি app-গুলো speaker-এ
নেওয়া বা simultaneous focus নিশ্চিত করে না।

## Primary sources

- [Android playback capture](https://developer.android.com/media/platform/av-capture)
- [AudioTrack routing](https://developer.android.com/reference/android/media/AudioTrack)
- [AOSP Android 14 Shell permissions](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/packages/Shell/AndroidManifest.xml)
- [AOSP AudioPolicy API](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/media/java/android/media/audiopolicy/AudioPolicy.java)
- [AOSP AudioService permission checks](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/services/core/java/com/android/server/audio/AudioService.java)
- [AOSP native mix priority and matching](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp)
- [AOSP native policy/output validation](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/services/audiopolicy/managerdefault/AudioPolicyManager.cpp)
- [Android audio focus](https://developer.android.com/media/optimize/audio-focus)
- [Android 14 FGS types](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Xiaomi Sound Assistant](https://www.mi.com/uk/support/article/KA-08490/)

### Route observation-এর অতিরিক্ত সতর্কতা

`AudioPlaybackConfiguration.getAudioDeviceInfo()` Android-এর কিছু version-এ populated
হয় না; newer official docs-ও এ সীমা জানিয়েছে। তাই এটি success-এর ভিত্তি নয়। Helper
Android 14 native audio-policy dump-এর **current Devices + active AudioTrack clients**
পড়ে UID/session মিলিয়ে device port ID বের করে; Preferred Device field উপেক্ষা করে।
Raw dump-এ MAC থাকতে পারে, তাই সেটি memory-তেই parse হয়, report-এ পাঠানো হয় না।
Unsupported OEM dump format, ambiguous/multiple outputs বা unmapped active player
হলে unknown/error দেখায়। প্রায় ২.৫ সেকেন্ড অন্তর bounded observation হয়; এটি
তাৎক্ষণিক audio leak prevention বা physical acoustic proof নয়।

## Permission/security সীমিত পর্যালোচনা

বর্তমান manifest-এর চারটি Android permission: INTERNET, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE এবং POST_NOTIFICATIONS। AndroidX নিজের signature-only
receiver permission যোগ করে। Launcher intent queries দিয়ে app picker চলে;
QUERY_ALL_PACKAGES, ACCESS_NETWORK_STATE, MODIFY_AUDIO_SETTINGS ও RECORD_AUDIO নেই।
HTTP cleartext flag false, backup false। ADB/TLS ও authenticated loopback socket-এর
কাজ অপরিবর্তিত; routing helper shell-এর identity ব্যবহার করে, APK privileged নয়।
নতুন কোনো dependency বা analytics/upload endpoint যোগ হয়নি।

Lint-এর hidden-API, self-signed ADB TLS trust-manager, dependency-version ও UI/resource
warning-গুলো পূর্ণ security audit-এর বিকল্প নয়। ADB certificate trust সাধারণ web PKI-এর
মতো নয়; শুধু নিজের ফোনের trusted debugging endpoint ব্যবহার করা উচিত। এই পরিবর্তন
malware-free certification বা সব dependency-এর vulnerability scan নয়।
