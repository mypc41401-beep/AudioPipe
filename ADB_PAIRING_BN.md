# AudioPipe 1.1: ফোন থেকে ADB pairing

এই পরিবর্তন AudioPipe-এর ADB setup/authentication-এর জন্য। `audio/`-এর capture ও routing
algorithm অপরিবর্তিত। নিজের Xiaomi/Bluetooth ফোনে পরীক্ষা হয়নি। ADB connection সফল
হওয়া split audio কাজ করার প্রমাণ নয়।

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
- TLS authorization ব্যর্থ হলে Settings-এর Paired devices থেকে শুধু AudioPipe-এর entry
  Forget করে নতুন code দিয়ে pair করো। Shizuku-এর entry মুছতে হবে না।
- Wrong/expired code হলে Settings-এ নতুন pairing dialog খুলে নতুন code ও port দাও।
- প্রয়োজনে Xiaomi battery settings-এ AudioPipe-এর background/No restrictions অনুমতি দাও।
- Pairing-এর জন্য Microphone, Nearby devices বা location permission লাগে না। Record
  audio ও notification অনুমতি আগের মতো audio capture ও status-এর জন্য লাগে।
- ADB connected হওয়ার পর appops permission error এলে Xiaomi-এর আলাদা **USB debugging
  (Security settings)** option প্রাসঙ্গিক হতে পারে। Error না থাকলে সেটি বদলানোর দরকার নেই।

## যাচাই এবং সীমাবদ্ধতা

Unit tests socket AUTH, shell streams, timeout, mDNS failure/fallback, persisted state ও
Setup input validation পরীক্ষা করে। Android integration tests আসল Android 14 Settings
pairing server/adbd, TLS exporter, encrypted exchange ও saved identity দিয়ে reconnect
পরীক্ষা করে; পাশাপাশি সঠিক/ভুল code ও tampered response-এর crypto tests আছে। Actions-এর
সর্বশেষ ফলাফল দেখো—এই test description নিজে test পাস করার দাবি নয়।

নিজের Xiaomi-তে এখনও split-screen UI, OEM networking/ADB policy, appops permission এবং
Bluetooth+speaker simultaneous routing পরীক্ষা করতে হবে। ADB verified হওয়ার পরে music
app Bluetooth-এ রাখো; AudioPipe-তে দ্বিতীয় app ও built-in speaker নির্বাচন করে Start
ও capture consent দাও। একটি করে source pause করে output আলাদা করে যাচাই করো; শেষে Stop।

## নতুন dependencies

| Dependency | ব্যবহার |
|---|---|
| Google Conscrypt Android 2.9.0 (Maven Central) | Public TLS exporter; hidden Android API bypass নয় |
| SPAKE2 Android 2.2.1 (group-scoped JitPack) | Android/BoringSSL-compatible native SPAKE2 AAR |

Unstable pure-Java SPAKE2 implementation ব্যবহার করা হয়নি। Upstream SPAKE2 library নিজেকে
unaudited বলে; এই কাজ formal security audit নয়। JVM tests-এ শুধু Conscrypt-এর Linux
variant ব্যবহার হয়; APK-তে Android variant থাকে। Existing Bouncy Castle 1.77 আপগ্রেড
এই ADB fix-এর scope-এ করা হয়নি। Self-signed adbd TLS certificate সাধারণ web PKI দিয়ে
যাচাই হয় না; pairing SPAKE2/TLS exporter-এ বাঁধা এবং connection নিজের ফোনে সীমাবদ্ধ।

Primary sources: [Android ADB](https://developer.android.com/tools/adb),
[SPAKE2 2.2.1](https://github.com/MuntashirAkon/spake2-java/tree/2.2.1),
[Conscrypt](https://github.com/google/conscrypt), এবং
[AOSP pairing](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_connection/).
