# AudioPipe 1.2 — যাচাই ও APK হস্তান্তর

তারিখ: ১৫ নভেম্বর ২০২৬ (বাংলাদেশ সময়)। কাজ শুধু `mypc41401-beep/AudioPipe` fork-এ হয়েছে;
মূল upstream বা public release বদলানো/প্রকাশ করা হয়নি।

**Redmi 11 Prime + Android 14 + BitChord + X-906-এর physical listening test হয়নি।**
সেই পরীক্ষা না হওয়া পর্যন্ত প্রকল্পটির প্রধান hardware requirement সম্পূর্ণ হয়েছে বলা যাবে না।

## ডাউনলোডযোগ্য build

- [সফল build ও Android 14 verification run](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43662866035)
- [AudioPipe-debug APK artifact ZIP](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43662866035/artifacts/14140219106)
- Build source: `dede1313c458ff8be1546430b1c24f4df87ee2da`। পরের virtual-Bluetooth
  commits শুধু CI/test fixtures-এর; এই APK-র application source বদলায়নি।
- Package: `com.asdfg.soundmaster`; version `1.2-uid-routing-test`, code 3।
- SDK: min 30, target/compile 34। Java 17, Gradle 8.9, AGP 8.2, Kotlin 1.9.22।
- APK: 30,838,800 bytes; ARM64-v8a, ARMv7, x86 ও x86_64। ZIP প্রায় 13.2 MB।
- SHA-256: `f879a21e33acd1745c7a972ce82b629dc10d2beed3b71424a1ffb9c918138668`।
- APK v2 signature verification সফল। Download করে hash আবার CI checksum-এর সঙ্গে মিলেছে।
- Downloaded একই artifact Android 14 emulator-এ install হয়েছে; `am start -W`-এ
  `Status: ok`, cold launch 1677 ms এসেছে। Crash log খালি ছিল। এটি device performance benchmark নয়।
- Artifact retention ১৪ দিন। মেয়াদ শেষ হলে GitHub Actions থেকে workflow আবার চালাতে হবে।

## নিশ্চিত ফলাফল

| পরীক্ষা | ফল ও সীমা |
|---|---|
| Gradle assembleDebug | সফল |
| JVM tests | ৩৩/৩৩ পাস, ০ failure/error/skip |
| Android lint | ০ error, ৮৮ warning; warning-মুক্ত বা formal security audit নয় |
| Secure Android 14 instrumentation | ১০টির মধ্যে ৯ পাস, ১ skipped, ০ failure/error |
| Real system adbd | SPAKE2/TLS pairing, shell UID 2000, saved-key reconnect পাস |
| Production routing helper | Token authentication, missing-Bluetooth error ও cleanup ACK পাস |
| Native policy/observer | Original ordinary-app track-এর UID/current device observation পাস |
| Native lifecycle/resource | ৩ start/stop cycle, Home/recreation ও ২৫ observer probe-এ bounded FD count পাস |
| Main emulator second-device case | HAL-এ দ্বিতীয় endpoint না থাকায় explicit skip; split success নয় |
| Exact delivered APK | Signature, package, ABI, checksum, emulator install ও cold launch সফল |
| Physical Redmi / X-906 / installed BitChord | পরীক্ষা হয়নি; নিচের phone test দরকার |

JUnit XML, merged manifest, merger report এবং lint output main run-এর report artifacts-এ আছে।
Main suite-এর skipped test-কে passed বলা হয়নি।

## অতিরিক্ত virtual Bluetooth যাচাই

- [সম্পূর্ণ সফল virtual Bluetooth run](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43678531563)
- [JUnit, diagnostic, radio ও SBC report artifact](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43678531563/artifacts/14147916250)
- Test source: `24766da0d5e0d7d32f7c5a6e5d5f40e505b284e3`; application source
  delivered APK-র `dede131`-এর একই। পরের commit শুধু Settings test automation ঠিক করেছে।
- Google Bumble `34def7f112f970cf1cb46233b8b71d6af1ba9f81` ও emulator Netsim দিয়ে
  **AudioPipe CI Speaker** তৈরি হয়েছে। এটি X-906 বা physical radio নয়।
