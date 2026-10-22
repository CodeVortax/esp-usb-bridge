<div dir="rtl" align="center">

# ESP USB Bridge

**اعمل فلاش ومراقبة لـ ESP32 من Termux. بدون روت وبدون كمبيوتر.**

تطبيق أندرويد صغير يمسك منفذ USB ويشاركه مع Termux كمنفذ تسلسلي عبر الشبكة المحلية، فيشتغل `esptool` ومراقب السيريال مباشرة من سطر الأوامر.

[English](README.md) · **العربية**

</div>

<div dir="rtl">

---

## ليه؟

أندرويد لا يسمح لـ Termux بفتح أجهزة USB، ومن غير روت لا يوجد `/dev/ttyUSB0`. التطبيق يحل المشكلة من الجهة الثانية: هو المسموح له باستخدام USB، فيفتح المنفذ ويقدمه لـ Termux على `127.0.0.1:2217` بمعيار **RFC 2217** الذي يفهمه `esptool` و`pySerial`. إعادة التشغيل التلقائية لوضع التحميل وتغيير السرعة والمراقب كلها تشتغل.

## التثبيت

1. **التطبيق:** نزّل `esp-usb-bridge-debug.apk` من [آخر إصدار](../../releases/latest) وثبّته.
2. **Termux:**

```sh
curl -fsSL https://raw.githubusercontent.com/CodeVortax/esp-usb-bridge/main/termux/install.sh | bash
```

3. **اللوحة:** وصّلها بكابل **بيانات** ووصلة OTG، واضغط موافق على إذن USB.

## الاستخدام

```sh
espb start                    # يفتح التطبيق ويشغّل الجسر
espb status                   # حالة الجسر والجهاز
espb flash firmware.bin       # فلاش ملف مدموج على 0x0
espb flash fw.bin --manual    # بدون ريسيت تلقائي: اضغط BOOT بنفسك
espb info                     # معلومات الشريحة
espb monitor                  # مراقب تفاعلي (Ctrl+] للخروج)
espb log --seconds 20 --reset # سطور بوقت ثم يخرج
espb doctor                   # فحص الإعداد
```

## مشاكل شائعة

- **Wrong boot mode (0x13):** اللوحة لم تدخل وضع التحميل. اضغط زر **BOOT** أثناء ظهور `Connecting...` أو استخدم `--manual`.
- **الجسر لم يعمل:** وصّل اللوحة أولاً ثم `espb start` واضغط موافق على إذن USB.
- **يتوقف مع إطفاء الشاشة:** عطّل توفير البطارية للتطبيق.

الرخصة: MIT.

</div>
