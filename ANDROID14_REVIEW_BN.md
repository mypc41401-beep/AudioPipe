> **ঐতিহাসিক নোট:** নিচের report প্রথম 1.0 APK-এর। বর্তমান routing/diagnostics-এর জন্য [AudioPipe 1.2 audit](AUDIO_ROUTING_AUDIT_BN.md), pairing-এর জন্য [ADB নির্দেশনা](ADB_PAIRING_BN.md) পড়ো। পুরোনো “pairing নেই” বিবরণ বর্তমান সংস্করণে প্রযোজ্য নয়।

# AudioPipe: Android 14 build ও পরীক্ষার নির্দেশনা

এই পর্যালোচনার ভিত্তি `mypc41401-beep/AudioPipe` fork; মূল source commit
`451920853cce662bc0cd8b90a88a2bb5346c0799`। মূল repository পরিবর্তন করা হয়নি।

## যাচাইকৃত build ফলাফল

- প্রথম run-এই সম্পূর্ণ workflow সফল; কোনো build error বা source-code fix প্রয়োজন হয়নি।
- Build commit: `eeb960450abefb03083d642ad714bc3fc161fc6e`।
- [সফল Actions run](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43621467675)
- [APK ZIP download](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43621467675/artifacts/14124441735)
- Java 17, Gradle 8.9, AGP 8.2.0, Kotlin 1.9.22, Android SDK/build-tools 34 ব্যবহার হয়েছে।
- `assembleDebug`, `apksigner verify`, APK metadata check এবং existing ৩টি unit test পাস।
- Download করা APK-এর SHA-256 CI-তে তৈরি checksum-এর সঙ্গে মিলেছে:
  `cee2663d7f78f60b274dc6a5d4c0c7051da58a25eaa5ce49e829eba0671e08ce`।
- ZIP প্রায় ৮.৩ MB; APK প্রায় ১৯.৫ MB। কোনো native `.so` library নেই।
- Artifact expiry: ২৮ নভেম্বর ২০২৬, বাংলাদেশ সময় রাত ১১টার পরে।
- পরিবর্তিত/যোগ করা ফাইল: `.github/workflows/android-debug.yml` এবং এই report।
  Application source, dependencies ও manifest অপরিবর্তিত। কোনো release প্রকাশ করা হয়নি।
- Workflow standard `ubuntu-22.04` runner ব্যবহার করে; public repository-তে standard
  runner compute বিনামূল্যে। Artifact storage account-এর quota-এর অধীন।

## APK build কী প্রমাণ করে

APK compile হওয়া, signature যাচাই এবং JVM unit test পাস করা বাস্তব ফোনে
Bluetooth ও speaker একসঙ্গে কাজ করার প্রমাণ নয়। এই কাজের সময় কোনো বাস্তব
Xiaomi/Bluetooth ডিভাইসে পরীক্ষা করা হয়নি।

## কোডে যা আছে

- `MainActivity.kt`: একটি user-installed app ও একটি output নির্বাচন। System app
  তালিকা থেকে বাদ পড়ে। Bluetooth A2DP, built-in speaker, wired এবং USB headset
  তালিকায় আছে; Bluetooth LE audio/SCO আলাদাভাবে অন্তর্ভুক্ত নয়।
- `PlayBackThread.kt`: নির্বাচিত package-এর UID দিয়ে
  `AudioPlaybackCaptureConfiguration` বানিয়ে PCM capture; এরপর `AudioTrack` দিয়ে
  playback। এটি অন্য অ্যাপের মূল audio track সরাসরি অন্য ডিভাইসে স্থানান্তর করে না।
- `AudioPlayer.kt`: volume, stereo balance ও audio effects।
- `SoundMasterService.kt`: mediaProjection foreground service ও notification।
- `adb/`: নিজের ফোনের `127.0.0.1` ঠিকানায় ADB connection, RSA key import,
  Android Keystore দিয়ে encrypted storage, TLS ও mDNS discovery।
- Native pairing build বন্ধ; `adb_pairing.cpp` নেই। কোনো Shizuku API/provider
  dependency বা Shizuku permission integration নেই।
- আগে কোনো GitHub Actions workflow ছিল না।

## তোমার লক্ষ্য কতটুকু সমর্থিত

সম্ভাব্য পরীক্ষার ব্যবস্থা: **music app স্বাভাবিকভাবে Bluetooth-এ চলবে;
AudioPipe দ্বিতীয় app-টির audio capture করে built-in speaker-এ পাঠানোর চেষ্টা করবে।**
দ্বিতীয় app-টিই AudioPipe-তে নির্বাচন করতে হবে।

তবে এই ফল নিশ্চিত নয়:

1. উৎস app-টিকে playback capture অনুমোদন করতে হয়। Android সাধারণ third-party
   capture-এ MEDIA, GAME ও UNKNOWN usage অনুমোদন করে; call/voice communication,
   alarm/notification এবং navigation usage সর্বজনীনভাবে capture করা যায় না।
   কোডে alarm/notification matching যোগ করলেই Android-এর নিষেধাজ্ঞা কাটে না।
   বর্তমান matching list-এ UNKNOWN usage কার্যত যোগ হয় না।
