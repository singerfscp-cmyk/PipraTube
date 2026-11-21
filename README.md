# PipraTube TV (Android TV / Google TV)

pipratube.com কে অ্যান্ড্রয়েড টিভি ও গুগল টিভিতে চালানোর অ্যাপ।
রিমোটের ডি-প্যাড, Back, Menu ও মিডিয়া বাটন সাপোর্ট করে। ভিডিও ফুলস্ক্রিন, ইন্টারনেট না থাকলে "আবার চেষ্টা করুন" স্ক্রিন, টিভি লঞ্চারে ব্যানার আছে।

## APK বানানোর দুটি উপায়

### উপায় ১: GitHub দিয়ে (কম্পিউটারে কিছু ইনস্টল লাগে না)
1. github.com-এ একটি নতুন repository বানান, এই ফোল্ডারের সব ফাইল (`.github` সহ) আপলোড করুন।
2. Actions ট্যাবে "Build APK" চলবে (বা "Run workflow" চাপুন)।
3. শেষ হলে রান পেজের নিচে Artifacts থেকে `PipraTube-TV-apk` ডাউনলোড করুন, জিপ খুললে `app-debug.apk` পাবেন।

### উপায় ২: Android Studio দিয়ে
1. Android Studio-তে এই ফোল্ডার Open করুন, Gradle Sync শেষ হতে দিন।
2. Build > Build APK(s) চাপুন। APK থাকবে `app/build/outputs/apk/debug/app-debug.apk`।

## টিভিতে ইনস্টল
1. টিভিতে Settings > Device Preferences > About > Build নম্বরে ৭ বার ক্লিক করে Developer options চালু করুন, তারপর USB debugging / Unknown sources অন করুন।
2. APK পেনড্রাইভে রেখে টিভিতে লাগান এবং ফাইল ম্যানেজার (যেমন "File Commander", "X-plore") দিয়ে খুলে ইনস্টল করুন।
   অথবা ফোন/পিসি থেকে: `adb connect <টিভির-IP>` তারপর `adb install app-debug.apk`।
3. অ্যাপ লঞ্চারে "PipraTube" নামে পাবেন।

## রিমোট কন্ট্রোল
| বাটন | কাজ |
|---|---|
| ডি-প্যাড | লিংক/কার্ডে ঘোরা (লাল বর্ডারে ফোকাস দেখায়) |
| OK / Enter | সিলেক্ট |
| Back | পিছনে যাওয়া / ফুলস্ক্রিন থেকে বের হওয়া / ২ বার চেপে অ্যাপ থেকে বের হওয়া |
| Menu (বা ≡) | হোম, রিলোড, পিছনে, বের হওয়া মেনু |
| Play/Pause, FF, RW | ভিডিও চালানো/থামানো/১০ সেকেন্ড আগানো-পেছানো |

## কাস্টমাইজ
`app/src/main/java/com/pipratube/tv/MainActivity.kt`-এর শুরুতে `Config` অবজেক্টে:
- `HOME_URL` : শুরুর ঠিকানা
- `DESKTOP_MODE` : `true` করলে ডেস্কটপ লেআউট চাইবে (যদি সাইটের মোবাইল লেআউট টিভিতে ভালো না লাগে)
- `SEEK_SECONDS` : FF/RW কত সেকেন্ড

## নোট
- এটি ডিবাগ-সাইনড APK, নিজের টিভিতে সাইডলোড করার জন্য যথেষ্ট। Play Store-এ দিতে চাইলে release সাইনিং ও আলাদা সেটআপ লাগবে।
- ডি-প্যাড নেভিগেশন সাইটের HTML-এর ওপর নির্ভর করে। কোনো বাটনে ফোকাস না গেলে সাইটে `tabindex`/সঠিক লিংক ট্যাগ থাকা দরকার।
