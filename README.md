# EterScreen Translator

แอปพลิเคชัน Android สำหรับแปลภาษาบนหน้าจอแบบปุ่มลอย (Floating Screen Translator) พัฒนาด้วย Kotlin, Jetpack Compose (Material 3) และสถาปัตยกรรม MVVM + Clean Architecture โดยรองรับการแปล **มังงะญี่ปุ่น → ไทย**, **มันฮวาเกาหลี → ไทย** และข้อความทั่วไปบนหน้าจอแอปอื่น

---

## ความสามารถหลัก

1. **โหมดการแปล 3 รูปแบบ (`TranslationRouter`)**
   - **Offline Mode (`OfflineTranslationEngine`)**: ใช้ ML Kit OCR + Language Identification + ML Kit On-Device Translation (`ja`, `ko`, `en` → `th`) ประมวลผลบนเครื่อง 100% ไม่ส่งข้อมูลออกนอกเครื่อง และตรวจสอบสถานะโมเดลจริงก่อนแปล
   - **Gemini AI Mode (`GeminiTranslationEngine`)**: เชื่อมต่อผ่าน **Firebase AI Logic (`Firebase.ai`) + App Check** โดยไม่ต้องฝัง API Key ในซอร์สโค้ด พร้อมคำสั่งแปลเฉพาะทางมังงะ/มันฮวา (รักษาอารมณ์ตัวละคร สรรพนาม คำเลียนเสียง `[SFX]` และไม่แต่งประโยคเพิ่ม)
   - **Hybrid Mode (ค่าเริ่มต้น)**: อ่าน OCR บนเครื่องก่อน แล้วเลือกแปลออฟไลน์ก่อนหรือเน้น Gemini AI ตามที่ผู้ใช้ตั้งค่า พร้อม Bounded LRU Cache และระบบป้องกันการส่งข้อมูลเมื่อผู้ใช้ปิด AI
2. **ระบบจับภาพหน้าจอ (`MediaProjection` + `ScreenCaptureForegroundService`)**
   - ทำงานผ่าน Foreground Service (`foregroundServiceType="mediaProjection"`) พร้อมแถบแจ้งเตือนที่มีปุ่ม **หยุดทันที** และ **พัก/ทำต่อ**
   - จัดการการหมุนหน้าจอ/เปลี่ยนขนาดหน้าต่าง ปล่อยทรัพยากร `VirtualDisplay`, `ImageReader`, `Bitmap` อย่างปลอดภัย และเคารพ `FLAG_SECURE`
   - ไม่บันทึกภาพหน้าจอถาวรเป็นค่าเริ่มต้น
3. **ระบบตรวจจับหยุดเลื่อนอัตโนมัติ (`AutoScrollAccessibilityService` - ตัวเลือกเสริม)**
   - ฟังเฉพาะ Event การเลื่อนหน้าจอจริง (`TYPE_VIEW_SCROLLED`) โดยตั้งค่า `canRetrieveWindowContent="false"` และกรอง Event จากแอปตัวเอง, SystemUI รวมถึง Event พิกัดซ้ำ
   - มี Debounce ปรับได้ 1.0 – 5.0 วินาที (ค่าเริ่มต้น 1.5 วินาที), Cooldown และ dHash Screen Deduplication เพื่อไม่แปลหน้าเดิมซ้ำ
   - ใช้งานโหมดกดแปลเอง (Manual) ได้สมบูรณ์แม้ไม่ได้เปิด Accessibility
4. **ตัวจัดการบริบทมังงะ (`MangaContextProcessor`)**
   - จัดกลุ่มกล่องข้อความตามบอลลูนคำพูด ไม่รวมข้อความข้ามพาเนล
   - เรียงลำดับอ่านแบบ **ขวาไปซ้าย (RTL)** สำหรับมังงะญี่ปุ่น และ **บนลงล่าง/ซ้ายไปขวา (LTR)** สำหรับมันฮวาเกาหลี
   - ตรวจจับคำเลียนเสียง (SFX) และเก็บความจำบริบท 3–6 บรรทัดล่าสุดสำหรับความต่อเนื่องของชื่อตัวละคร

---

## การตั้งค่า Firebase AI Logic และ App Check

> **หมายเหตุสำคัญ:** แอปสามารถใช้งาน **Offline Mode** (ML Kit OCR + Language ID + On-Device Translation) ได้เต็มรูปแบบแม้ยังไม่ได้ตั้งค่า Firebase

หากต้องการเปิดใช้งาน **Gemini AI Mode (`gemini-3.8-flash`)**:

