# AudioPipe 1.4: stability, Repair এবং ফোনের পরীক্ষা

বর্তমান APK ও **আসল automated ফলাফল** [verification report](AUDIOPIPE_VERIFICATION_BN.md)-এ আছে।
এই নোট implementation ও physical test procedure বর্ণনা করে; Redmi/X-906-এ পরীক্ষা করার দাবি নয়।

## Stuttering audit

আগের service প্রতি ২.৫ সেকেন্ডে একটি নতুন `dumpsys media.audio_policy` process চালিয়ে
পূর্ণ native snapshot নিত। এটি read-only observation; ওই loop-এ audio policy পুনরায়
register করা, audio focus বদলানো বা PLAY_AUDIO permission লেখা ছিল না। AudioPipe-এর
বর্তমান routing path কোনো AudioRecord/AudioTrack buffer চালায় না। তাই পুরোনো অব্যবহৃত
capture worker-এর buffer বদলে এই সমস্যার সমাধান হয়েছে বলা সঠিক হবে না।

এই build-এ স্থির অবস্থায় পূর্ণ snapshot ১৫ সেকেন্ড অন্তর হয়। শুরুতে অল্প সময় এবং wrong/
unknown output settling-এর সময় ছোট bounded interval থাকে। Output-device callback পরিবর্তন
জানালে আগেও পরীক্ষা হয়; codec-event burst coalesce হয়। কোনো periodic policy-reinstall বা
focus-writing loop যোগ হয়নি। এটি observer overhead কমায়; নিজের ফোনের প্রায় দুই সেকেন্ড
অন্তর stutter-এর causal proof বা audible fix এখনও নয়।

UI-তে **Last checked** ও **Last observed by Android** আছে। মাঝের সময়ে status একটি আগের
snapshot; continuous hardware verification নয়। Bluetooth loss callback/পরবর্তী check এবং
স্থায়ী wrong/unknown routing-এ service বন্ধ হয়। AudioPipe কোনো audio privacy barrier নয়।

## Repair AudioPipe policy

এটি একটি manual, bounded পুনরুদ্ধার ব্যবস্থা। চলমান session থাকলে:

1. প্রথমে saved identity দিয়ে ADB shell পরীক্ষা/প্রয়োজনে reconnect করে। ADB পাওয়া না
   গেলে **আগের চলমান policy রেখে** Repair postponed দেখায়।
2. Source package এখনও একই UID কি না যাচাই করে। আগের owned native policy সরানোর
   acknowledgement পায়, তারপর একই app/device identity দিয়ে একটি নতুন policy তৈরি করে।
3. Cleanup acknowledgement না পেলে overlapping দ্বিতীয় policy তৈরি করে না। নতুন policy
   rejected হলে stopped/error হয়; successful split দাবি করে না।
4. দ্রুত বারবার চাপলে ৩০ সেকেন্ড cooldown। এটি নিজের থেকে প্রতি কয়েক সেকেন্ডে repair
   চালায় না। পুনরায় native original-player observation ছাড়া observed status ফেরে না।

Repair-এর সময়ে সাময়িক Android default output শোনা যেতে পারে। অন্য apps, audioserver,
ফোন বা AudioPipe process restart করা হয় না; volume/focus setting এবং অন্য app-এর buffer
reset করা হয় না। Bluetooth হারিয়ে গেলে বা firmware policy প্রত্যাখ্যান করলে Repair
সেটি জোর করে সমর্থিত বানাতে পারবে না। Stable Bluetooth করে আবার Start প্রয়োজন হতে পারে।

## Diagnostics

Export-এ actual original-player UID/session/output, foreground service state, বর্তমান ADB
status ও transitions, connection-port hint, saved-identity flag, snapshot-এর বয়স/গণনা/সময়,
policy generation/repair attempts, helper CPU/open descriptors এবং app heap/CPU থাকে।
Stopped/restarting session-এর আগের routing evidence পরিষ্কার হয়।
Normal Stop-এ bounded native cleanup শেষ না হওয়া পর্যন্ত foreground service রাখা হয়;
UI background-এ থাকলেও cleanup-এর মাঝখানে app cached/frozen হয়ে যাওয়া এড়ানো হয়েছে।

