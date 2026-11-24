# EterScreen Translator

แอปพลิเคชัน Android สำหรับแปลภาษาบนหน้าจอแบบปุ่มลอย (Floating Screen Translator) พัฒนาด้วย Kotlin, Jetpack Compose (Material 3) และสถาปัตยกรรม MVVM + Clean Architecture โดยรองรับการแปล **มังงะญี่ปุ่น → ไทย**, **มันฮวาเกาหลี → ไทย** และข้อความทั่วไปบนหน้าจอแอปอื่น

---

## รุ่นของแอป (Product Flavors: Manual vs Full Edition)

โปรเจกต์แบ่งออกเป็น 2 รุ่นย่อย (Product Flavors ในมิติ `edition`) เพื่อแยกโครงสร้าง Manifest และลดการประกาศสิทธิ์ที่ไม่จำเป็นในรุ่น Manual:

| คุณสมบัติ | **Manual Edition (`manual`) — แนะนำสำหรับทดสอบทั่วไป** | **Full Edition (`full`)** |
|---|---|---|
| **ชื่อแสดงบนเครื่อง** | `EterScreen Translator` | `EterScreen Translator Full` |
| **Application ID** | `com.eteroverseer.screentranslator` | `com.eteroverseer.screentranslator.full` |
| **การประกาศ Accessibility Service** | **ไม่มี** (ไม่รวมใน merged `AndroidManifest.xml`) | **มี** (`AutoScrollAccessibilityService`, `canRetrieveWindowContent="false"`) |
| **Auto-scroll Detection** | ปิดการใช้งาน (แสดงข้อความแจ้งใน UI) | เปิดใช้งาน (`TYPE_VIEW_SCROLLED` เท่านั้น) |
| **Manual Screen Capture (`MediaProjection`)** | ใช้งานได้เต็มรูปแบบ | ใช้งานได้เต็มรูปแบบ |
| **ML Kit OCR / Offline / Gemini AI / Overlay / History** | ใช้งานได้เต็มรูปแบบ | ใช้งานได้เต็มรูปแบบ |

> **หมายเหตุด้าน Google Play Protect:** รุ่น **Manual Edition** ถูกออกแบบมาโดย **ไม่ประกาศ Accessibility Service ใน Manifest** ของ APK เพื่อหลีกเลี่ยงตัวกระตุ้นการเตือนจากบริการ Accessibility อย่างไรก็ตาม การยอมรับหรือการบล็อกของ Google Play Protect / Enhanced Fraud Protection ขึ้นอยู่กับการประเมินจริงบนอุปกรณ์ปลายทาง ผู้ใช้จำเป็นต้องทดสอบติดตั้งบนอุปกรณ์จริงด้วยตนเอง (การถอด Accessibility Service ช่วยตัดเงื่อนไขนี้ แต่ไม่ถือเป็นการรับประกันผลการตรวจสอบของ Google)

---

## ความสามารถหลัก

1. **โหมดการแปล 3 รูปแบบ (`TranslationRouter`)**
   - **Offline Mode (`OfflineTranslationEngine`)**: ใช้ ML Kit OCR + Language Identification + ML Kit On-Device Translation (`ja`, `ko`, `en` → `th`) ประมวลผลบนเครื่อง 100% ไม่ส่งข้อมูลออกนอกเครื่อง และตรวจสอบสถานะโมเดลจริงก่อนแปล
   - **Gemini AI Mode (`GeminiTranslationEngine`)**: เชื่อมต่อผ่าน **Firebase AI Logic (`Firebase.ai`, โมเดล `gemini-3.8-flash`) + App Check** โดยไม่ต้องฝัง API Key ในซอร์สโค้ด พร้อมคำสั่งแปลเฉพาะทางมังงะ/มันฮวา
   - **Hybrid Mode (ค่าเริ่มต้น)**: อ่าน OCR บนเครื่องก่อน แล้วเลือกแปลออฟไลน์ก่อนหรือเน้น Gemini AI ตามที่ผู้ใช้ตั้งค่า พร้อม Bounded LRU Cache และระบบป้องกันการส่งข้อมูลเมื่อผู้ใช้ปิด AI
2. **ระบบจับภาพหน้าจอ (`MediaProjection` + `ScreenCaptureForegroundService`)**
   - ทำงานผ่าน Foreground Service (`foregroundServiceType="mediaProjection"`) พร้อมแถบแจ้งเตือนที่มีปุ่ม **หยุดทันที** และ **พัก/ทำต่อ**
   - จัดการการหมุนหน้าจอ/เปลี่ยนขนาดหน้าต่าง ปล่อยทรัพยากร `VirtualDisplay`, `ImageReader`, `Bitmap` อย่างปลอดภัย และเคารพ `FLAG_SECURE`
   - ไม่บันทึกภาพหน้าจอถาวรเป็นค่าเริ่มต้น
