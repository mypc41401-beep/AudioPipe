# AudioPipe: ফোন থেকে ADB pairing ও reconnect

## 1.3-এর reconnect পরিবর্তন

Wi-Fi/ Wireless debugging বন্ধ হলে paired-device entry থাকতে পারে, কিন্তু আগের TCP
port বা socket আর কাজ নাও করতে পারে। AudioPipe এখন সেই পুরোনো port-কে পরিচয় হিসেবে
ধরে রাখে না। Saved RSA identity আগের একই Android Keystore AES-GCM format-এ থাকে;
শুধু ব্যর্থ port hint বাতিল করে বর্তমান `_adb-tls-connect._tcp` service খোঁজে। Android
14-এ NSD service-info callback port বদলানোও অনুসরণ করে। অন্য ফোনের service গ্রহণ করে না;
ADB connection এখনও কেবল এই ফোনের `127.0.0.1`-এ হয়।

- AudioPipe দেখা যাচ্ছে, অথবা routing foreground service চলছে—এই সময় automatic
  recovery সক্রিয়। সুস্থ connection-এ ৩০ সেকেন্ড পর shell liveness probe হয়। App বন্ধ
  এবং routing বন্ধ থাকলে network observer, NSD watch ও retry timer বন্ধ থাকে।
- ব্যর্থতার পরে ১/২/৪/৮/১৫ সেকেন্ড backoff; এরপর timer দিয়ে অনন্ত retry নয়। নতুন
  local service, network change, app আবার খোলা বা manual Connect হলে recovery জাগে।
- Wi-Fi হারালে UI disconnected দেখায়। Wi-Fi ফিরলে saved identity দিয়ে discovery হয়।
  **Android Wireless debugging নিজে বন্ধ করে দিলে Settings থেকে সেটি আবার On করতে হবে।**
  AudioPipe নিরাপত্তার এই setting নিজের থেকে চালু করে না।
- NSD/mDNS ROM বা network-এ ব্যর্থ হলে Setup → Connection port-এ মূল Wireless debugging
  page-এর বর্তমান port লিখে Connect / verify shell করা যায়; নতুন pairing code লাগে না।
- Key decrypt ব্যর্থ হলে আগের identity আর silently নতুন key দিয়ে প্রতিস্থাপন হয় না।
  Unlock/restart করে আবার চেষ্টা করো; app data clear করলে saved identity হারাবে।
- Disconnect-এর সময়ে shell command-এর ফল অজানা হলে সেটি নিজে replay হয় না; একই
  mutating command দুবার চলে যাওয়ার ঝুঁকি এড়ানো হয়েছে। Cancel retry-ও থামায়।

এগুলো implementation-এর বর্ণনা; সর্বশেষ automated ফলাফল ও APK link
[verification report](AUDIOPIPE_VERIFICATION_BN.md)-এ দেখো। নিজের Redmi-তে নিচের পরীক্ষা দরকার।

### Redmi-তে reconnect পরীক্ষা

1. আগে notifications allow করে `Connected — ADB shell verified` নিশ্চিত করো। Pairing
   identity পরীক্ষা করতে Android Settings-এর Paired devices-এ AudioPipe entry খেয়াল করো;
   সেটি Forget করবে না।
2. AudioPipe খোলা রেখে Wireless debugging Off → On করো। আবার connected হওয়া ও নতুন
   connection port পাওয়া পরীক্ষা করো; pairing-code dialog খোলা লাগার কথা নয়।
3. Wi-Fi Off → On করো, আগের network-এ যুক্ত হও। Android Wireless debugging Off করে
   দিলে নিজে On করো। AudioPipe-তে ফিরে saved identity দিয়ে reconnect পরীক্ষা করো।
4. Routing চালু রেখে Home-এ গিয়ে একই পরীক্ষা করো। তারপর AudioPipe পুনরায় খোলো।
   ADB connection ফেরত আসা **routing policy বা audio isolation ফিরে আসার প্রমাণ নয়**;
   routing বন্ধ হয়ে গেলে Bluetooth stable করে আবার Start করতে হবে।