2. `preferredDevice` একটি অনুরোধ; আসলে কোন ডিভাইসে audio গেছে তা কোড
   `routedDevice` দিয়ে যাচাই করে না। Xiaomi-এর audio policy Bluetooth ও speaker-এর
   simultaneous routing মেনে নাও নিতে পারে।
3. `PLAY_AUDIO deny` দিয়ে উৎসের স্বাভাবিক playback বন্ধ করার চেষ্টা আছে। এতে
   capture চলবে অথচ আসল output বন্ধ থাকবে—এটি এই Xiaomi ফোনে যাচাই করা হয়নি।
4. দুটি app-এর audio focus আচরণে একটি অন্যটিকে pause/duck করতে পারে। AudioPipe
   সেটি সর্বজনীনভাবে ঠেকায় না।
5. Capture buffer ও Bluetooth-এর কারণে delay/glitch হতে পারে। Notification-এর
   latency সংখ্যা loop rate-এর হিসাব; end-to-end latency measurement নয়।
6. README-এর Android 10+ দাবির বিপরীতে `minSdk` 30: এই APK-এর ন্যূনতম Android 11।
7. README-এর “যেকোনো app”, “system routing bypass” এবং Spotify/Android Auto
   উদাহরণকে এই build-এর জন্য যাচাইকৃত compatibility দাবি ধরা যাবে না।

## ADB setup-এর প্রকৃত বাধা

এই সংস্করণে pairing code নেওয়ার কার্যকর UI/backend নেই। `Setup` বোতাম আসলে
connection পুনরায় চেষ্টা করে। `Import Existing Key` দিয়ে **একই ফোনে আগে থেকেই
অনুমোদিত ADB private key** import করতে হয়। যেকোনো নতুন key import করলেই wireless
debugging-এ অনুমতি পাওয়া যায় না।

Shizuku চালু থাকলে তার আলাদা private key স্বয়ংক্রিয়ভাবে এই app-এ আসে না। সাধারণ
অবস্থায় Shizuku-এর private storage AudioPipe পড়তে পারে না। শুধু Shizuku install ও
start করার নির্দেশনা এই সমস্যার সম্পূর্ণ সমাধান নয়।

শুধু ফোন ব্যবহারকারী যার কাছে অনুমোদিত exportable key নেই, তার জন্য এটি বাস্তব
setup blocker। Native pairing অথবা যথাযথ Shizuku integration যোগ করা আলাদা source
কাজ; build workflow সেই feature যোগ করে না। কোনো private key chat-এ বা repository-তে
upload করো না।

## ফোন দিয়ে download ও install

1. নিজের GitHub account-এ browser দিয়ে sign in করো।
2. এই fork-এর **Actions → Build debug APK → সফল run → Artifacts → AudioPipe-debug**
   খুলে ZIP নামাও। মোবাইল পেজে না দেখালে browser-এর Desktop site চালু করো।
3. ফোনের File Manager দিয়ে ZIP extract করে `app-debug.apk` খুলবে।
4. Android চাইলে সেই browser/File Manager-এর **Install unknown apps → Allow from
   this source** অস্থায়ীভাবে চালু করে install করো; পরে বন্ধ করো।
5. এটি debug APK; নতুন runner-এর পরের build-এ signing key বদলাতে পারে। তখন update
   install ব্যর্থ হলে পুরোনো test app uninstall করতে হতে পারে; app-এর saved setup
   হারাবে।
6. Artifact ১৪ দিন রাখা হয়। পরে Actions থেকে **Run workflow** দিয়ে নতুন build
   করা যাবে। APK ZIP-এ SHA-256 ফাইলও থাকে।

## Permissions ও পরীক্ষার ক্রম

প্রথমে Bluetooth device pair/connect করে তার **Media audio** চালু রাখো। এরপর:

1. AudioPipe-কে **Microphone / Record audio** অনুমতি দাও; এই API permission-ই
   internal playback capture-এর জন্যও প্রয়োজন। Notification অনুমতি দিলে service
   status দেখা যায়। Source-এ microphone input capture-এর code পাইনি।
2. Test চলার সময় প্রয়োজন হলে Xiaomi-এর app battery setting-এ AudioPipe-কে
   unrestricted/No restrictions দাও। ফোনের menu নাম MIUI/HyperOS version অনুযায়ী
   আলাদা হতে পারে।
3. Developer options-এর Wireless debugging চালু রাখো, এবং ওপরের ADB শর্ত পূরণ
   করে app-এ **Connected** নিশ্চিত করো। Connected না হলে পরের ধাপে যেয়ো না।
4. Bluetooth-এ music app চালাও। AudioPipe-তে **দ্বিতীয় app** নির্বাচন করে output
   হিসেবে **built-in speaker** নির্বাচন করো; শুধু device product name দেখা যেতে পারে।
5. **Start** চাপলে Android-এর screen/audio capture consent দাও। Internal audio
   capture-এর জন্য Android 14-এ whole-screen sharing প্রয়োজন হতে পারে।