1. **Android App Package Name:** ลงทะเบียนแอปใน Firebase Console ด้วย `package_name` = `com.eteroverseer.screentranslator`
2. **เปิดใช้งาน Firebase AI Logic** และ **Firebase App Check** ในโปรเจกต์ Firebase ของคุณ
3. **เพิ่มไฟล์ `app/google-services.json`:** ดาวน์โหลดจาก Firebase Console แล้ววางในโฟลเดอร์ `app/` *(ไฟล์นี้ถูกยกเว้นใน `.gitignore` เพื่อไม่ให้คอมมิตข้อมูลโปรเจกต์ขึ้น Repository)*
4. **App Check สำหรับ Debug Build (`BuildConfig.DEBUG`):**
   - `AppCheckInitializer` จะติดตั้ง `DebugAppCheckProviderFactory` โดยอัตโนมัติ (หรือรับ `FIREBASE_APPCHECK_DEBUG_TOKEN` ผ่าน Launch Intent Extra ในสภาพแวดล้อมทดสอบโดยไม่บันทึกค่า Token ลง Log)
   - นำ Debug Token ที่ลงทะเบียนไว้ใน Firebase App Check Console มาใช้งานทดสอบได้ทันที
5. **App Check สำหรับ Release Build (`!BuildConfig.DEBUG`):**
   - กำหนด String Resource `recaptcha_site_key` (จาก reCAPTCHA Enterprise / Firebase App Check) ก่อนคอมไพล์ Release APK เพื่อให้ `RecaptchaAppCheckProviderFactory` ทำงานได้
6. **กรณีไม่พบการตั้งค่า Firebase:** แอปจะไม่แครชและจะแสดงสถานะชัดเจนในหน้าหลัก/หน้าความเป็นส่วนตัวว่ายังไม่ได้ตั้งค่า Firebase โดยยังคงให้ใช้งานโหมดออฟไลน์ได้ตามปกติ

---

## สิทธิ์ที่แอปใช้งาน

- **`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION`**: สำหรับบริการจับภาพหน้าจอ `MediaProjection`
- **`SYSTEM_ALERT_WINDOW`**: สำหรับแสดงปุ่มลอยและหน้าต่างคำแปลทับแอปอื่น (`WindowManager`)
- **`BIND_ACCESSIBILITY_SERVICE`** *(ตัวเลือกเสริม)*: สำหรับตรวจจับจังหวะหยุดเลื่อนหน้าจอในโหมด Auto
- **`POST_NOTIFICATIONS`** *(Android 13+)*: สำหรับแสดงการแจ้งเตือนสถานะจับภาพและปุ่มหยุดด่วน
- **`INTERNET` / `ACCESS_NETWORK_STATE`**: สำหรับดาวน์โหลดโมเดล ML Kit ครั้งแรก และเรียกใช้ Gemini AI เมื่อผู้ใช้เปิดใช้งาน

---

## ข้อจำกัดที่ควรทราบ

- หน้าจอแอปที่เปิด **`FLAG_SECURE`** (เช่น แอปธนาคารหรือแอปสตรีมมิ่งบางชนิด) ระบบ Android จะไม่อนุญาตให้ `MediaProjection` อ่านภาพได้
- การแปลภาษาไทยแบบ **Offline (ML Kit)** จำเป็นต้องดาวน์โหลดแพ็กเกจภาษาต้นทาง (`JA`/`KO`/`EN`) และภาษาไทย (`TH`) ลงเครื่องก่อน (~30 MB ต่อภาษา) จึงจะใช้งานแบบไร้เน็ตได้
- ข้อความแนวตั้งที่เขียนด้วยฟอนต์ศิลป์หรือสติกเกอร์ทับลายเส้นหนาในมังงะ อาจอ่านคลาดเคลื่อนได้บ้าง ผู้ใช้สามารถแก้ไขข้อความ OCR ในหน้า **ควบคุม** ก่อนกดแปลใหม่ได้ทันที

---

## ขั้นตอนการสร้างไฟล์ APK (Build & Release)

> **ข้อควรระวังสำหรับ Production APK:** หลีกเลี่ยงการใช้ Debug Keystore สำหรับการติดตั้งจริงระยะยาว ให้กำหนด Upload/Release Keystore ผ่านตัวแปรสภาพแวดล้อมของระบบบิลด์ (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`)

1. ตรวจสอบว่าได้วาง `app/google-services.json` ของคุณแล้ว (กรณีต้องการใช้ Gemini AI)
2. รันชุดทดสอบ Unit & Robolectric:
   ```bash
   gradle :app:testDebugUnitTest
   ```
   *(หรือ `./gradlew :app:testDebugUnitTest` บนเครื่องที่มี Gradle Wrapper)*
3. สร้าง Debug APK สำหรับทดสอบบนอุปกรณ์ (เช่น RedMagic 11S Pro):
   ```bash
   gradle :app:assembleDebug
   ```
   ไฟล์ APK จะอยู่ที่ `app/build/outputs/apk/debug/app-debug.apk`
4. สร้าง Release APK (เมื่อกำหนด Release Keystore แล้ว):
   ```bash
   gradle :app:assembleRelease
   ```
   ไฟล์ APK จะอยู่ที่ `app/build/outputs/apk/release/app-release.apk`