5. AudioPipe বন্ধ/পুনরায় খোলা ও ফোন reboot-এর পরে Wireless debugging On করে পরীক্ষা
   করো। NSD ব্যর্থ হলে manual current connection port fallback ব্যবহার করো।
6. ব্যর্থ হলে Export diagnostics করো, Wi-Fi/ Wireless debugging কোনটি বদলেছিল ও সময়
   লিখে দাও। Private key, pairing code বা control token পাঠিও না।

App update একই signing identity-তে install হলে data/key থাকে। অন্য CI debug signer হলে
Android update প্রত্যাখ্যান করতে পারে; uninstall করলে key/data হারাবে এবং একবার নতুন
pairing লাগবে। Debug signer-এর এই সীমা Wi-Fi toggle-এ key পুনর্ব্যবহারের আচরণ থেকে আলাদা।

> **আগের routing build:** 1.2-তে ADB pairing একই আছে; capture/replay বদলে shell
> UID policy ও diagnostics যোগ হয়েছে। বর্তমান APK/অনুমতি/ফোনের audio test-এর জন্য
> [সর্বশেষ APK/যাচাই/ফোন নির্দেশনা](AUDIOPIPE_VERIFICATION_BN.md) এবং
> [routing audit](AUDIO_ROUTING_AUDIT_BN.md) পড়ো। নিচের 1.1 build results ঐতিহাসিক।

এই পরিবর্তন AudioPipe-এর ADB setup/authentication-এর জন্য। `audio/`-এর capture ও routing
algorithm অপরিবর্তিত। নিজের Xiaomi/Bluetooth ফোনে পরীক্ষা হয়নি। ADB connection সফল
হওয়া split audio কাজ করার প্রমাণ নয়।

## যাচাইকৃত ফলাফল — ১৫ নভেম্বর ২০২৬