- **৩/৩ runtime test পাস; ০ failure/error/skip।** দুই native-policy case এবং দুই
  ordinary app UID নিয়ে production service/UI test অন্তর্ভুক্ত।
- Selected source একটি ordinary media-foreground-service app-এর original 660-Hz track;
  অন্য UID একটি original 440-Hz track চালিয়েছে। দুই source-এ preferred-device request
  ছাড়াই native active UID/session/current output মিলেছে। কোনো capture/replay ব্যবহার হয়নি।
- UI app picker ও Start/Stop, দুইবার Start, Home-এ ১০ সেকেন্ড fresh observation, UI
  recreation, acknowledged Stop ও দুই original track-এর default output-এ ফেরা পাস।
- Bluetooth disable-এ service সত্যিকার stopped/error state দেখিয়েছে; enable/reconnect-এর
  পরে UI Start আবার সফল হয়েছে। লগে **requested port 42 → actual live port 49** দেখা গেছে:
  codec negotiation port বদলালেও একই device identity resolve করে actual port state-এ এসেছে।
- Radio artifact-এ **2,251,123 bytes SBC audio** আছে। এটি software A2DP transport-এর
  প্রমাণ; Redmi speaker বা X-906-এর acoustic recording নয়।
- Native tests-এ প্রতি case-এ ৩টি policy cycle ও ২৫টি observer probe চালানো হয়েছে;
  helper FD growth নির্ধারিত সীমার মধ্যে ছিল। User-facing diagnostic snapshots-এ FD 51 দেখা গেছে।
- Test companion নিজের স্বাভাবিক media foreground service ব্যবহার করে। Android freezer
  globally disable করা হয়নি। Source app cached/frozen হলে route-change callback আটকে যেতে
  পারে—এটি বাস্তব background-contract সীমা। প্রথম minimal fixture-তে logcat-এ সেটি ধরা
  পড়েছিল; proper foreground player fixture দিয়ে উপরের পরীক্ষা সফল হয়েছে।
- Fixtures ও Bumble dependencies ব্যবহারকারীর APK-তে নেই। Final APK-র DEX-এ routing/
  ADB instrumentation fixture markers ও fixture private-key marker অনুপস্থিত যাচাই হয়েছে।

## প্রধান সংশোধন

- পুরোনো MediaProjection/AudioPlaybackCapture copy/replay ও asynchronous PLAY_AUDIO-deny
  routing path নিষ্ক্রিয়। সেটি source output redirect করত না, অন্য apps speaker-এ নিত না।
- Rootless authenticated ADB **shell identity**-তে native UID RENDER mixes যোগ হয়েছে:
  selected UID → Bluetooth, অন্য UID-র MEDIA/GAME/UNKNOWN → built-in speaker।
- Normal APK privileged নয়। Shell-এর `MODIFY_AUDIO_ROUTING` ও `QUERY_AUDIO_STATE`
  দরকার; firmware/HAL compatible না হলে error হয়। এটি public Android API-only solution নয়।
- Actual native current output + original UID/session observation ছাড়া split-observed status
  আসে না। Persistent wrong/unknown route হলে policy বন্ধ হয়; accepted policy-কে listening
  success বলা হয় না।
- Foreground service owns state/control; `Routing null → Default`, stale static flags,
  phantom sticky restart, UI selection reset এবং unsafe old capture-resource path সরেছে।
- Real virtual-Bluetooth test-এ UID exclusion rule-এর masked value সাধারণ add API-তে
  দেওয়ার bug পাওয়া ও সংশোধন হয়েছে; সঠিক excludeMixRule API এবং দুই Android-14 framework
  regression test এখন আছে।
- Reconnect/codec negotiation-এ পুরোনো port ID ধরে রাখার race ঠিক হয়েছে। Helper bounded
  সময় device type/address অথবা unique type/name মিলিয়ে live port resolve করে; recycled
  ID বা ambiguous identity-তে অন্য device বেছে নেয় না। UI/service actual resolved port পায়।
  পাঁচটি identity regression test-ও যোগ হয়েছে।
- Helper timeout/error, bounded messages, local authenticated control, acknowledged cleanup,
  Bluetooth loss detection, local diagnostics ও CPU/heap/FD snapshots আছে।