6. দুটি app-এ আলাদা চেনা audio চালিয়ে পরীক্ষা করো। একবার একটি app pause করে
   Bluetooth ও speaker-এ কোনটি শোনা যায় তা আলাদা করে মিলিয়ে দেখো। শুধু
   “Routing” লেখা বা green ADB status-কে সফল audio routing ধরে নিয়ো না।
7. কাজ শেষে AudioPipe-তে **Stop** চাপো। Wireless debugging বন্ধ করতে পারো।

সফলতার শর্ত: একই সময়ে Bluetooth-এ শুধু music app এবং ফোনের speaker-এ শুধু
দ্বিতীয় app শোনা যাবে; duplicate, silence বা forced pause হবে না।

## Build log-এর সতর্কতা

Build-এ deprecated Android audio/mDNS API এবং Node 20-ভিত্তিক CI actions-এর
warning আছে; GitHub runner সেগুলো Node 24-এ চালিয়েছে। setup-java v4 deprecated
বলার warning-ও আছে। এই run-এ এগুলো failure ঘটায়নি।

## Security review-এর পর্যবেক্ষণ

এটি source-level obvious-risk review; malware-free certification বা সম্পূর্ণ
dependency vulnerability audit নয়।

| বিষয় | যা পাওয়া গেছে |
|---|---|
| App network code | Loopback ADB socket ও local mDNS; বিজ্ঞাপন/analytics/upload server code পাইনি |
| Dependencies | Google Maven ও Maven Central থেকে AndroidX, Material, Kotlin coroutines, Bouncy Castle; অজানা binary SDK নেই |
| `INTERNET` | ADB socket-এর জন্য ব্যবহৃত; permission নিজে শুধু localhost-এ সীমাবদ্ধ নয় |
| `ACCESS_NETWORK_STATE` | Source-এ সরাসরি ব্যবহার পাওয়া যায়নি; সম্ভবত অপ্রয়োজনীয় |
| `QUERY_ALL_PACKAGES` | App picker-এর জন্য; installed-app তালিকা দেখার বিস্তৃত অনুমতি |
| `MODIFY_AUDIO_SETTINGS` | Audio routing/settings control-এর সঙ্গে সম্পর্কিত |
| Foreground service permissions | চলমান capture service ও Android 14 mediaProjection type-এর জন্য |
| `RECORD_AUDIO`, `POST_NOTIFICATIONS` | Capture runtime permission ও notification display |
| Merged manifest | ওপরের ৮টি Android permission-এর সঙ্গে AndroidX-এর signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` আছে |
| `allowBackup=true` | Encrypted ADB settings আছে; secret-bearing app-এর backup policy আরও শক্ত করা উচিত |
| `usesCleartextTraffic=true` | বিস্তৃত network policy; raw ADB/TLS socket-এর জন্য এই flag প্রয়োজন বলে প্রমাণ পাইনি |
| TLS trust manager | Server certificate verification ফাঁকা; connection code localhost ব্যবহার করে, তবু এটি security weakness |
| ADB authority | Key ও shell access অত্যন্ত সংবেদনশীল; শুধু নিজের trusted dedicated key ব্যবহার করা উচিত |
| AppOps cleanup | Crash/force-stop বা ADB disconnect-এ নির্বাচিত app-এর PLAY_AUDIO deny থেকে যাওয়ার ঝুঁকি আছে |
| Crypto version | Bouncy Castle 1.77 পুরোনো; upstream পরের version-এ security fixes দিয়েছে। এই code path-এ exploitability প্রমাণ করা হয়নি |

কোনো source app স্থায়ীভাবে silent হয়ে গেলে, app এখনও ADB-connected থাকলে AudioPipe
স্বাভাবিকভাবে Start/Stop করে restore চেষ্টা করা যায়। প্রয়োজন হলে নিজের অনুমোদিত
ADB shell-এ `appops set PACKAGE_NAME PLAY_AUDIO allow` চালিয়ে পরীক্ষা করো;
`PACKAGE_NAME` হবে আক্রান্ত app-এর সঠিক package। এই build-এর cleanup আগের custom
AppOps mode সংরক্ষণ করে না; `allow` সেট করে।

আরও runtime সীমাবদ্ধতা: MediaProjection revocation callback নেই; static token ও
START_STICKY restart নির্ভরযোগ্য নয়; ADB commands একটি socket-এ serialize করা হয় না;
capture initialization failure-এর সব পথে cleanup নেই। এগুলো build-success দিয়ে
সংশোধিত বা যাচাইকৃত হয় না।

## যাচাইয়ের primary sources

- [Android playback capture requirements](https://developer.android.com/media/platform/av-capture)
- [AudioPlaybackCaptureConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration)
- [AudioTrack routing API](https://developer.android.com/reference/android/media/AudioTrack)
- [Android 14 foreground service requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [AGP 8.2 build compatibility](https://developer.android.com/build/releases/agp-8-2-0-release-notes)
- [GitHub public runner billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)
- [GitHub artifact download](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)
- [Bouncy Castle 1.78 security fixes](https://www.bouncycastle.org/resources/java-release-1-78-1-is-now-available-for-download/)