- [সম্পূর্ণ সফল GitHub Actions run](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43640973733)
- [সংশোধিত APK ZIP](https://github.com/mypc41401-beep/AudioPipe/actions/runs/43640973733/artifacts/14131379395)
- Build source: `59c90333184829b3e09bf8c3704a6a65926f924e`; version `1.1-adb-pairing` (code 2)।
- APK compile, v2 signature ও download checksum যাচাই সফল; ১৩টি JVM unit test পাস।
- Secure Android 14 emulator-এ ৬টি device test পাস: সঠিক/ভুল code, tampered response,
  invalid peer/header, এবং আসল system pairing service/adbd-তে pairing/authentication।
- বাস্তব adbd-তে `id -u` ফল `2000`; একই connection-এ পরপর shell command এবং encrypted
  saved key থেকে নতুন executor দিয়ে reconnect সফল।
- Remote stream বন্ধ হলে অতিরিক্ত CLSE পাঠানোর bug-ও ঠিক হয়েছে। পুরোনো closed stream-এর
  দেরিতে আসা packet এখন পরের command নষ্ট করে না; regression test আছে।
- CI-তে pairing service শুরু/তার code ও port পড়ার জন্য **test-only** shell fixture আছে।
  উৎপাদনের pairing/client চালানোর আগে delegated shell permission drop করা হয়। এই fixture
  app-debug.apk-তে নেই; ফোনের app hidden system API বা MANAGE_DEBUGGING permission চায় না।
- Full Settings UI automation-কে সফল বলা হচ্ছে না। Pairing UI input validation JVM-এ
  পরীক্ষা হয়েছে; নিজের Xiaomi-তে split-screen UI ও OEM networking এখনও পরীক্ষা করতে হবে।
- Bluetooth + speaker simultaneous routing বাস্তব hardware-এ পরীক্ষা হয়নি।

APK SHA-256: `ee79d44976b2682f5d33bd312a27d08372f0053b4122a0314cb260379e0fec4d`। ZIP প্রায় ১৩.২ MB; APK প্রায় ৩০.৮ MB;
ARM64, ARMv7, x86 ও x86_64 library অন্তর্ভুক্ত। Artifact ১৪ দিন থাকে।

## পুরোনো Setup কেন ব্যর্থ হতো

Setup বোতাম pairing করত না, শুধু retry করত। নতুন install-এ paired/imported key flag
না থাকলে discovery-ও শুরু হতো না। Shizuku API/provider integration নেই; Shizuku
Running মানে AudioPipe-এর নিজস্ব key অনুমোদিত নয়। Import Existing Key শুধু file picker
খুলত। mDNS failure, stale port এবং blocking socket-এর জন্য নির্দিষ্ট timeout/error ছিল না।

## পরিবর্তন

- Setup / Pair-এ pairing port ও ৬ সংখ্যার code নেওয়ার UI। নিজস্ব encrypted persistent
  RSA key দিয়ে TLS 1.3, TLS exporter, SPAKE2 ও encrypted PeerInfo exchange হয়।
- সফল encrypted exchange-এর পরে paired flag save হয়। TLS authentication এবং `id -u`
  shell probe সফল হলে তবেই **Connected — ADB shell verified** দেখায়।
- Pairing port ও connection port আলাদা। mDNS না পেলে হাতে port লেখা যায়। Discovery
  নিজের ফোনের service-ই গ্রহণ করে; connection শুধু `127.0.0.1`-এ সীমাবদ্ধ।
- TCP connect সর্বোচ্চ ৪ সেকেন্ড, authentication/shell ৮ সেকেন্ড, pairing ১৫ সেকেন্ড,
  discovery ৫ সেকেন্ড। Timeout-এ socket বন্ধ হয়; endless retry loop নেই।
- Wrong/expired code, connection refused, TLS failure, invalid input ও discovery failure
  UI-তে দেখা যায়। Cancel চলমান network operation বন্ধ করে।
- একই ADB socket-এর commands serialize হয়; packet size ও stream ID পরীক্ষা হয়।
- Code/password save বা log হয় না। Key Android Keystore-এর AES-GCM দিয়ে encrypted;
  sensitive settings-এর app backup বন্ধ।

## Install

GitHub-এ login করে successful run-এর **Artifacts → AudioPipe-debug** ZIP নামাও। Extract
করে `app-debug.apk` install করো। পুরোনো CI build-এর signing key আলাদা হলে update
install ব্যর্থ হতে পারে। সে ক্ষেত্রে পুরোনো AudioPipe-তে আগে **Stop** চাপো, পুরোনো
app uninstall করে নতুনটি install করো; app data/setup হারাবে।

## প্রথম pairing: PC, Termux বা private-key file লাগবে না

1. ফোন Wi-Fi-তে যুক্ত রাখো। **Developer options → Wireless debugging → On** করো;
   Android নিজের network-এর অনুমতি চাইলে Allow দাও।
2. **Split screen**-এ একদিকে **AudioPipe → Setup / Pair**, অন্যদিকে
   **Settings → Developer options → Wireless debugging** রাখো।
3. Settings-এ **Pair device with pairing code** চাপো। Dialog খোলা রেখেই সেখানে দেখানো
   `IP:PORT`-এর শুধু **PORT** ও ছয় সংখ্যার code AudioPipe-তে লিখে **Pair and connect** চাপো।
   চাইলে **Find pairing port automatically** ব্যবহার করা যায়।
4. Paired হওয়ার পরে automatic connect না হলে Settings-এর pairing dialog বন্ধ করো।
   মূল **Wireless debugging** page-এর **IP address & port**-এর PORT AudioPipe-এর
   **Connection port** ঘরে দিয়ে **Connect / verify shell** চাপো।
5. **Pairing port ও connection port আলাদা।** ভুল port দিলে হবে না। Reboot, Wireless
   debugging toggle বা Wi-Fi change-এ port বদলাতে পারে; বর্তমান port ব্যবহার করো।

Split screen না থাকলে Xiaomi floating window দিয়ে pairing-code dialog খোলা রেখে
AudioPipe-তে code দিতে হবে। Notification থেকে code নেওয়ার service এই সংস্করণে নেই।
Shizuku চলতে পারে, কিন্তু তার pairing বা private key AudioPipe ব্যবহার করে না।

## পরে connection ও সমস্যার সমাধান

- Authorization Forget না করলে প্রতিবার pairing দরকার হওয়ার কথা নয়। Wireless debugging
  চালু করে **Connect / verify shell** চাপো; mDNS ব্যর্থ হলে বর্তমান connection port দাও।
- TLS authorization ব্যর্থ হলে Wireless debugging পুরো চালু হওয়া পর্যন্ত অপেক্ষা করে
  retry করো। AudioPipe authorization সত্যিই সরানো হয়ে থাকলে বা বারবার rejected হলেই
  নতুন code দিয়ে pair করো। Saved-key unlock error-এ Forget/clear-data প্রথম পদক্ষেপ নয়।
- Wrong/expired code হলে Settings-এ নতুন pairing dialog খুলে নতুন code ও port দাও।
- প্রয়োজনে Xiaomi battery settings-এ AudioPipe-এর background/No restrictions অনুমতি দাও।
- Pairing-এর জন্য Microphone, Nearby devices বা location permission লাগে না। বর্তমান 1.2 build-এ
  microphone/capture অনুমতি লাগে না; foreground status-এর জন্য notification অনুমতি দাও।
- ADB connected হওয়ার পর appops permission error এলে Xiaomi-এর আলাদা **USB debugging
  (Security settings)** option প্রাসঙ্গিক হতে পারে। Error না থাকলে সেটি বদলানোর দরকার নেই।

## যাচাই এবং সীমাবদ্ধতা

Unit tests socket AUTH, shell streams, timeout, mDNS failure/fallback, persisted state ও
Setup input validation পরীক্ষা করে। Android integration tests আসল Android 14 system
pairing server/adbd, TLS exporter, encrypted exchange ও saved identity দিয়ে reconnect
পরীক্ষা করে; পাশাপাশি সঠিক/ভুল code ও tampered response-এর crypto tests আছে। Actions-এর
সর্বশেষ ফলাফল দেখো—এই test description নিজে test পাস করার দাবি নয়।

নিজের Xiaomi-তে এখনও split-screen UI, OEM networking/ADB policy, appops permission এবং
Bluetooth+speaker simultaneous routing পরীক্ষা করতে হবে। বর্তমান 1.2 build-এ ADB verified হওয়ার পরে AudioPipe-তে **BitChord ও X-906**
নির্বাচন করে Start করো; অন্য media UID-গুলোর জন্য speaker policy চাওয়া হয়। Capture
consent লাগে না। Simultaneous-media setting/reboot এবং listening test-এর সঠিক ধাপ
[routing audit](AUDIO_ROUTING_AUDIT_BN.md)-এ আছে।

## নতুন dependencies

| Dependency | ব্যবহার |
|---|---|
| Google Conscrypt Android 2.9.0 (Maven Central) | Public TLS exporter; hidden Android API bypass নয় |
| SPAKE2 Android 2.2.1 (group-scoped JitPack) | Android/BoringSSL-compatible native SPAKE2 AAR |

Android native SPAKE2 variant ব্যবহার করা হয়েছে; pure-Java variant ব্যবহার করা হয়নি। Upstream SPAKE2 library নিজেকে
unaudited বলে; এই কাজ formal security audit নয়। JVM tests-এ শুধু Conscrypt-এর Linux
variant ব্যবহার হয়; APK-তে Android variant থাকে। Existing Bouncy Castle 1.77 আপগ্রেড
এই ADB fix-এর scope-এ করা হয়নি। Self-signed adbd TLS certificate সাধারণ web PKI দিয়ে
যাচাই হয় না; pairing SPAKE2/TLS exporter-এ বাঁধা এবং connection নিজের ফোনে সীমাবদ্ধ।

Primary sources: [Android ADB](https://developer.android.com/tools/adb),
[SPAKE2 2.2.1](https://github.com/MuntashirAkon/spake2-java/tree/2.2.1),
[Conscrypt](https://github.com/google/conscrypt), এবং
[AOSP pairing](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_connection/).