- Original ADB pairing/TLS/saved-key flow অক্ষত। ADB test fixture-এ শুধু adbd-এর asynchronous
  key reload-এর নির্দিষ্ট readiness error-এ bounded retry যোগ হয়েছে।
- Android 14 live multi-focus setter-এ বাস্তব system-server identity exception পাওয়া গেছে।
  তাই UI persistent setting save করে এবং reboot চায়; helper actual runtime flag যাচাই করে।

## নিরাপত্তা/অনুমতি

APK-এর Android permissions শুধু INTERNET, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE এবং POST_NOTIFICATIONS; AndroidX-এর নিজস্ব signature
receiver permission-ও আছে। Microphone, screen capture, storage, phone-state,
QUERY_ALL_PACKAGES বা Bluetooth scan permission চায় না। Backup বন্ধ।

Routing-এর জন্য নতুন APK dependency/analytics/upload endpoint যোগ হয়নি। Diagnostics
নিজে upload হয় না; ADB private key/code/token, MAC বা পুরো installed-app list export হয় না।
পূর্বের ADB crypto dependencies আছে; SPAKE2 upstream নিজেকে unaudited বলে। Hidden-API,
self-signed ADB TLS trust manager, পুরোনো dependency ও UI/resource-সংক্রান্ত lint warnings
রয়ে গেছে। এটি malware-free certificate বা সম্পূর্ণ dependency vulnerability audit নয়।
শুধু নিজের ফোনের trusted Wireless debugging ব্যবহার করো।

## ফোনে install, pair ও পরীক্ষা

1. GitHub-এ নিজের account-এ sign in করে ওপরের APK artifact ZIP download করো। মোবাইলে
   Artifacts দেখা না গেলে browser-এর Desktop site চালু করো। Xiaomi Files দিয়ে extract
   করে `app-debug.apk` install করো; চাইলে installer-এর Install unknown apps অনুমতি দাও।
2. পুরোনো debug APK-র signature আলাদা হলে আগে AudioPipe-তে Stop চাপো, শুধু পুরোনো
   AudioPipe uninstall করে নতুনটি install করো। Saved pairing মুছবে; BitChord মুছবে না।
   Notifications allow করো; microphone/capture permission লাগবে না। Xiaomi battery
   settings-এ পরীক্ষার সময় AudioPipe ও BitChord → No restrictions রাখো।
3. Wi-Fi ও Developer options → Wireless debugging চালু করো। প্রয়োজন হলে split screen/
   floating window-তে Settings-এর Pair device with pairing code dialog খোলা রেখে
   AudioPipe → Setup / Pair-এ **pairing port + ছয় সংখ্যার code** দিয়ে pair করো।
   তারপর মূল Wireless debugging page-এর আলাদা **connection port** দিয়ে Connect / verify
   shell করো। `Connected — ADB shell verified` চাই। Shizuku authorization-এর বিকল্প নয়।
4. **Enable simultaneous media (then reboot)** চাপো, ফোন reboot করো। এটি persistent
   **global** media-focus setting। Wireless debugging আবার চালু করে AudioPipe reconnect
   করো; port বদলাতে পারে। Undo করতে **Restore normal audio focus (then reboot)** চাপবে।
   Stop routing policy সরায়; এই saved focus option বদলায় না।
5. X-906 connect করো, Bluetooth-এর Media audio চালু রাখো। BitChord-এ output preference
   থাকলে X-906/System default, এবং সমস্যা হলে standard PCM mode ব্যবহার করো। AudioPipe-তে
   `com.music.bitchord` ও X-906-এর **A2DP** output বেছে Start চাপো। পুরোনো PLAY_AUDIO
   block-এর নির্দিষ্ট error থাকলে Stop → Restore selected app playback ব্যবহার করো।
6. BitChord ও Facebook/YouTube-তে আলাদা চেনা audio একসঙ্গে চালাও। BitChord pause করলে
   শুধু X-906 থামবে; অন্য app pause করলে শুধু phone speaker থামবে। দুটো চালালে কোনো
   duplicate/leak শোনা চলবে না। `Policy accepted` একা success নয়; native observed status-ও
   physical listening-এর বিকল্প নয়।