3. **ระบบตรวจจับหยุดเลื่อนอัตโนมัติ (`AutoScrollAccessibilityService` — เฉพาะ Full Edition)**
   - ประกาศเฉพาะใน `app/src/full/AndroidManifest.xml`
   - ฟังเฉพาะ Event การเลื่อนหน้าจอจริง (`TYPE_VIEW_SCROLLED`) โดยตั้งค่า `canRetrieveWindowContent="false"` และกรอง Event จากแอปตัวเอง, SystemUI รวมถึง Event พิกัดซ้ำ
   - มี Debounce ปรับได้ 1.0 – 5.0 วินาที (ค่าเริ่มต้น 1.5 วินาที), Cooldown และ dHash Screen Deduplication
4. **ตัวจัดการบริบทมังงะ (`MangaContextProcessor`)**
   - จัดกลุ่มกล่องข้อความตามบอลลูนคำพูด ไม่รวมข้อความข้ามพาเนล
   - เรียงลำดับอ่านแบบ **ขวาไปซ้าย (RTL)** สำหรับมังงะญี่ปุ่น และ **บนลงล่าง/ซ้ายไปขวา (LTR)** สำหรับมันฮวาเกาหลี

---

## สิทธิ์ที่แต่ละรุ่นใช้งาน

### Manual Edition (`com.eteroverseer.screentranslator`)
- **`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION`**: สำหรับจับภาพหน้าจอเมื่อผู้ใช้กดอนุญาต
- **`SYSTEM_ALERT_WINDOW`**: สำหรับแสดงปุ่มลอยและหน้าต่างคำแปลทับแอปอื่น (`WindowManager`)
- **`POST_NOTIFICATIONS`** *(Android 13+)*: สำหรับแสดงการแจ้งเตือนสถานะจับภาพและปุ่มหยุดด่วน
- **`INTERNET` / `ACCESS_NETWORK_STATE`**: สำหรับดาวน์โหลดโมเดล ML Kit และเรียก Gemini AI (เมื่อผู้ใช้เปิดใช้งาน)
- *(ไม่มี `AutoScrollAccessibilityService` และไม่มี `BIND_ACCESSIBILITY_SERVICE` ใน Manifest)*

### Full Edition (`com.eteroverseer.screentranslator.full`)
- ใช้สิทธิ์ทั้งหมดข้างต้น **ร่วมกับ**
- **`BIND_ACCESSIBILITY_SERVICE`** *(ตัวเลือกเสริม เปิดใช้ด้วยตนเองในการตั้งค่า Android)*: สำหรับ `AutoScrollAccessibilityService` เพื่อจับจังหวะหยุดเลื่อนหน้าจอ (`canRetrieveWindowContent="false"`)

---

## วิธีใช้งาน

### การใช้งาน Manual Capture (ใช้ได้ทั้ง Manual และ Full Edition)
1. เปิดแอปแล้วอนุญาตสิทธิ์ **หน้าต่างลอย (Overlay)** และการแจ้งเตือน (หากระบบถาม)
2. กดปุ่ม **เริ่มแปลหน้าจอ** และยืนยันสิทธิ์จับภาพหน้าจอ **MediaProjection**
3. เปิดแอปมังงะหรือแอปที่ต้องการแปล แล้วแตะ **ปุ่มลอย** (หรือกดปุ่ม **จับภาพและแปลทันที** ในหน้าควบคุม) เพื่อจับภาพหน้าจอปัจจุบันและแปลเป็นภาษาไทยทันที

### การตั้งค่า Auto-scroll (เฉพาะ Full Edition)
1. ติดตั้งรุ่น **Full Edition** (`EterScreen Translator Full`)
2. ในหน้าแรก กดปุ่ม **ตั้งค่า** ข้างรายการ Accessibility (หรือไปที่ **การตั้งค่า Android → การช่วยเหลือการเข้าถึง → EterScreen Translator Full**) แล้วเปิดบริการ
3. เลือกโหมดจับภาพเป็น **อัตโนมัติ (Auto)** หรือ **ทั้งกดเองและอัตโนมัติ** และปรับระยะหน่วง/Cooldown ได้ในหน้าตั้งค่า

---

## การตั้งค่า Firebase AI Logic และ App Check

> **หมายเหตุสำคัญ:** ทั้งสองรุ่นสามารถใช้งาน **Offline Mode** (ML Kit OCR + Language ID + On-Device Translation) ได้เต็มรูปแบบแม้ยังไม่ได้ตั้งค่า Firebase

หากต้องการเปิดใช้งาน **Gemini AI Mode (`gemini-3.8-flash`)**:

