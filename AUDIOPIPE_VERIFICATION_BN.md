# AudioPipe 1.4 — চূড়ান্ত APK, যাচাই ও ফোনের পরীক্ষা

তারিখ: ১৬ নভেম্বর ২০২৬ (বাংলাদেশ সময়)। পরিবর্তন শুধু `mypc41401-beep/AudioPipe` fork-এ;
মূল upstream পরিবর্তন বা public release প্রকাশ করা হয়নি। Multiple-app selection যোগ হয়নি।

**ADB reconnect এবং audio-policy stability-এর automated পরীক্ষা পাস করেছে। নিজের Redmi
11 Prime / Android 14 / BitChord / X-906-এ reconnect, acoustic isolation ও stutter এখনও
যাচাই হয়নি। সেই physical ফল ছাড়া মূল hardware requirement সম্পূর্ণ বলা যাবে না।**

## APK

- [সফল build ও Android 14 runtime run](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43758884245)
- **[AudioPipe-debug APK ZIP ডাউনলোড](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43758884245/artifacts/14179452747)**
- Source: [7de4755fb6930e3e3388eee281ebe32acc6cb5ca](https://github.com/mypc41401-beep/AudioPipe/commit/7de4755fb6930e3e3388eee281ebe32acc6cb5ca)।
  এরপরের documentation/verification commit APK-এর application source বদলায় না।
- Package `com.asdfg.soundmaster`; version `1.4-stability-diagnostics`, code 5।
- Min SDK 30; compile/target SDK 34; Java 17, Gradle 8.9, AGP 8.2, Kotlin 1.9.22।
- APK 30,921,912 bytes; ZIP প্রায় 13.2 MB। ARM64-v8a, ARMv7, x86 ও x86_64 অন্তর্ভুক্ত।
- SHA-256: `4e60a440283954f86fca23dbdf165f9588e3f090f2bcee22be7627bd3f40e5fa`।
- CI-তে APK v2 signature/package/ABI যাচাই হয়েছে। Download করা একই artifact-এর
  checksum আবার মিলেছে; পরীক্ষার fixture markers APK-এর DEX-এ পাওয়া যায়নি।
- একই delivered APK emulator-এ install হয়েছে; cold launch `Status: ok`, 1166 ms।
  এটি Redmi-র performance benchmark নয়। Crash log খালি ছিল।
- Artifact ১৪ দিন থাকে। মেয়াদ শেষ হলে Actions থেকে workflow আবার চালাতে হবে।

## পরীক্ষার ফল

| পরীক্ষা | নিশ্চিত ফল / সীমা |
|---|---|
| Gradle build | সফল |
| JVM unit tests | ৫১/৫১ পাস; ০ failure/error/skip |
| Android lint | ০ error, ৮৮ warning; warning-মুক্ত বা formal security audit নয় |
| Secure Android 14 instrumentation | ১৩টির মধ্যে ১২ পাস, ১ skipped, ০ failure/error |
| Real system adbd | SPAKE2/TLS pairing, UID 2000 shell এবং encrypted saved-key reconnect পাস |
| Wi-Fi restart | Fully connected Wi-Fi-তে user-enabled Wireless debugging ফিরলে NSD/current-port authentication পাস |
| Wireless debugging restart | Actual system listener বন্ধ/চালু হওয়ার পরে নতুন pairing code ছাড়া reconnect; actual system port-এর সঙ্গে cached port মিলেছে |
| Identity safety | Encrypted identity অপরিবর্তিত; unreadable key silently regenerate হয় না |
| Failure/retry logic | Stale port/socket, bounded backoff, discovery failure/manual fallback, cancellation, owner removal এবং failed command replay না করা পাস |
| Lifecycle/native resource | UI recreate/Home, invalid service starts, native policy cycles এবং bounded descriptors পাস |
| Main emulator-এর দ্বিতীয় endpoint | HAL-এ দ্বিতীয় output না থাকায় ১ explicit skip; virtual suite সেই দুই-output case-ও চালিয়েছে |
| Exact APK install/start | সফল; crash log ০ bytes |
| Physical Redmi / installed BitChord / X-906 | পরীক্ষা হয়নি |

Full JUnit XML, lint, manifest ও runtime logs Actions-এর সংশ্লিষ্ট report artifacts-এ আছে।
Machine-readable ফল: [7de4755-results.json](docs/verification/7de4755-results.json)।

### Software Bluetooth ও দুইটি original app UID

- [সফল virtual Bluetooth run](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43758884247)
- [JUnit / diagnostics / SBC / radio reports](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43758884247/artifacts/14179079245)
- **৩/৩ runtime test পাস; ০ failure/error/skip।** Pinned Google Bumble/Netsim A2DP sink;
  এটি X-906 বা physical radio নয়। Fixtures/dependencies ব্যবহারকারীর APK-তে নেই।
- Selected ordinary app UID original 660-Hz track চালিয়েছে; অন্য ordinary UID original
  440-Hz track চালিয়েছে। Source tracks preferred-device request করেনি; production
  shell RENDER policy এবং native current-output/client evidence ব্যবহার হয়েছে।
- Home-এ দুই মিনিট background playback, UI recreation, পাঁচটি routing cycle, manual
  Repair, rapid-repeat cooldown, Bluetooth loss/reconnect এবং background Stop পাস।
- Soak-এ policy generation 1 থেকেছে; automatic reinstall loop হয়নি। Native observation
  প্রায় ১৫ সেকেন্ড অন্তর আপডেট হয়েছে। Helper FD growth test পাস; snapshots-এ FD 51 ছিল।
- Repair-এ acknowledged cleanup-এর পরে generation 1→2 এবং দুই native UID-এর সঠিক
  output ফিরেছে। পরের তিনটি দ্রুত request generation আবার বাড়ায়নি। Repair-এর পরে
  Stop ও final background Stop-এ Android foreground service সত্যিই বন্ধ হয়েছে।
- Virtual sink **7,596,841 bytes SBC** পেয়েছে। Download করা SBC decode করে 125 সেকেন্ডের
  ধারাবাহিক interval-এ selected 660-Hz tone dominant পাওয়া গেছে; 440-Hz band amplitude
  selected band-এর অন্তত প্রায় 50 dB নিচে ছিল। [ফল](docs/verification/7de4755-sbc-analysis.json)
  এবং [পুনরুৎপাদনযোগ্য offline analyzer](docs/verification/analyze_sbc.py) আছে।
- এটি generated software signal-এর পরীক্ষা। Phone speaker waveform record করা হয়নি।
  Raw SBC-তে packet-arrival timing নেই; এই analysis দিয়ে zero leakage, acoustic isolation,
  latency বা stutter পুরো ঠিক হওয়ার দাবি করা যায় না। দুই মিনিট দীর্ঘমেয়াদি thermal/battery test নয়।

## কী সংশোধন হয়েছে

**ADB:** Cached port আর স্থায়ী পরিচয় নয়। Android 14 NSD continuous service-info callbacks
দিয়ে বর্তমান local connection port অনুসরণ হয়। Wi-Fi change/resume/routing foreground
service recovery জাগায়; healthy shell probe ৩০ সেকেন্ড অন্তর। ব্যর্থ হলে bounded
১/২/৪/৮/১৫ সেকেন্ড backoff, পরে event/manual recovery। App ও routing দুটোই বন্ধ হলে
network watch/timers বন্ধ থাকে। Failed/stopped NSD subscription পরের attempt-এ বদলায়।
Encrypted RSA identity-এর alias/format বজায় আছে; decrypt error-এ overwrite হয় না।
Failed mutating command নিজে replay হয় না। Healthy probe আর UI লাল করে Start আটকে দেয় না।

**Stability:** প্রতি ২.৫ সেকেন্ডে full native-policy dump-এর বদলে steady অবস্থায় ১৫
সেকেন্ড interval, bounded settling ও coalesced device events। ওই পুরোনো observer loop-ও
read-only ছিল; focus/policy-writing loop পাওয়া যায়নি। Stopped/repairing session-এর
পুরোনো evidence সরে; Last checked দেখায়। Cleanup-এর পরে task reference clear হয় এবং
পুরোনো service নতুন state overwrite করতে পারে না। Normal Stop cleanup শেষ হওয়া পর্যন্ত
foreground ownership রাখে। Native dump timeout-এ owned subprocess termination শক্ত করা হয়েছে।

**Repair:** আগে ADB যাচাই; ব্যর্থ হলে আগের policy রেখে postpone। সফল preflight-এর পরে
owned policy removal ACK পেলে একই UID/device identity দিয়ে একবার নতুন policy। ACK ছাড়া
overlap নয়; ৩০ সেকেন্ড cooldown। Source app, audioserver বা ফোন restart/অন্য app-এর buffer,
volume/focus reset করা হয় না। সাময়িক default output শোনা যেতে পারে। Physical stutter-এর
কারণ বা সমাধান নিশ্চিত হয়নি।

**Diagnostics:** Current outputs, actual original-player UID/session/native output, FGS,
ADB transitions, snapshot age/duration/count, policy generation/repair attempts এবং
CPU/heap/open-FD counters export হয়। Capture/MediaProjection/own replay AudioTrack নেই।
বিস্তারিত: [stability/Repair/ফোন guide](AUDIO_STABILITY_BN.md) এবং [ADB guide](ADB_PAIRING_BN.md)।

## Platform ও নিরাপত্তা

Normal APK permissions দিয়ে অন্য app-এর output জোর করে নিয়ন্ত্রণ করা যায় না। বর্তমান
পথ একটি **authorized ADB shell-এর privileged MODIFY_AUDIO_ROUTING / QUERY_AUDIO_STATE**
ক্ষমতা ব্যবহার করে; root বা system-signed APK ব্যবহার করা হয়নি। Native UID RENDER policy
original players-কে route করে; MediaProjection capture/replay বা AudioTrack preferred
device-কে redirect করার প্রমাণ বলা হয়নি। পূর্ণ [platform audit ও primary sources](AUDIO_ROUTING_AUDIT_BN.md) আছে।

Permissions: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS এবং AndroidX-এর signature receiver
permission। Microphone, MediaProjection, storage, location, Bluetooth scan ও
QUERY_ALL_PACKAGES permission নেই। ACCESS_NETWORK_STATE reconnect-এর Wi-Fi/NSD-র জন্য।

নতুন production dependency/analytics/upload endpoint যোগ হয়নি। Existing Bouncy Castle,
Conscrypt Android 2.9.0 ও SPAKE2 Android 2.2.1 আছে; dependency/security formal audit নয়।
SPAKE2 upstream নিজেকে unaudited বলে। JVM tests-এ শুধু Robolectric-compatible Linux
Conscrypt 2.5.2 থাকে; এটি APK-এর Android TLS provider downgrade নয়। Hidden API ও পুরোনো
dependencies/lint warnings সীমা হিসেবে রয়ে গেছে। Backup বন্ধ; key Android Keystore
AES-GCM-এ encrypted। Diagnostics private key/ciphertext, code, token, MAC বা পূর্ণ
installed-app list export/upload করে না।

### Signing ও একবার নতুন pairing লাগতে পারে

Final APK certificate SHA-256:
`89d58b550188f07c549993279f087787e6c5d868daa8815990c67530246e86bb`।
এটি আগের CI debug signer থেকে আলাদা। পুরোনো signing private key আমার কাছে নেই;
মিলিয়ে সরাসরি update/sign করা যায়নি। স্থায়ী private CI signing key ব্যবস্থা না হলে
ভবিষ্যৎ run-এও এই সীমা থাকতে পারে। কোনো private signing key public repo-তে রাখা হয়নি।

একই signer-এর update হলে app data/key থাকে। Signature mismatch হলে uninstall লাগতে
পারে; **uninstall app data ও Android Keystore identity মুছে দেয়**, তাই একবার নতুন pairing
লাগবে। Settings-এ পুরোনো paired entry থাকলেও নতুন install সেই মুছে যাওয়া private key
ব্যবহার করতে পারে না। এটি installation/migration সীমা; একই installed app-এ Wi-Fi toggle
করে saved identity পুনর্ব্যবহারের পরীক্ষার সঙ্গে এক নয়।

## Android ফোন থেকে install ও test

1. GitHub-এ sign in করে ওপরের APK artifact ZIP নামাও। Artifacts না দেখালে browser-এর
   Desktop site ব্যবহার করো। Xiaomi Files দিয়ে extract করে `app-debug.apk` install করো;
   installer চাইলে Install unknown apps অনুমতি দাও।
2. Update signature mismatch হলে আগে পুরোনো AudioPipe-তে **Stop** চাপো, শুধু AudioPipe
   uninstall করে নতুনটি install করো। BitChord মুছবে না। একবার নতুন AudioPipe pairing
   করতে হবে; Shizuku entry মুছতে হবে না। Notifications allow করো।
3. Wi-Fi-তে সম্পূর্ণ connected হয়ে Wireless debugging On করো। প্রথম pairing দরকার হলে
   Settings-এর Pair device with pairing code dialog split screen/floating window-তে খোলা
   রেখে AudioPipe → Setup / Pair-এ pairing port ও ছয় সংখ্যার code দাও। Pair ও connection
   port আলাদা। Manual fallback-এ মূল Wireless debugging page-এর current connection port
   দিয়ে Connect / verify shell করো। PC/Termux/private-key file লাগে না।
4. X-906 connect করে Media audio On রাখো; AudioPipe-তে BitChord
   `com.music.bitchord` এবং X-906 A2DP বেছে Start করো। Start simultaneous media চাইলে
   তখন ওই option save করে reboot করো; পরে Wireless debugging On করে ফিরে এসো।
   আগে enable/reboot হয়ে থাকলে অযথা আবার করবে না। Undo: Restore normal audio focus +
   reboot; Stop এই persistent global setting বদলায় না।
5. BitChord এবং game/YouTube/Facebook-এ আলাদা চেনা শব্দ একসঙ্গে চালাও। BitChord pause
   করলে শুধু X-906, অন্য app pause করলে শুধু phone speaker থামবে কি না শোনো। Duplicate/
   leakage চলবে না। Home/screen off-on ও ফিরে এসে selection/status পরীক্ষা করো।
6. Wireless debugging Off→On; তারপর Wi-Fi Off→On আলাদা করে test করো। Wi-Fi সম্পূর্ণ
   যুক্ত হলে Android নিজে Wireless debugging Off করে দিলে তুমি আবার On করবে। নতুন
   pairing code ছাড়া reconnect হওয়া পরীক্ষা করো। ADB reconnect আর audio session restore
   এক নয়; routing stopped হলে stable Bluetooth-এ আবার Start করতে হবে।
7. Stutter হলে **আগে diagnostics export**, একবার Repair, তারপর দ্বিতীয় report করো।
   পাঁচবার Stop/Start ও Bluetooth loss/reconnect পরীক্ষা করো। Xiaomi background restriction
   থাকলে পরীক্ষার সময়ে AudioPipe/BitChord → No restrictions রাখো।
8. Report-এর সঙ্গে ঘটনার সময়, app version, কোন শব্দ কোন output-এ শুনেছ ও Repair-এর
   আগে/পরে কী বদলেছে জানাও। Pairing code/private key পাঠিও না। বিস্তারিত
   [guided phone test](AUDIO_STABILITY_BN.md)-এ আছে।

## অবশিষ্ট সীমা

Redmi firmware/HAL support, installed BitChord, physical X-906 isolation, game stutter,
দীর্ঘসময় CPU/battery/thermal stability এবং বাস্তব Wi-Fi toggles এখনও unverified।
Explicit player output, compressed/offload/exclusive playback, voice chat/call focus বা
source app নিজে pause করলে সব app-এর সব শব্দে strict ONLY-output guarantee দেওয়া যায় না।
এই policy মূলত MEDIA/GAME/UNKNOWN usage-এর; calls/alarms/notifications-এর universal router নয়।

Accepted/last-observed status listening test নয়। Failure/force-stop/device loss-এ Android
default routing ফিরতে পারে; default Bluetooth হলে অন্য app-এর audio-ও সেখানে শোনা যেতে
পারে। এটি privacy barrier নয়। OEM shell routing নিষিদ্ধ করলে native vendor per-app output,
অন্য device দিয়ে Bluetooth music, অথবা পৃথক system/root integration বাস্তব বিকল্প;
Xiaomi Sound Assistant-এর volume option-কে per-app output support বলে ধরা হয়নি।

## প্রধান commits

- [173b9ef](https://github.com/mypc41401-beep/AudioPipe/commit/173b9ef275d6b6f795ca491f376eeb2119a54fc4): identity-safe ADB recovery, NSD/network lifecycle, bounded retries ও tests।
- [0b7f2fc](https://github.com/mypc41401-beep/AudioPipe/commit/0b7f2fc11b07ef816055d04b481a5d11e7304da8): real system listener/current-port assertions।
- [9f988b9](https://github.com/mypc41401-beep/AudioPipe/commit/9f988b9e1a7b020f2f911524799129a34eaaca77): bounded Repair, observer load, diagnostics/state এবং extended tests।
- [6418644](https://github.com/mypc41401-beep/AudioPipe/commit/64186442163f9a90fb9e5a13816df82b62bfe030): failed/stopped NSD recovery ও actual Wi-Fi-readiness test corrections।
- [7de4755](https://github.com/mypc41401-beep/AudioPipe/commit/7de4755fb6930e3e3388eee281ebe32acc6cb5ca): foreground ownership through background Stop cleanup।