7. Home দিয়ে ২ মিনিট অন্য app ব্যবহার করো, screen off/on করো, AudioPipe আবার খোলো।
   Status, নির্বাচন ও audio অপরিবর্তিত কি না দেখো। পাঁচবার Stop/Start পরীক্ষা করো। Bluetooth
   disconnect করলে stopped/error প্রত্যাশিত; reconnect করে আবার select/Start করতে হবে।
8. Normal playback, background এবং failure-এর পরে আলাদা **Export diagnostics** দিয়ে `.txt`
   save করো। কী শব্দ কোথায় শুনেছ ও সময় লিখে report দাও। Pairing screenshot/key/code নয়।
   Report-এ model, Android SDK, output IDs, actual UID routes, service ও errors থাকবে।

## অবশিষ্ট সীমা ও বাস্তব বিকল্প

- Source app cached/frozen হলে native route পরিবর্তন প্রক্রিয়া নাও হতে পারে। Source-কে
  foreground-এ এনে playback restart করো; BitChord-এর media foreground service চালু রাখো।
  AudioPipe অন্য app-এর execution policy বা নিজে pause করার আচরণ জোর করে বদলায় না।
- Active session-এর মধ্যে output port/device বদলে গেলে service বন্ধ হতে পারে; স্থিতিশীল
  connection-এ আবার Start করতে হবে। এই build silently default output-কে split বলে না।
- Actual Redmi/Xiaomi firmware, X-906 acoustic isolation, installed BitChord output/focus
  behavior, দীর্ঘসময় battery/thermal stability এখনও unverified। Emulator ফল hardware proof নয়।
- Explicit player preferred-output request, offload/compressed/exclusive mode, OEM policy বা
  transient/call focus split ভাঙতে পারে। সব arbitrary app-এর সব আচরণ জোর করে আটকানোর
  guarantee নেই। Calls/voice chat/alarms/notifications এই media policy-র লক্ষ্য নয়।
- Bluetooth loss/force-stop/process death-এ policy সরে Android default ফিরে আসে; বাইরের app
  বাজতে থাকলে default output-এ শোনা যেতে পারে। এটি audio privacy barrier নয়।
- Stock ordinary APK permissions দিয়ে এই অন্য-app output control সম্ভব নয়। Authorized ADB
  shell একটি privileged alternative; সেটিও unsupported হলে vendor-supported per-app output,
  আলাদা device থেকে Bluetooth music, অথবা আলাদা root/system integration দরকার হবে। Root/
  custom ROM এই কাজ করেনি। Xiaomi Sound Assistant-এর volume feature-কে per-app routing
  প্রমাণ হিসেবে ধরা হয়নি।

## গুরুত্বপূর্ণ commits

- `9b7734b79dd2bd05463b4da66870299ec5953c7e`: real UID-policy backend, lifecycle ও diagnostics।
- `c98fe8569d295a5331541e49891f3d989aa82cba`: Android 14 native API/runtime corrections।
- `f3153c29b984f9d0289c90a866c924eabad65a3d`: safe persistent focus setting + reboot flow।
- `bf00860a1dfcce969c2455b17a04d9a147c4c6cd`: resource probe ও bounded CI checks।
- `360e07b28c28bdca8c8c452922d179302b16a79a`: successful exact-artifact delivery checks।
- `645be3c83277b01ab42ec22bee07818267c3527c`: বাস্তব A2DP পরীক্ষায় পাওয়া invalid UID exclusion API call সংশোধন এবং দুই framework regression test।
- `99ad5490f6206882bcc22d75634b16f0f7fcf239`: optional two-UID production service fixtures।

পূর্ণ source/platform audit ও primary-source links:
[AUDIO_ROUTING_AUDIT_BN.md](AUDIO_ROUTING_AUDIT_BN.md)।

Test platform/background sources: [Google Bumble speaker](https://google.github.io/bumble/apps_and_tools/speaker.html),
[Bumble Android/Netsim](https://google.github.io/bumble/platforms/android.html),
[AOSP cached-app freezer](https://source.android.com/docs/core/perf/cached-apps-freezer)।