Private RSA key/ciphertext, pairing code, control token, MAC এবং পূর্ণ installed-app list
export হয় না। রিপোর্ট নিজে upload হয় না। Helper CPU-র মধ্যে আলাদা dumpsys process বা
পুরো system-server-এর CPU নেই; এই counters থেকে ফোনের battery saving-এর শতাংশ দাবি করা যায় না।

## Redmi 11 Prime + BitChord + X-906 test

1. সর্বশেষ APK install করে notifications allow করো। Xiaomi battery settings-এ পরীক্ষার
   সময় AudioPipe ও BitChord-এর background চালু/No restrictions রাখো। Debug signer আলাদা
   হলে Android update নাও মানতে পারে; uninstall করলে একবার নতুন pairing লাগবে।
2. Wi-Fi ও Wireless debugging On রাখো। `Connected — ADB shell verified` নিশ্চিত করো।
   Reconnect-এর বিস্তারিত [ADB guide](ADB_PAIRING_BN.md)-এ আছে। Shizuku entry মুছবে না।
3. X-906-এর Media audio connect করো। BitChord (`com.music.bitchord`) এবং X-906 A2DP
   নির্বাচন করো। Start যদি simultaneous media চায়, তখনই ওই option save করে reboot করো;
   তারপর Wireless debugging On করে ফিরে এসো। আগে করা থাকলে অযথা আবার করার প্রয়োজন নেই।
4. AudioPipe Stop রেখে গেম একা চালিয়ে ৩০–৬০ সেকেন্ড শোনো। এরপর Start করে BitChord ও
   গেম/YouTube-এর দুইটি চেনা শব্দ একসঙ্গে ২–৫ মিনিট শোনো। কোন output-এ কী শোনা যাচ্ছে,
   duplicate/leak, কাটার ব্যবধান এবং voice-chat চালু ছিল কি না লিখে রাখো।
5. BitChord pause করলে শুধু X-906 থামবে; অন্য app pause করলে শুধু speaker থামবে।
   দুইটি চালিয়ে Home-এ যাও, screen off/on করো, তারপর UI খোলো। নির্বাচিত app/device,
   Last checked ও audio ঠিক আছে কি না দেখো। আলাদা ৫টি Stop/Start পরীক্ষা করো।
6. Stutter বা stuck routing হলে **আগে একটি diagnostics export** করো। একবার Repair
   AudioPipe policy চাপো, settle হওয়ার পর আবার দুই output শোনো এবং দ্বিতীয় report করো।
   Repair-এর cooldown-এ বারবার চাপবে না। সফল message একা listening test-এর বিকল্প নয়।
7. Wireless debugging Off/On এবং Wi-Fi Off/On আলাদা করে পরীক্ষা করো। Android Wireless
   debugging Off করে দিলে নিজে On করো। নতুন pairing-code dialog ছাড়া reconnect হওয়া
   পরীক্ষা করো। ADB ফিরে আসা আর routing session ফিরে আসা আলাদা বিষয়।
8. Bluetooth বিচ্ছিন্ন করলে stopped/error প্রত্যাশিত। Reconnect করে নতুন live output
   নির্বাচন/Start করো। তারপরে আরেকটি report save করো।

রিপোর্টের সঙ্গে app version, ঘটনার সময়, কোন audio কোথায় শুনেছ এবং Repair-এর আগে/পরে
কী বদলেছে জানাও। Pairing code/private key পাঠিও না। এই real-device ফল ছাড়া exact Redmi/
BitChord/X-906 split audio বা stutter পুরো ঠিক হয়েছে বলা যাবে না।