1. **Android App Package Name:** ลงทะเบียนแอปใน Firebase Console ตามรุ่นที่ใช้งาน:
   - Manual Edition: `com.eteroverseer.screentranslator`
   - Full Edition: `com.eteroverseer.screentranslator.full`
2. **เปิดใช้งาน Firebase AI Logic** และ **Firebase App Check** ในโปรเจกต์ Firebase ของคุณ
3. **เพิ่มไฟล์ `app/google-services.json`:** ดาวน์โหลดจาก Firebase Console แล้ววางในโฟลเดอร์ `app/` *(ไฟล์นี้ถูกยกเว้นใน `.gitignore`)*
4. **App Check สำหรับ Debug Build (`BuildConfig.DEBUG`):** `AppCheckInitializer` ติดตั้ง `DebugAppCheckProviderFactory` อัตโนมัติโดยไม่บันทึกค่า Debug Token ลง Log
5. **App Check สำหรับ Release Build (`!BuildConfig.DEBUG`):** กำหนด String Resource `recaptcha_site_key` ก่อนคอมไพล์ Release APK เพื่อใช้งาน `RecaptchaAppCheckProviderFactory`

---

## คำสั่งสร้าง APK และรันการทดสอบ

> บนเครื่องที่มี Gradle Wrapper ให้ใช้ `./gradlew` (Linux/macOS) หรือ `gradlew.bat` (Windows) และหากสภาพแวดล้อมใช้ Gradle ที่ติดตั้งไว้ในระบบโดยตรง สามารถแทนด้วยคำสั่ง `gradle`

### Linux / macOS
```bash
# รัน Unit & Robolectric Tests แยกแต่ละรุ่น
./gradlew :app:testManualDebugUnitTest
./gradlew :app:testFullDebugUnitTest

# สร้าง Debug APK แยกแต่ละรุ่น
./gradlew :app:assembleManualDebug
./gradlew :app:assembleFullDebug

# ตรวจสอบ Merged Manifest และ Packaged APK ของทั้งสองรุ่น
python3 scripts/verify_flavor_manifests.py
```

### Windows (Command Prompt / PowerShell)
```bat
REM รัน Unit & Robolectric Tests แยกแต่ละรุ่น
gradlew.bat :app:testManualDebugUnitTest
gradlew.bat :app:testFullDebugUnitTest

REM สร้าง Debug APK แยกแต่ละรุ่น
gradlew.bat :app:assembleManualDebug
gradlew.bat :app:assembleFullDebug

REM ตรวจสอบ Merged Manifest และ Packaged APK
python scripts\verify_flavor_manifests.py
```

### ตำแหน่งไฟล์ APK ที่ได้
- **Manual Edition (Primary):** `app/build/outputs/apk/manual/debug/EterScreen-Translator-manual-debug.apk`
- **Full Edition:** `app/build/outputs/apk/full/debug/EterScreen-Translator-full-debug.apk`

---

## GitHub Actions (CI/CD)

Workflow อยู่ที่ `.github/workflows/build-apk.yml` โดยทำงานกับ **Node.js 24 actions**, **JDK 21 (Temurin)** และ **Gradle 9.3.1**:

- รัน `:app:testManualDebugUnitTest` และ `:app:testFullDebugUnitTest` พร้อมอัปโหลดรายงานทดสอบแยกทั้ง 2 รุ่น
- สร้างทั้ง **Manual Debug APK** และ **Full Debug APK**
- รัน `scripts/verify_flavor_manifests.py` ตรวจยืนยันทั้ง Merged Manifest และ Packaged APK (CI จะล้มเหลวทันทีหากรุ่น Manual มี Accessibility Service หรือรุ่น Full ขาด Service)
- อัปโหลด **Manual APK** (`EterScreen-Translator-manual-debug`) เป็น Artifact หลัก และอัปโหลด **Full APK** (`EterScreen-Translator-full-debug`) แยกต่างหากชัดเจน
- เผยแพร่ APK ทั้ง 2 รุ่น (พร้อม `.sha256`) ไปยัง **GitHub Releases** อัตโนมัติเมื่อ push เข้า `main` (แท็ก `latest-editions-debug` พร้อมตั้งเป็น Latest), เมื่อ push tag `v*`, หรือเมื่อสั่งรันผ่าน `workflow_dispatch` (ค่าเริ่มต้น `publish_release = true`)
- **Repository Secrets (ตัวเลือกเสริม):** `GOOGLE_SERVICES_JSON_B64` สำหรับใส่ `app/google-services.json` ใน CI (หากไม่กำหนด แอปยังบิลด์ผ่านและใช้โหมดออฟไลน์ได้ตามปกติ)
