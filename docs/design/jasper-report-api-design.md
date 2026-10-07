# เอกสารออกแบบ: Jasper Report API (JRXML → PDF)

> สถานะ: **Phase 1 implemented** — 2026-10-06 (รายละเอียดที่ต่างจากแผนและผลการทดสอบอยู่ที่ [§18](#18-ผลการ-implement-phase-1)); วิธีใช้งานอยู่ใน [README](../../README.md)
> ต่อยอดจาก: [`jasperreports-pdf`](../../jasperreports-pdf) (ดูผลเปรียบเทียบใน [project-comparison](../project-comparison/jasperreports-generater-vs-jasperreports-pdf.md))
> font extension ของโปรเจกต์นี้: `jasper-report-api-thai-fonts:2.0.0` (TH Sarabun New)

สัญลักษณ์ในเอกสาร: ✅ = ตรวจแล้วจาก artifact/โค้ดจริง, ⚠️ = ยังต้องยืนยันใน PoC

---

## 1. เป้าหมายและขอบเขต

**เป้าหมาย**
- บริการ REST ภายใน รับ "รายงานที่จะใช้" + parameter แล้วคืนไฟล์ (PDF เป็นหลัก)
- JRXML มาได้ 2 แหล่ง: **โฟลเดอร์ที่ mount ไว้** หรือ **S3-compatible storage** (เช่น rustfs)
- รูปแบบ request เข้ากันได้กับ `jasperreports-pdf` เพื่อให้ client เดิมย้ายมาได้ง่าย
- รองรับฟอนต์ไทย (TH Sarabun New พร้อม license), **barcode** และ **QR code**
- รองรับหลาย tenant (หลายโรงพยาบาล) แต่ไม่บังคับ — deployment แบบหนึ่ง instance ต่อหนึ่งที่ใช้ tenant `default` ได้เลย

**นอกขอบเขต (ตอนนี้)**
- อัปโหลด/แก้ JRXML ผ่าน API (แก้ที่ storage/โฟลเดอร์โดยตรง)
- เปิดให้บริการนอกเครือข่ายภายใน
- UI ออกแบบรายงาน

**สมมติฐาน**: ผู้เรียกเป็น **ระบบภายในที่เชื่อถือได้** (ข้อตัดสินใจ D2)

---

## 2. สรุปข้อตัดสินใจ

| # | หัวข้อ | ข้อตัดสินใจ |
|---|---|---|
| D1 | การเชื่อมต่อ DB | API ถือ connection ไว้เอง (named datasource + HikariCP pool) **client ไม่ส่ง JDBC URL/credential** |
| D2 | ระดับความเชื่อถือ client | ระบบภายในที่เชื่อถือได้ → ป้องกันด้วยเครือข่าย + API key (โหมด `required` / `optional` / `disabled` ตั้งได้ ดู §12.1), ไม่ทำ authorization ราย report |
| D3 | Multi-tenant | รองรับตั้งแต่แรก แต่ **optional** — ไม่ส่ง tenant = `default` |
| D4 | การเลือก datasource | ลำดับ: **request > property ใน JRXML > default ของ tenant**; ถ้า request ไม่ตรงกับ JRXML → **request ชนะ** + log WARN + metric (ปิดได้ด้วย config) |
| D5 | แหล่ง JRXML | path ในโฟลเดอร์ที่ mount, `s3://bucket/key`, หรือ `https://` เฉพาะ host ใน allowlist (ค่าเริ่มต้นปิด) |
| D6 | Cache/version | ใช้ ETag (S3) / mtime+size (ไฟล์) ของต้นทางเป็น version; เก็บ `JasperReport` ใน memory; ไม่ใช้ `modified_at` จาก client (รับแต่ไม่สนใจ) |
| D7 | Subreport | ทั้งแบบใหม่ `SUBREPORTS` (Map ของ report ที่ compile แล้ว) และแบบเดิม `SUBREPORT_DIR` |
| D8 | Parameter | แปลงตามชนิดที่ประกาศใน JRXML; `type` จาก client เป็น optional (เข้ากันได้กับ pdf) |
| D9 | Engine | **JasperReports 7.0.8** (ยืนยันแล้ว 2026-10-06) — JRXML รูปแบบ 6.x ต้องแปลงก่อนใช้ (ดู §13, Q1) |
| D10 | ฟอนต์ | ใช้ font extension **TH Sarabun New เท่านั้น** (GPL-2.0+ พร้อม font embedding exception) jar `jasper-report-api-thai-fonts`; ไม่รวม TH SarabunPSK และฟอนต์ที่ไม่มีเอกสาร license |
| D11 | Barcode/QR | ใช้ component ของ JasperReports (`jasperreports-barcode4j`: Code128/39, EAN, PDF417, DataMatrix, QRCode) แทนการใช้ฟอนต์ barcode |
| D12 | ความทนทาน | จำกัด concurrency, timeout, query timeout, จำนวนหน้า, virtualizer สำหรับรายงานใหญ่; โหมด async สำหรับงานยาว (phase 2) |

---

## 3. สถาปัตยกรรม

```
                         ┌───────────────────────── jasper-report-api ─────────────────────────┐
 client (ภายใน)          │                                                                       │
  POST /render  ───────► │  Auth(API key) → Validate → Resolve tenant/datasource                 │
  X-Tenant-Id (opt)      │        │                                                              │
                         │        ▼                                                              │
                         │  ReportSourceResolver ──► local root (/app/reports, read-only mount)  │
                         │   (path | s3:// | https)──► S3 client (rustfs, path-style) ──────────┼──► rustfs
                         │        │                                                              │
                         │        ▼                                                              │
                         │  CompiledReportCache (Caffeine, key = source + version, single-flight) │
                         │        │                                                              │
                         │        ▼                                                              │
                         │  ParameterBinder (ชนิดจาก JRXML) + system params                       │
                         │        │                                                              │
                         │        ▼                                                              │
                         │  RenderExecutor (semaphore, governors, virtualizer)                   │
                         │        │  Connection จาก DataSourceRegistry[tenant][name] (Hikari) ───┼──► PostgreSQL
                         │        ▼                                                              │
                         │  Exporter (pdf / xlsx / csv) → stream response                        │
                         └───────────────────────────────────────────────────────────────────────┘
```

ทุก instance มี cache ของตัวเอง ไม่ต้อง share volume ระหว่าง container (ต่างจาก pdf ที่แนะนำให้ share `jaspers/`)

---

## 4. API

### 4.1 Endpoints

| Method | Path | หน้าที่ | Phase |
|---|---|---|---|
| POST | `/api/v1/reports/render` | สร้างรายงานแบบ sync คืนไฟล์ | 1 |
| POST | `/api/v1/jasper/generate` | alias ของ `render` เพื่อให้ client ของ `jasperreports-pdf` ใช้ต่อได้ | 1 |
| POST | `/api/v1/reports/validate` | compile อย่างเดียว คืนรายการ parameter (ชื่อ/ชนิด), datasource ที่ resolve ได้, subreport, คำเตือน (เช่น พบ `$P!{}`) — สำหรับคนทำรายงาน | 1 |
| POST | `/api/v1/reports/jobs` | สร้างงาน async → `202 {jobId}` | 2 |
| GET | `/api/v1/reports/jobs/{id}` | สถานะงาน + pre-signed URL ของผลลัพธ์ | 2 |
| GET | `/api/healthz` | เข้ากันได้กับของเดิม | 1 |
| GET | `/actuator/health/{liveness,readiness}` | readiness ตรวจ datasource และ storage | 1 |
| GET | `/actuator/prometheus` | metrics | 1 |

### 4.2 Request

```json
{
  "tenant": "hospital-a",
  "datasource": "opd",
  "mainReport": { "name": "medical_certificate", "url": "s3://reports/opd/medical_certificate/main.jrxml" },
  "subReports": [],
  "parameters": [
    { "name": "visit_id", "value": "1234" },
    { "name": "print_date", "type": "timestamp", "value": "2026-10-06T10:30:00+07:00" },
    { "name": "item_ids", "value": [1, 2, 3] }
  ],
  "format": "pdf",
  "fileName": "ใบรับรองแพทย์"
}
```

| ฟิลด์ | บังคับ | หมายเหตุ |
|---|---|---|
| `tenant` | ไม่ | หรือส่งผ่าน header `X-Tenant-Id` (header ชนะ); ไม่ส่ง = `default` |
| `datasource` | ไม่ | ชื่อเชิงตรรกะ เช่น `opd`, `ipd` (D4) |
| `mainReport.url` | ใช่ | ดู §5.1 |
| `mainReport.name` | ไม่ | ใช้ตั้งชื่อไฟล์ผลลัพธ์ถ้าไม่ส่ง `fileName`; ไม่ส่ง = ชื่อไฟล์ JRXML |
| `mainReport.modified_at` | ไม่ | **รับแต่ไม่ใช้** (เข้ากันได้กับ pdf) |
| `subReports[]` | ไม่ | ใช้กับรายงานแบบ `SUBREPORT_DIR` เท่านั้น (§5.3) |
| `parameters[].type` | ไม่ | ใช้เมื่อ JRXML ประกาศชนิดกว้าง (`Object`, `Collection`) หรือเพื่อความเข้ากันได้ |
| `parameters[].value` | ใช่ | รับได้ทั้ง string (แบบเดิม), number, boolean, array, `null` |
| `format` | ไม่ | `pdf` (ค่าเริ่มต้น), `xlsx`, `csv` |
| `locale` | ไม่ | ภาษาของรายงาน (`th`, `en`, `en-US`, ...) — ชนะ `report.locale` ใน JRXML; ดู [§19](#19-หลายภาษา-i18n) |

Header ที่เกี่ยวข้อง: `X-API-Key` (บังคับหรือไม่ขึ้นกับโหมดใน §12.1), `X-Request-Id` (ไม่บังคับ — ไม่ส่งจะสร้างให้), `sentry-trace`/`traceparent` (ส่งต่อ trace)

### 4.3 Response

- สำเร็จ: `200` + body เป็นไฟล์, `Content-Type` ตาม format, `Content-Disposition: inline; filename*=UTF-8''<ชื่อ>` (รองรับชื่อไทย), header `X-Report-Version` (version ของต้นทางที่ใช้จริง), `X-Request-Id`
- ผิดพลาด: `application/problem+json` (RFC 9457)

```json
{ "type": "about:blank", "title": "Unknown datasource", "status": 400,
  "code": "DATASOURCE_UNKNOWN", "detail": "datasource 'opdx' is not configured for tenant 'default'",
  "requestId": "..." }
```

| code | HTTP | เมื่อไร |
|---|---|---|
| `API_KEY_MISSING` | 401 | โหมด `required` แต่ไม่ส่ง `X-API-Key` |
| `API_KEY_INVALID` | 401 | ส่ง key ที่ไม่รู้จัก/ถูกปิด/หมดอายุ (ทุกโหมดยกเว้น `disabled`) |
| `VALIDATION_FAILED` | 400 | body ไม่ครบ/ผิดรูปแบบ |
| `TENANT_UNKNOWN` / `DATASOURCE_UNKNOWN` | 400 | ไม่มีใน config |
| `DATASOURCE_UNRESOLVED` | 400 | ไม่มีทั้งใน request, JRXML และ default |
| `DATASOURCE_OVERRIDE_DENIED` | 400 | request ไม่ตรงกับ JRXML และปิด override ไว้ |
| `SOURCE_NOT_ALLOWED` | 400 | scheme/bucket/host/path อยู่นอก allowlist หรือ path traversal |
| `PARAMETER_INVALID` | 400 | แปลงค่าไม่ได้ตามชนิด (ระบุชื่อ parameter) |
| `REPORT_NOT_FOUND` | 404 | หาไฟล์ไม่เจอ |
| `REPORT_COMPILE_FAILED` | 422 | JRXML compile ไม่ผ่าน |
| `RENDER_BUSY` | 503 + `Retry-After` | รอคิวเกินเวลา |
| `RENDER_TIMEOUT` | 504 | fill เกิน timeout |
| `PAGE_LIMIT_EXCEEDED` | 422 | เกินจำนวนหน้าที่กำหนด |
| `DATABASE_ERROR` | 502 | SQL/connection ผิดพลาด |
| `STORAGE_ERROR` | 502 | อ่าน S3 ไม่ได้ |
| `INTERNAL_ERROR` | 500 | อื่นๆ |

---

## 5. แหล่ง JRXML, subreport และรูปภาพ

### 5.1 การตีความ `mainReport.url`

| รูปแบบ | ตัวอย่าง | อ่านจาก |
|---|---|---|
| ไม่มี scheme | `opd/medical_certificate/main.jrxml`, `test.jrxml` | `report.sources.local.root` (เช่น `/app/reports`) — เข้ากับโฟลเดอร์ `jrxmls/` ของ pdf |
| `s3://` | `s3://reports/opd/medical_certificate/main.jrxml` | S3 SDK ด้วย credential ของ server, เฉพาะ bucket ใน `allowed-buckets` |
| `https://` | `https://files.internal/rpt/a.jrxml` | เฉพาะ host ใน `allowed-hosts` (ค่าเริ่มต้นว่าง = ปิด), ไม่ตาม redirect, จำกัดขนาด/เวลา |

ข้อกำหนดความปลอดภัย (แม้ client เชื่อถือได้ เพราะ JRXML = โค้ดที่ถูกรัน):
- local path: `root.resolve(path).normalize()` ต้องขึ้นต้นด้วย `root`, ห้าม symlink ออกนอก root, ต้องลงท้าย `.jrxml`
- S3: อนุญาตเฉพาะ bucket/prefix ที่กำหนด; bucket ของรายงานให้สิทธิ์เขียนเฉพาะคนทำรายงาน
- tenant สามารถมี `local.root`/`s3.prefix` ของตัวเองได้ (ไม่บังคับ)

### 5.2 Report bundle

ถือว่า **โฟลเดอร์ (หรือ S3 prefix) ที่ main JRXML อยู่ = bundle ของรายงาน** ซึ่งรวม subreport และรูป:

```
reports/opd/medical_certificate/
├── main.jrxml
├── sub_diag.jrxml
└── assets/logo.png
```

- **version ของ bundle**: S3 → hash ของ (key, ETag) ทุก object ใน prefix จาก `ListObjectsV2` ครั้งเดียว; local → hash ของ (path, mtime, size) ของไฟล์ใน bundle
- ตรวจ version ไม่บ่อยกว่า `cache.check-interval` (เช่น 10 วินาที) ต่อ bundle
- ทั้ง render ใช้ bundle version เดียว (pin ตอนเริ่ม) — ป้องกันการผสมไฟล์สองเวอร์ชันระหว่างที่มีคนแก้ไฟล์
- โฟลเดอร์แบบ flat ของ pdf (`jrxmls/*.jrxml` หลายรายงานรวมกัน) ใช้ได้ แต่ **version คิดจากทั้งโฟลเดอร์** (ไฟล์ใดเปลี่ยน รายงานทุกตัวในโฟลเดอร์ compile ใหม่) — แนะนำให้แยกโฟลเดอร์ต่อรายงาน (implement แล้วตามนี้ ไม่ได้คิดเฉพาะไฟล์ที่ถูกใช้ตามที่ร่างไว้ตอนแรก)

### 5.3 Subreport — รองรับ 2 แบบ

| แบบ | JRXML เขียนว่า | API ทำอะไร |
|---|---|---|
| **ใหม่ (แนะนำ)** ✅ | `<parameter name="SUBREPORTS" class="java.util.Map"/>` และ expression `((JasperReport)$P{SUBREPORTS}.get("sub_diag"))` | ส่ง Map แบบ lazy: `get("x")` จะ compile `x.jrxml` ใน bundle ครั้งแรกแล้ว cache ไว้; ไม่ต้องเขียน `.jasper` ลงดิสก์; ไม่ต้องให้ client ระบุ `subReports` |
| **เดิม** — แบบ pdf | `$P{SUBREPORT_DIR} + "sub_diag.jasper"` | compile subreport (จาก `subReports[]` หรือทุก `*.jrxml` ใน bundle ถ้าไม่ระบุ) เขียนเป็น `.jasper` ลง `cache/<bundle-hash>/<version>/` แบบ temp + atomic rename แล้วตั้ง `SUBREPORT_DIR` ไปที่โฟลเดอร์นั้น; ลบเวอร์ชันเก่าเป็นระยะ |

### 5.4 รูปภาพ

- `IMAGE_DIR` (แบบ pdf) และ `REPORT_ASSETS_DIR` ชี้ไปที่โฟลเดอร์ assets ของ bundle (สำหรับ S3 จะ sync ลง `cache/<bundle-hash>/<version>/assets/` ก่อน)
- `IMAGE_DIR` ส่วนกลาง (เช่น โลโก้โรงพยาบาลของ tenant) ตั้งได้ใน config ของ tenant

### 5.5 System parameters (client ส่งมาจะถูกตัดทิ้ง + WARN)

`SUBREPORTS`, `SUBREPORT_DIR`, `IMAGE_DIR`, `REPORT_ASSETS_DIR`, `REPORT_CONNECTION`, `REPORT_VIRTUALIZER`, `REPORT_LOCALE`, `REPORT_TIME_ZONE` ฯลฯ — API ตั้งให้เอง (`REPORT_TIME_ZONE` = `Asia/Bangkok`; `REPORT_LOCALE` **ไม่ตั้ง** เว้นแต่กำหนด `report.locale` เพราะ `th_TH` ทำให้ pattern วันที่แสดงปี พ.ศ.)

---

## 6. Datasource และ tenant

### 6.1 Registry จาก config

```yaml
tenants:
  default:
    default-datasource: opd
    datasources:
      opd: { url: jdbc:postgresql://db:5432/hosv4, username: report_ro, password: ${OPD_DB_PASSWORD}, pool-size: 5 }
      ipd: { url: jdbc:postgresql://db:5432/ipd,   username: report_ro, password: ${IPD_DB_PASSWORD}, pool-size: 3 }
  hospital-a:
    default-datasource: opd
    datasources:
      opd: { url: jdbc:postgresql://10.0.1.10:5432/hosv4, username: report_ro, password: ${HA_OPD_DB_PASSWORD} }
```

- สร้าง HikariDataSource ต่อ (tenant, ชื่อ) แบบ lazy และปิดเมื่อไม่ได้ใช้นาน (เมื่อมี tenant มาก)
- ใช้ **DB user แบบ read-only**; ตั้ง `statement_timeout` ที่ DB/connection
- deployment หนึ่งที่ต่อหนึ่ง instance ตั้งแค่ `tenants.default` (ข้อตัดสินใจ D3)

### 6.2 การ resolve datasource (D4)

```
tenant   = header X-Tenant-Id ?: body.tenant ?: "default"         → ไม่รู้จัก → 400 TENANT_UNKNOWN
fromReq  = body.datasource
fromFile = jasperReport.getProperty("report.datasource")            ← property ใน JRXML
name     = fromReq ?: fromFile ?: tenant.default-datasource        → ไม่มี → 400 DATASOURCE_UNRESOLVED

ถ้า fromReq != null && fromFile != null && fromReq != fromFile:
    ถ้า report.datasource.allow-request-override = true (ค่าเริ่มต้น)
        → ใช้ fromReq, log WARN {report, tenant, fromReq, fromFile}, metric report_datasource_override_total++
    ไม่เช่นนั้น → 400 DATASOURCE_OVERRIDE_DENIED
```

การประกาศใน JRXML (ชื่อเชิงตรรกะ ไม่ใช่ DB จริง):

```xml
<jasperReport name="medical_certificate" ...>
    <property name="report.datasource" value="opd"/>
```

เหตุผลที่ให้ request ชนะ: client เชื่อถือได้ (D2) และช่วงย้ายจาก pdf ที่ JRXML ส่วนใหญ่ยังไม่มี property นี้ ส่วน WARN + metric ช่วยหารายงานที่ตั้งค่าไม่ตรงกันเพื่อแก้ต่อไป

---

## 7. Compile และ cache

- key = `(tenant, source URI, bundle version)` → `JasperReport` ใน Caffeine (จำกัดจำนวน/ขนาด)
- **single-flight**: request พร้อมกันที่ key เดียวกันรอ compile ครั้งเดียว (`ConcurrentHashMap<Key, CompletableFuture<JasperReport>>`) — แก้ race ของ pdf ที่ compile/เขียนไฟล์เดียวกันพร้อมกัน
- compile ไม่ผ่าน → cache ผลผิดพลาดสั้นๆ (เช่น 5 วินาที) กันการ compile ซ้ำถี่ๆ
- ไม่ persist `.jasper` ยกเว้น subreport แบบ `SUBREPORT_DIR` (§5.3) — เลี่ยงปัญหา `.jasper` ข้ามเวอร์ชัน JasperReports
- Compiler ✅ (มี module ใน JR 7.0.8):
  - `language="groovy"` ต้องมี `jasperreports-groovy` (เคยเจอ JRXML ที่ไม่ระบุ language แล้ว compile ไม่ผ่านบน JRE-only image)
  - `language="java"` บน JRE image ต้องมี `jasperreports-jdt`
- ตอน compile ให้ lint: แจ้งเตือนถ้า query มี `$P!{...}` (ต่อ string เข้า SQL = เสี่ยง SQL injection) — แสดงใน `/validate` และ log WARN

---

## 8. Parameter

### 8.1 การแปลงค่า (D8)

ชนิดเป้าหมาย = `valueClass` ของ parameter ใน JRXML (`jasperReport.getParameters()` ที่ไม่ใช่ system):

| ชนิดใน JRXML | รับค่า |
|---|---|
| `String` | string |
| `Integer`/`Long`/`Short`/`BigDecimal`/`Double`/`Float` | number หรือ string ตัวเลข |
| `Boolean` | boolean หรือ `"true"/"false"` |
| `java.util.Date`, `java.sql.Date` | `yyyy-MM-dd` |
| `java.sql.Time` | `HH:mm:ss` (ไม่มี offset = `report.timezone`) หรือ `HH:mm:ssZ` / `HH:mm:ss+07:00` |
| `java.sql.Timestamp` | ISO-8601/RFC 3339 มีหรือไม่มี offset (ไม่มี = `report.timezone`) |
| `java.util.Collection`/`List` | JSON array หรือ string คั่นด้วย `,`; ชนิดสมาชิกจาก `nestedType` ใน JRXML หรือ `type` (`array_int`, `array_str`) |
| `Object` หรือชนิดอื่น | ใช้ `type` จาก client (`string, integer, number, date, time, timestamp, bool, array_str, array_int` เหมือน pdf); ไม่มี `type` = string |

- รับรูปแบบเวลาทั้งแบบ generater (ไม่มี offset) และแบบ pdf (มี `Z`) เพื่อให้ client ทั้งสองแบบย้ายมาได้
- แปลงไม่ได้ → `400 PARAMETER_INVALID` ระบุชื่อ (ไม่คืน `null` เงียบๆ แบบเดิม)
- parameter ที่ไม่ได้ประกาศใน JRXML: ค่าเริ่มต้น **ตัดทิ้ง + WARN** (`report.parameters.strict=true` → 400)
- `null` ได้ — แก้ NPE ตอน log ของเดิม

### 8.2 Logging

log เฉพาะ **ชื่อและชนิด** ของ parameter ไม่ log ค่า (อาจเป็น HN/เลขบัตรประชาชน)

---

## 9. ฟอนต์

### 9.1 สิ่งที่พบ

| แหล่ง | ฟอนต์ | เอกสาร license ใน jar |
|---|---|---|
| `jasper-report-api-thai-fonts:2.0.0` ✅ | TH Sarabun New (Regular/Bold/Italic/BoldItalic) | มี: `META-INF/LICENSES/GPL-2.0-or-later.txt`, `TH-Sarabun-New-Font-License-Metadata.txt`, `META-INF/NOTICE` |
| pdf `hosos-jasperreports-font-1.1.1` ✅ | TH Sarabun New, **TH SarabunPSK**, RSU, RSU TEXT, Arthit, AngsanaDSE, WinAmaraPura, Myanmar Text (`mmrtext.ttf`), **IDAutomationHC39M** (ฟอนต์ barcode Code39) | **ไม่มี** |

ข้อมูลฟอนต์:
- TH Sarabun New: **GPL-2.0-or-later พร้อม font embedding exception** (ฝังลง PDF แล้ว PDF ไม่ต้องเป็น GPL) — ข้อความ license มาจาก name table ของ TTF เอง
- TH SarabunPSK ถูกตัดออก เพราะ license ของ DIP&SIPA จำกัดการขายแยกและต้องแจ้งก่อนดัดแปลง
- การอนุมัติแจกจ่าย image **ผูกกับ SHA-256 ของ font jar** (`libs/font-jar.sha256`)

### 9.2 ข้อตัดสินใจ (D10)

1. **ใช้ jar `jasper-report-api-thai-fonts:2.0.0` ของโปรเจกต์นี้ (ตรวจ SHA-256)** — บรรจุไฟล์ TTF TH Sarabun New เดิมโดยไม่แก้ไข ต้องขออนุมัติแจกจ่ายตาม SHA-256 นี้
   - pin ด้วย lock file + script ตรวจ SHA-256 ตอน build (`libs/font-jar.sha256` + `sha256sum -c` ใน Dockerfile)
   - ใส่ jar เป็น dependency ปกติจาก local maven repo (ไม่ใช้ `system` scope แบบ pdf)
2. font-families ใช้ `pdfEncoding=Identity-H`, `pdfEmbedded=true` (มีอยู่แล้วใน jar) → ภาษาไทยฝังใน PDF ถูกต้อง เปิดได้ทุกเครื่อง
3. **ไม่รวม** TH SarabunPSK และฟอนต์อื่นใน `hosos-jasperreports-font-1.1.1` จนกว่าจะมีเอกสาร license และการอนุมัติ — `mmrtext.ttf` น่าจะเป็นฟอนต์ที่มากับ Windows (Myanmar Text) ส่วน IDAutomationHC39M เป็นฟอนต์ของผู้ขายเชิงพาณิชย์; ถ้าต้องใช้ในอนาคต ให้แยกเป็น jar ต่างหาก (font pack) พร้อม `META-INF/LICENSES` และการอนุมัติของตัวเอง
4. ใส่ `jasperreports-fonts` (DejaVu) ไว้เป็นฟอนต์ละติน/ค่าเริ่มต้นสำหรับ element ที่ไม่ระบุ `fontName` (DejaVu ใช้ license แบบ free)
5. คงค่า `net.sf.jasperreports.awt.ignore.missing.font=false` ให้รายงานที่อ้างฟอนต์ที่ไม่มี **fail ทันที** ไม่ใช่แสดงผิดเงียบๆ — และให้ `/validate` แจ้งชื่อฟอนต์ที่ JRXML อ้างแต่ไม่มีใน extension
6. JRXML เดิมที่ใช้ `fontName="TH SarabunPSK"`:
   - **แนะนำ**: เปลี่ยนเป็น `TH Sarabun New` (layout และจำนวนหน้าไม่เปลี่ยน)
   - ทางเลือกชั่วคราว: font extension เล็กๆ ที่ map ชื่อ family `TH SarabunPSK` → ไฟล์ TH Sarabun New (ไม่แก้ไฟล์ TTF) เปิดด้วย config — รูปตัวอักษรจะต่างจากต้นฉบับเล็กน้อย ต้องบันทึกเป็น deviation

### 9.3 Docker image

- ใช้ `eclipse-temurin:21-jre-jammy` (ไม่ต้อง `apk add ttf-dejavu` แบบ alpine เดิม เพราะฟอนต์มาจาก font extension)
- ✅ ตรวจแล้ว: image `eclipse-temurin:21-jre-noble` มี `fontconfig`, `libfreetype6` และ `curl` อยู่แล้ว barcode/QR พร้อมตัวอักษรใต้แท่งและฟอนต์ไทย render ถูกต้องใน container จริง (ไม่ต้อง `apt-get install` เพิ่ม)

---

## 10. Barcode และ QR code (D11)

### 10.1 Dependency ที่ต้องเพิ่ม (JasperReports 7.0.8)

| Artifact | ทำอะไร | ตรวจแล้ว |
|---|---|---|
| `net.sf.jasperreports:jasperreports-barcode4j:7.0.8` | component `barcode4j:Code128`, `Code39`, `EAN13`, `EAN8`, `EAN128`, `Interleaved2Of5`, `Codabar`, `UPCA`, `UPCE`, `PDF417`, `DataMatrix`, **`QRCode`** ฯลฯ; ดึง `barcode4j:2.1` และ `zxing core:3.4.0` มาเอง | ✅ จาก pom และรายชื่อ class ใน jar |
| `net.sf.jasperreports:jasperreports-barbecue:7.0.8` | component `barbecue` (ใส่เฉพาะถ้า JRXML เดิมใช้) | ✅ มีใน Maven Central |
| Batik (`batik-bridge` ฯลฯ 1.19) | วาด barcode แบบ SVG ลง PDF | ✅ เป็น compile dependency ของ `jasperreports:7.0.8` อยู่แล้ว ไม่ต้องเพิ่ม |
| `com.google.zxing:javase` | **เฉพาะ** ถ้า JRXML เดิมเรียก ZXing ตรงใน expression (เช่น `MatrixToImageWriter`) — pdf มี `core`/`javase` 3.5.0 | ต้องจัดเวอร์ชัน `zxing core` ให้ตรงกันด้วย `dependencyManagement` ⚠️ |
| `hospital-os-utils` | **เฉพาะ** ถ้า JRXML เดิมเรียกคลาส `com.hosos.util.*` (pdf ใส่ไว้ — มี package `image`, `general`, `datetime` ฯลฯ) | ตรวจด้วย `/validate` ⚠️ |

ค่าเริ่มต้นของ JR 7.0.8 ✅ (`default.jasperreports.properties`):
- `net.sf.jasperreports.components.barcode4j.image.producer=svg` (คมชัดทุกขนาด) — เปลี่ยนเป็น `image` (raster 300 dpi) ได้ถ้า SVG มีปัญหา
- `net.sf.jasperreports.components.qrcode.character.encoding=UTF-8` → **QR ใส่ภาษาไทยได้**; Code128/Code39 รองรับเฉพาะ ASCII

### 10.2 ตัวอย่าง JRXML (รูปแบบ JR 7)

```xml
<element kind="component" x="0" y="0" width="80" height="80">
    <component kind="barcode4j:QRCode" errorCorrectionLevel="M" margin="0">
        <codeExpression><![CDATA[$F{hn}]]></codeExpression>
    </component>
</element>
<element kind="component" x="100" y="0" width="200" height="40">
    <component kind="barcode4j:Code128">
        <codeExpression><![CDATA[$F{vn}]]></codeExpression>
    </component>
</element>
```

ชื่อ `kind` (`barcode4j:QRCode`, `barcode4j:Code128`) และ property `codeExpression`, `errorCorrectionLevel`, `margin` ✅ มาจาก class ใน jar; ⚠️ attribute อื่น (เช่น ตำแหน่งตัวอักษร) ให้สร้างด้วย Jaspersoft Studio 7 แล้วดู XML ที่ได้

### 10.3 ไม่แนะนำฟอนต์ barcode (IDAutomationHC39M)

- เป็นฟอนต์เชิงพาณิชย์และไม่มีเอกสาร license ใน jar
- ต้องเติม `*` เปิด/ปิด และตรวจ checksum เอง, อ่านด้วยเครื่องสแกนได้ไม่แน่นอนเมื่อย่อ/ขยาย
- → ย้ายรายงานที่ใช้ฟอนต์นี้ไปใช้ `barcode4j:Code39` (หรือ Code128 ที่หนาแน่นกว่า)

---

## 11. ความทนทานและทรัพยากร

| กลไก | ค่าเริ่มต้น (ปรับได้) | หมายเหตุ |
|---|---|---|
| จำนวน render พร้อมกัน | `limits.max-concurrent-renders: 4` | `Semaphore`; รอเกิน `limits.queue-wait: 10s` → 503 + `Retry-After` |
| เวลา fill | `limits.fill-timeout: 60s` | ใช้ governor ของ JasperReports (`net.sf.jasperreports.governor.timeout.*`) → 504 |
| จำนวนหน้า | `limits.max-pages: 500` | governor `net.sf.jasperreports.governor.max.pages.*` → 422 |
| query timeout | `limits.query-timeout: 30s` | `net.sf.jasperreports.jdbc.query.timeout` (JDBC query executer) + `statement_timeout` ของ PostgreSQL |
| หน่วยความจำ | virtualizer เมื่อเปิด `virtualizer.enabled` | `JRSwapFileVirtualizer` ลงดิสก์ชั่วคราว กัน OOM สำหรับรายงานหลายพันหน้า |
| ขนาด JRXML ที่ดึง | `sources.max-bytes: 5MB` | ทั้ง S3 และ https |
| Async (phase 2) | — | `POST /jobs` → เก็บผลใน bucket output (lifecycle ลบอัตโนมัติ เช่น 1 วัน) → pre-signed URL; สถานะงานเก็บใน memory ถ้า instance เดียว หรือใน DB ถ้าหลาย instance |

---

## 12. ความปลอดภัยและ observability

### 12.1 API key

**หน้าที่หลัก**: ระบุว่า "ใครเรียก" (สำหรับ log/metric/การไล่ปัญหา) และกันการเรียกโดยไม่ตั้งใจจากระบบอื่นในเครือข่ายเดียวกัน — ไม่ได้ใช้แบ่งสิทธิ์ราย report (D2)

**รูปแบบ key**: `jra_<random>` — `<random>` คือ 32 byte จาก CSPRNG เข้ารหัส base64url (ประมาณ 43 ตัวอักษร) prefix `jra_` ช่วยให้ secret scanner และคนอ่าน log รู้ว่าเป็น key ของระบบนี้

**การสร้าง** (ทำครั้งเดียวต่อ client, ผู้ดูแลระบบเป็นคนทำ):

```bash
KEY="jra_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
```

```bash
printf '%s' "$KEY" | shasum -a 256 | cut -d' ' -f1
```

(บน Linux ใช้ `sha256sum` แทน `shasum -a 256`; phase 1 จะมี `make api-key CLIENT=<id>` ที่ทำทั้งสองขั้นและพิมพ์ผลออกมา)

- **ตัว key จริง** ส่งให้ทีม client ผ่านช่องทางลับครั้งเดียว และเก็บใน secret/env ของ client — **ฝั่ง API ไม่เก็บ key จริง**
- **ฝั่ง API เก็บแค่ SHA-256** ใน config/secret (`report.security.api-keys[].sha256`)
- ใช้ SHA-256 ธรรมดาได้ (ไม่ต้อง bcrypt/argon2) เพราะ key สุ่ม 256 bit เดาไม่ได้ และทำให้ตรวจได้เร็วทุก request

**การตรวจ** (`OncePerRequestFilter` เดียว ไม่ต้องใช้ Spring Security ทั้งชุด):
1. อ่าน header `X-API-Key`
2. `sha256(key)` แล้วเทียบกับทุก hash ด้วย `MessageDigest.isEqual` (constant-time)
3. ผ่าน → ใส่ `clientId` ลง MDC/metric; ไม่ผ่าน → 401 `API_KEY_INVALID`
4. ไม่ตรวจ: `/api/healthz`, `/actuator/health/**` (ให้ Docker/LB เรียกได้); `/actuator/prometheus` ให้เปิดเฉพาะ network ของ monitoring

**Rotation**: ให้ client หนึ่งรายมีได้หลาย key → เพิ่ม key ใหม่ → client เปลี่ยนไปใช้ → ลบ key เก่า (ดูจาก metric ว่า key เก่าไม่ถูกใช้แล้ว) ปิดชั่วคราวได้ด้วย `enabled: false` และกำหนด `expires-at` ได้

**เป็น optional ได้** — มี 3 โหมด:

| `report.security.api-key.mode` | ไม่ส่ง key | ส่ง key ถูก | ส่ง key ผิด | ใช้เมื่อ |
|---|---|---|---|---|
| `required` (ค่าเริ่มต้นใน profile `prod`) | 401 | ผ่าน | 401 | production ปกติ |
| `optional` | ผ่าน ในชื่อ `anonymous` | ผ่าน | **401** | ช่วงย้าย client จาก pdf ที่ยังไม่ส่ง key |
| `disabled` | ผ่าน | ไม่ตรวจ | ไม่ตรวจ | dev/local หรือ deployment ที่มีชั้นอื่นคุมอยู่แล้ว (เช่น reverse proxy ทำ mTLS) |

ข้อกำหนดเพื่อไม่ให้ optional กลายเป็นช่องโหว่:
- โหมด `optional`: key ที่ส่งมาแต่ผิด **ต้องได้ 401 เสมอ** ไม่ลดเป็น `anonymous` (ไม่อย่างนั้น client ที่ตั้ง key ผิดจะไม่มีวันรู้)
- ทุกโหมดที่ไม่ใช่ `required` ต้องจำกัดเครือข่าย: compose bind แค่ `127.0.0.1` หรือ network ภายใน + firewall rule
- ตอน start log WARN ถ้าโหมดไม่ใช่ `required`; ถ้าโหมด `required` แต่ไม่มี key ใน config → **ไม่ยอม start**
- metric `report_requests_total{client="anonymous"}` ใช้ดูว่า client ไหนยังไม่ส่ง key ก่อนเปลี่ยนเป็น `required`

**ความปลอดภัยอื่นๆ** (บนสมมติฐาน client ภายในเชื่อถือได้)
- เปิดเฉพาะเครือข่ายภายใน
- allowlist ของ local root / S3 bucket / https host (§5.1)
- DB user แบบ read-only
- ไม่ log ค่า parameter และ request body; Sentry ตั้ง `send-default-pii=false`
- secrets (DB, S3, API key) มาจาก env/secret file ไม่อยู่ใน properties ที่ commit

**Observability**
- Log แบบ JSON (Logstash encoder แบบ pdf) + `requestId`, `tenant`, `report`, `datasource`, `clientId`, ระยะเวลาแต่ละขั้น
- Micrometer/Prometheus:
  - `report_render_seconds{tenant,report,datasource,format,outcome}`
  - `report_compile_seconds`, `report_cache_requests{result=hit|miss}`
  - `report_inflight`, `report_queue_wait_seconds`
  - `report_datasource_override_total{report,from_file,from_request}`
  - metrics ของ Hikari ต่อ pool
- Sentry (optional แบบ pdf) พร้อม span: resolve → fetch → compile → fill → export

---

## 13. เทคโนโลยี

| ส่วน | เลือก | เหตุผล |
|---|---|---|
| Runtime | Java 21, Spring Boot 4.x | Boot 4.1.1; Java 8/Boot 2.5 ของ pdf หมดอายุการสนับสนุน |
| Engine | JasperReports **7.0.8** + `-pdf`, `-groovy`, `-jdt`, `-barcode4j`, `-fonts` (+ `-barbecue` ถ้าจำเป็น) | 7.0.8 เป็น release ล่าสุดบน Maven Central ✅ |
| Storage | AWS SDK v2 S3 client (`endpointOverride` + `forcePathStyle(true)` สำหรับ rustfs) | S3-compatible ทั่วไป |
| Cache | Caffeine | in-memory, TTL/size |
| DB | HikariCP + PostgreSQL driver | |
| Image | `eclipse-temurin:21-jre-jammy`, multi-stage build ใน Dockerfile (ไม่ต้อง build jar นอก Docker แบบเดิม) | |

**ผลกระทบสำคัญของ JR 7 (ยืนยันด้วยการทดสอบ):** JRXML รูปแบบ 6.x **อ่านด้วย 7.0.8 ไม่ได้** — ทดสอบกับไฟล์จริง 3 ไฟล์ (`medical_certificate.jrxml`, `sub_diag_rk01x.jrxml` และไฟล์ตัวอย่างที่เขียนเอง) ได้ `JRException: Unable to load report` โดยไม่มี cause และ JR 7 ฉบับโอเพนซอร์สไม่มี module สำหรับอ่านรูปแบบเก่า (Jaspersoft มีโมดูลแบบมี license ติดมากับ Studio ดู §20.5) ต้องเปิดแล้วบันทึกใหม่ใน Jaspersoft Studio 7 (API ตรวจ namespace เก่าแล้วบอกวิธีแก้ใน error `REPORT_COMPILE_FAILED`)

---

## 14. Configuration ตัวอย่าง

```yaml
report:
  timezone: Asia/Bangkok
  locale: ""                     # ว่าง = ใช้ locale ของ JVM (th_TH จะพิมพ์ปี พ.ศ. ใน pattern วันที่)
  sources:
    local:
      root: /app/reports
    s3:
      endpoint: http://rustfs:9000
      region: us-east-1
      path-style-access: true
      access-key: ${S3_ACCESS_KEY}
      secret-key: ${S3_SECRET_KEY}
      allowed-buckets: [reports]
    http:
      allowed-hosts: []          # ว่าง = ปิด https://
    max-bytes: 5MB
  cache:
    check-interval: 10s
    max-entries: 500
    work-dir: /app/cache         # .jasper แบบ SUBREPORT_DIR และ assets จาก S3
  datasource:
    allow-request-override: true
  parameters:
    strict: false
  limits:
    max-concurrent-renders: 4
    queue-wait: 10s
    fill-timeout: 60s
    query-timeout: 30s
    max-pages: 500
  virtualizer:
    enabled: false
  security:
    api-key:
      mode: required             # required | optional | disabled
      header: X-API-Key
    api-keys:
      - client-id: hosos-web
        sha256: ${API_KEY_HOSOS_WEB_SHA256}
      - client-id: hosos-web     # key ใหม่ระหว่าง rotate
        sha256: ${API_KEY_HOSOS_WEB_SHA256_NEXT}
        expires-at: 2026-12-31T23:59:59+07:00
      - client-id: kvpo
        sha256: ${API_KEY_KVPO_SHA256}
        enabled: true

tenants:
  default:
    default-datasource: opd
    datasources:
      opd: { url: "${OPD_DB_URL}", username: "${OPD_DB_USER}", password: "${OPD_DB_PASSWORD}", pool-size: 5 }
      ipd: { url: "${IPD_DB_URL}", username: "${IPD_DB_USER}", password: "${IPD_DB_PASSWORD}", pool-size: 3 }
```

---

## 15. การย้ายจาก `jasperreports-pdf`

| เรื่อง | pdf | API ใหม่ | client ต้องแก้? |
|---|---|---|---|
| Endpoint | `POST /api/v1/jasper/generate` | ยังใช้ได้ (alias) | ไม่ |
| API key | ไม่มี | `X-API-Key` | ไม่ต้องในช่วงย้าย (โหมด `optional`), ต้องส่งเมื่อเปลี่ยนเป็น `required` |
| `datasource` | บังคับ, ค่าอื่นที่ไม่ใช่ `opd` ตกไป IPD | optional, ไม่รู้จัก = 400 | เฉพาะคนที่ส่งค่าผิดแล้วเคยได้ IPD |
| `mainReport.url` | ไฟล์ใน `jrxmls/` หรือ URL ใดก็ได้ | path ใน root, `s3://`, https ใน allowlist | ถ้าใช้ URL ภายนอก → ย้ายไป S3 หรือเพิ่ม allowlist |
| `modified_at` | ใช้เป็น cache key | ไม่ใช้ | ไม่ (ส่งต่อได้) |
| `parameters[].type` | บังคับ | optional | ไม่ |
| รูปแบบเวลา | `HH:mm:ssZ`, RFC 3339 | รับทั้งมีและไม่มี offset | ไม่ |
| `SUBREPORT_DIR` / `IMAGE_DIR` | ระบบตั้งให้ | ระบบตั้งให้เหมือนเดิม | ไม่ |
| Port | 9091 | ตาม deployment | อาจ |
| JRXML | JR 6.20.6 | **JR 7.0.8 — ต้องแปลงไฟล์** | ไม่ (แต่ทีมทำรายงานต้องแปลง) |
| ฟอนต์ TH SarabunPSK และอื่นๆ | มี | ไม่มี (§9.2) | แก้ JRXML |
| Credential DB | env ของ pdf | env/secret ของ API ใหม่ (user read-only) | ไม่ |

---

## 16. แผนงาน

**Phase 1 — sync render (MVP)**
1. โครง Spring Boot 4 / Java 21 / JR 7.0.8 + Dockerfile multi-stage + font jar ที่ pin SHA-256
2. Tenant/DataSource registry + การ resolve datasource (§6)
3. Source resolver: local + S3 (rustfs) + https allowlist (§5.1)
4. Compiled cache + bundle version + single-flight; subreport ทั้ง `SUBREPORTS` และ `SUBREPORT_DIR`; assets
5. Parameter binder ตามชนิดใน JRXML (§8)
6. Barcode/QR (§10) + test render: ไทย, QR ไทย, Code128, subreport ทั้งสองแบบ
7. Limits, problem+json, API key 3 โหมด + `make api-key`, JSON log, metrics, readiness
8. `/validate`
9. ทดสอบ: unit (binder, resolver, path traversal), integration (Testcontainers PostgreSQL + MinIO/rustfs), render จริงตรวจจำนวนหน้า/ฟอนต์ฝังใน PDF

**Phase 2**: async jobs, alias ฟอนต์ PSK ชั่วคราว (ถ้าต้องการ) — `xlsx`/`csv` และ virtualizer ทำแล้ว ดู [§20](#20-xlsxcsv-virtualizer-และสัญญา-openapi-ส่วนของ-phase-2-ที่ทำแล้ว)

**Phase 3**: เครื่องมือ/ขั้นตอนแปลง JRXML 6.x → 7 และย้าย client ของ pdf

---

## 17. คำถามที่ยังเปิดอยู่

| # | คำถาม | ผลต่อการออกแบบ |
|---|---|---|
| Q1 | **ตอบแล้วบางส่วน:** JR 7.0.8 อ่าน JRXML 6.x ไม่ได้ (ยืนยันแล้ว) — ยังเหลือ: มี JRXML 6.x กี่ไฟล์ที่ต้องย้าย และใครแปลง/ทดสอบ? | ระหว่างที่ยังแปลงไม่ครบ ต้องเปิด pdf ไว้คู่กันสำหรับรายงานที่ยังเป็น 6.x |
| Q2 | ระบุ tenant ด้วย header หรือ body เป็นหลัก? และ tenant มี bucket/prefix ของตัวเองไหม? | รูปแบบ config ของ tenant |
| Q3 | ต้องใช้ฟอนต์อื่นนอกจาก TH Sarabun New ไหม (เช่น Myanmar Text สำหรับผู้ป่วยต่างชาติ, RSU)? | ต้องหาฟอนต์ที่ license อนุญาต + ขออนุมัติ font pack |
| Q4 | การอนุมัติแจกจ่ายฟอนต์ครอบคลุม image ของ API นี้ด้วยไหม หรือต้องขอแยก? | ขั้นตอนก่อน push image |
| Q5 | ต้องเข้ารหัส PDF (เช่น รหัสผ่านเป็นเลขบัตรประชาชน) ไหม? | เพิ่ม option `pdf.password` ใน request |
| Q6 | รายงานใหญ่สุดที่คาดไว้กี่หน้า / ใช้เวลาเท่าไร? | ค่า limits และความจำเป็นของ async ใน phase 1 |

---

## 18. ผลการ implement (Phase 1)

implement ใน repo นี้เมื่อ 2026-10-06 ตามแผน §16 phase 1 วิธีใช้งานดูที่ [README](../../README.md)

### 18.1 ทดสอบอย่างไร และผลเป็นอย่างไร

| ส่วน | วิธีทดสอบ | ผล |
|---|---|---|
| เทสต์อัตโนมัติ (`make test`) | 31 เทสต์: render จริงด้วย H2, ตรวจ PDF ด้วย PDFBox (ข้อความไทย, ฟอนต์ฝัง `THSarabunNew`, พิกเซลของ QR/Code128/โลโก้), subreport ทั้ง 2 แบบ, expression language `groovy`/`java`/ไม่ระบุ, API key ทั้ง 3 โหมด, rotation/expiry, datasource override, parameter ทุกชนิด, limits (max pages, timeout, ช่อง render เต็ม + `Retry-After`), path traversal, แก้ไฟล์แล้ว recompile, SHA-256 ของ font jar | ผ่านทั้งหมด |
| S3 จริง | Testcontainers + `rustfs/rustfs:latest`: สร้าง bucket, ซิงก์โฟลเดอร์ (JRXML + subreport + `assets/`), แก้ object แล้ว version เปลี่ยน, allowlist, readiness | ผ่าน |
| Docker จริง | build image, `docker compose --profile dev` (PostgreSQL 16 + rustfs + API): render จากโฟลเดอร์ที่ mount และจาก `s3://`, alias `/jasper/generate`, `/validate`, `/actuator/prometheus` ด้วย Bearer key, readiness, log แบบ structured, subreport แบบ `SUBREPORT_DIR` (cache dir ของ user 10001 เขียนได้), expression `java`/ไม่ระบุ ใน image แบบ JRE | ผ่าน |
| ยังไม่ได้ทดสอบ | PostgreSQL ที่ใช้ `statement_timeout` ชนจริง, S3 ที่ไม่ใช่ rustfs (MinIO/AWS), http(s) source กับ host จริง, ผลกับ JRXML ของ hosos จริงที่แปลงเป็นรูปแบบ 7 แล้ว, โหลดหนัก/หลาย instance | — |

### 18.2 สิ่งที่ต่างจากแผน หรือพบระหว่างทำ

1. **JRXML 6.x อ่านไม่ได้ใน JR 7.0.8** (§13) — ยืนยันด้วยไฟล์จริง; error บอกวิธีแก้
2. **syntax ของ barcode/QR ถูกต้องตามที่ร่างไว้** (`<component kind="barcode4j:QRCode" errorCorrectionLevel="M">` + `<codeExpression>`) และเทสต์ `rendersThaiTextFontsQrAndBarcodeAndSubreport` เรนเดอร์หน้า PDF เป็นภาพแล้วถอดรหัสด้วย ZXing ได้ค่าตรง: QR = `HN:HN001 สมชาย ใจดี` (UTF-8, ภาษาไทย), Code128 = `HN001`
3. **Barcode/QR ใช้ producer แบบ raster ไม่ใช่ SVG** (§10.1 ที่ระบุว่าค่าเริ่มต้นคือ `svg`): เมื่อเรนเดอร์หน้า PDF เป็นภาพ QR แบบ SVG มีเส้นขาวบางๆ คั่นระหว่างจุดและ ZXing ถอดรหัสไม่ได้ (`NotFoundException`) เปลี่ยนเป็น `net.sf.jasperreports.components.barcode4j.image.producer=image` (300 ppi) ใน `jasperreports.properties` แล้วถอดได้ทั้ง QR ภาษาไทยและ Code128
4. **Spring Boot 4.1.1** (ใช้ Jackson 3 ใน MVC ส่วน JasperReports ใช้ Jackson 2 ในตัวมันเอง อยู่ร่วมกันได้); ต้องใช้ `spring-boot-starter-micrometer-metrics` เพิ่มจึงจะมี endpoint `prometheus`
5. **เอา `spring-boot-starter-jdbc` ออก ใช้ HikariCP ตรงๆ** — พบจากการรันใน container: starter ทำให้ Spring พยายามสร้าง DataSource เริ่มต้นจาก `spring.datasource.url` แล้ว start ไม่ขึ้น (เทสต์ผ่านเพราะมี H2 ใน classpath)
6. **`/api/healthz` อยู่นอก `/v1`** เหมือนของเดิม; `GET /actuator/health/readiness` รวม datasource ทุกตัวและ S3
7. **Locale ไม่ตั้งเป็น `th_TH`** (ดู §5.5) เพื่อไม่ให้ปีใน pattern วันที่เปลี่ยนเป็น พ.ศ. โดยไม่ตั้งใจ
8. **Timestamp ที่ลงท้าย `Z` ตีความเป็น UTC** (ตาม RFC 3339 แบบ pdf) — ของ generater เดิม `'Z'` เป็นแค่ตัวอักษรและถูกอ่านเป็นเวลาท้องถิ่น
9. **query timeout** ตั้งผ่าน property `net.sf.jasperreports.jdbc.query.timeout` ของ context ตอน fill (ยืนยันชื่อแล้วใน JR 7.0.8; ใช้ `Statement.setQueryTimeout` จึงใช้ได้กับทุก driver และครอบคลุม subreport) เกินแล้วตอบ 504 `QUERY_TIMEOUT`; PostgreSQL ยังมี `SET statement_timeout` ใน `connectionInitSql` ของ Hikari อีกชั้น (เฉพาะ URL ที่ขึ้นต้น `jdbc:postgresql:`); fill timeout และ max pages ใช้ governor ของ JasperReports (ทดสอบแล้วทั้งสามตัว)
10. **ผลของ compile ที่ล้มเหลวถูกจำ 5 วินาที** กัน compile ซ้ำถี่ๆ แต่เวอร์ชันของโฟลเดอร์เปลี่ยนเมื่อไร ลองใหม่ทันที
11. **ไม่ได้ทำใน phase 1 (ตามแผน):** async jobs, `xlsx`/`csv`, virtualizer, alias ฟอนต์ `TH SarabunPSK`, tenant ที่มี root/bucket ของตัวเอง, Sentry (ใช้ log แบบ structured + metrics แทน), การบล็อก IP ภายในของ http(s) source (ใช้ allowlist ชื่อ host อย่างเดียว)
12. ผลลัพธ์ถูกสร้างใน memory ทั้งก้อนก่อนตอบ (พอสำหรับรายงานที่จำกัดด้วย `max-pages`) — ถ้ามีรายงานใหญ่มาก ให้ทำ virtualizer/async ใน phase 2

---

## 19. หลายภาษา (i18n)

เพิ่มหลัง phase 1 (2026-10-06) ตามคำขอ: รายงานเลือกภาษาได้โดย **ใช้รูปแบบเดียวกับ datasource**

### 19.1 ข้อตัดสินใจ

| # | หัวข้อ | ข้อตัดสินใจ |
|---|---|---|
| D13 | กลไก | ใช้ resource bundle ของ JasperReports (`resourceBundle="messages"` + `$R{key}`) ไม่ทำระบบแปลของเราเอง |
| D14 | ส่งภาษาทางไหน | **JSON body** ฟิลด์ `locale` (ไม่ใช้ URL/query และไม่อ่าน `Accept-Language`): `url` = รายงานไหน, `locale` = ตัวเลือกการ render เหมือน `format`/`fileName`; `Accept-Language` เป็นภาษาของ browser/ผู้ใช้ปลายทางซึ่งมักไม่ใช่ภาษาของเอกสาร |
| D15 | ค่าเริ่มต้น | ลำดับ: request `locale` > `<property name="report.locale">` ใน JRXML > `report.locale` ใน config > `en`; **request ชนะ** (เหมือน D4) |
| D16 | ตำแหน่งไฟล์ข้อความ | `messages*.properties` อยู่ข้าง JRXML → โฟลเดอร์ที่ mount และ S3 (อยู่ใน bundle อยู่แล้ว) และ http(s) (ดาวน์โหลดไฟล์ข้างๆ ให้อัตโนมัติ ดู §19.4) |
| D17 | ข้อมูลหลายภาษาใน DB | API ใส่ parameter `REPORT_LANGUAGE` (`th`/`en`, ถ้ารายงานประกาศ) ให้ใช้ใน SQL |
| D18 | Response | header `Content-Language`; `/validate` แสดง locale ที่ resolve ได้, ภาษาที่มีไฟล์ในแต่ละ bundle และเตือน key ที่ขาด |

### 19.2 สิ่งที่พบจากการทดสอบ (ส่งผลต่อการ implement)

1. **Java ข้ามไปใช้ไฟล์ของภาษาเครื่องก่อนไฟล์ตั้งต้น:** `ResourceBundle` ใช้ locale ของ JVM เป็น fallback ก่อน base bundle — บนเครื่อง `en_US` คำขอ `th` ที่ไม่มี `messages_th.properties` ได้ `messages_en.properties` แทนที่จะเป็น `messages.properties` แก้โดยตั้ง locale ของ JVM เป็น `ROOT` ตอนเริ่ม (`RuntimeConfig`) — รายงานได้ `REPORT_LOCALE` ที่ชัดเจนเสมอ จึงไม่กระทบอย่างอื่น
2. **ไฟล์ใน classpath บังไฟล์ของรายงานได้:** JasperReports หา resource ผ่าน class loader ของ thread ก่อน — พบจากเทสต์ที่วาง `messages.properties` ไว้ที่ราก classpath แล้วรายงานได้ค่านั้นแทน แก้โดยให้โฟลเดอร์ของรายงาน (version นั้น) เป็น class loader ที่ค้น **ก่อน** classpath (`BundleClassLoaders` + `JRResourcesUtil.setThreadClassLoader` ระหว่าง fill) และสร้าง loader ใหม่เมื่อเวอร์ชันของโฟลเดอร์เปลี่ยน เพื่อให้แก้ `.properties` แล้วมีผลทันที (`ResourceBundle` cache ต่อ loader)
3. **`FileRepositoryService` ใช้ไม่ได้กับ bundle** (ลองแล้ว ไม่พบไฟล์) ใช้ class loader แทน
4. **`th-TH` พิมพ์ปี พ.ศ. ใน pattern วันที่ แต่ `th` พิมพ์ ค.ศ.** (ทดสอบ: `2569` เทียบ `2026`) — เอกสาร/ตัวอย่างแนะนำ `th`
5. subreport ที่มี `resourceBundle` ของตัวเองได้ภาษาเดียวกับรายงานหลักโดยไม่ต้องส่งต่อ parameter
6. properties เขียน UTF-8 ตรงๆ ได้ (ภาษาไทยผ่าน)

### 19.3 การทดสอบ

`I18nTest` (ค่าเริ่มต้นจากรายงาน, request ชนะ + ข้อมูล DB แปลภาษา + subreport, `th-TH` vs `th`, ภาษาที่ไม่มีไฟล์ → ไฟล์ตั้งต้น, tag ผิด → 400), `LocaleSelectorTest` (ลำดับ), `/validate`, bundle ใน S3 (rustfs) และแก้ `messages.properties` ในโฟลเดอร์/S3 แล้วมีผล, และรันจริงใน Docker กับ PostgreSQL + rustfs (`locale: en` ได้ข้อความอังกฤษ ชื่อผู้ป่วยจากคอลัมน์ `name_en` และวันที่ `6 October 2026`)
`/validate` เตือน key ที่ขาดในบางภาษา (`BundleVersionTest`) ยังไม่ได้ทดสอบ: ฟอนต์สำหรับภาษาอื่นนอกจากไทย/ละติน

### 19.4 http(s) ต้องรองรับ subreport และ message bundle (แก้ไขข้อจำกัดของ phase 1)

phase 1 ทำ http(s) แบบ "ไฟล์เดียว" ซึ่ง **ด้อยกว่า `jasperreports-pdf`** ที่รับ `subReports[].url` — ไม่ใช่ข้อจำกัดโดยธรรมชาติของ http แต่เป็นสิ่งที่ยังไม่ได้ implement จึงปรับดังนี้

| เรื่อง | การตัดสินใจ |
|---|---|
| subreport | ระบุใน `subReports[]` (`name` + `url`) เหมือน pdf; ดาวน์โหลดมาเก็บเป็น `<name>.jrxml` ใน bundle ของรายงาน จึงใช้ได้ทั้ง `SUBREPORTS` และ `SUBREPORT_DIR`; **ไม่เดา URL** (ถ้าไม่ระบุ ตอบ 404 พร้อมคำแนะนำ) เพราะไม่รู้ว่าต้นทางเก็บไฟล์ข้างกันหรือไม่ |
| message bundle | อ่าน `resourceBundle` จาก JRXML แต่ละไฟล์ แล้วขอ `<bundle>.properties`, `<bundle>_<lang>_<COUNTRY>.properties`, `<bundle>_<lang>.properties` จาก URL ข้างไฟล์นั้น (404/403 = ไม่มี) สำหรับภาษาที่ **อาจ** ถูกเลือก: `locale` ใน request + `report.locale` ใน JRXML (อ่านด้วย regex) + `report.locale` ใน config — ใช้ชุดรวมแทนการคำนวณลำดับความสำคัญซ้ำ จึงถูกต้องไม่ว่าอันไหนชนะ |
| เวอร์ชัน | แฮชของชื่อ+เนื้อหาไฟล์ที่ดาวน์โหลดทั้งหมด; ตรวจใหม่ทุก `check-interval`; แก้ `.properties` ที่ต้นทางแล้วเวอร์ชันเปลี่ยน → class loader ของ bundle เปลี่ยน (§19.2 ข้อ 2) |
| รูปภาพ | ไม่ดาวน์โหลดให้ — ใช้ URL เต็มใน expression ซึ่ง JasperReports ดึงเองได้ (ทดสอบแล้ว) ไม่ผ่าน allowlist ของ API (JRXML คือโค้ดที่เชื่อถืออยู่แล้ว) |
| ความปลอดภัย | ทุก URL (หลัก, subreport, ไฟล์ข้างเคียง) ผ่าน allowlist ของ host; ไม่ตาม redirect; จำกัดขนาด/เวลา/จำนวนไฟล์ (100) |
| ดาวน์โหลดพร้อมกัน | JRXML หลัก + subreport พร้อมกัน แล้ว `.properties` ทุกตัวที่เป็นไปได้พร้อมกัน (virtual threads) จำกัดทั้งระบบด้วย semaphore `report.sources.http.parallelism` (8); ความล้มเหลวที่พบก่อนในลำดับ (หลักก่อน) เป็นตัวที่รายงาน และยกเลิกที่เหลือ — ทดสอบแล้วว่ามี ≥ 4 request ซ้อนกันและเวลารวมต่ำกว่าแบบทีละไฟล์ |
| stale-if-error | รอบตรวจใหม่ล้มเหลวเพราะ **โครงสร้างพื้นฐาน** (เชื่อมต่อไม่ได้/timeout/HTTP 5xx; ใน S3 คือ SdkException/5xx) → ใช้เวอร์ชันล่าสุดที่โหลดสำเร็จ + WARN + metric `report.source.stale` และจำผลนั้นเท่า check-interval (ไม่ยิงต้นทางที่ล่มทุก request) **ไม่ปิดบังคำตอบของต้นทาง** — 404, 401/403, URL pre-signed หมดอายุ ต้องล้มตามจริง เพราะสำเนาเก่าจะอยู่เกินสิทธิ์ที่ให้ไว้ (พบจากการออกแบบ: ธง `ApiException.transientFailure` แยก "ระบบล่ม" ออกจาก "ถูกปฏิเสธ"; มีเทสต์ 500 → ใช้ของเก่า, 403/404 → ไม่ใช้) |
| ข้อจำกัดที่เหลือ | URL ที่มี query string (pre-signed) ใช้เป็นไฟล์ JRXML ได้แต่ไม่ดาวน์โหลด `.properties` ให้; ดาวน์โหลดทั้งชุดซ้ำทุก check-interval (ยังไม่ใช้ conditional GET); รูปแบบ URL เต็มดึงตอน render จึงไม่ได้ stale-if-error; ถ้าต้นทางล่ม request แรกต่อ key หลังหมดอายุจะรอ timeout หนึ่งครั้งก่อนใช้ของเก่า |

ทดสอบด้วย HTTP server จริงในเทสต์ (`HttpSourceTest`): หลัก + subreport + bundle ของทั้งสอง, `locale: en`, แก้ `.properties` ที่ต้นทางแล้วเปลี่ยน, subreport แบบ `SUBREPORT_DIR`, รูปแบบ URL เต็ม, subreport ที่ไม่ได้ระบุ, host/scheme นอก allowlist, ไฟล์ไม่มี ยังไม่ได้ทดสอบกับ https/host จริงและ pre-signed URL

## 20. xlsx/csv, virtualizer และสัญญา OpenAPI (ส่วนของ phase 2 ที่ทำแล้ว)

เพิ่มหลัง v0.2.0 (2026-10-06) ส่วนที่เหลือของ phase 2 คือ async jobs และ alias ฟอนต์ `TH SarabunPSK`

### 20.1 xlsx / csv

| เรื่อง | การตัดสินใจ | เหตุผล |
|---|---|---|
| ตัวส่งออก | xlsx: `net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter` (อยู่ใน jar หลัก); csv: `JRCsvExporter` | ตรวจใน 7.0.8 แล้วว่า xlsx ไม่ต้องใช้ `jasperreports-excel-poi` (โมดูลนั้นมีเฉพาะ `.xls` เก่า) จึงไม่เพิ่ม POI กับ dependency ที่ตามมา (xmlbeans, log4j-api ฯลฯ) เข้า jar |
| ขอบเขต format | `pdf`, `xlsx`, `csv` เท่านั้น (`OutputFormat`); `xls`, `docx`, `html` ตอบ `400 FORMAT_UNSUPPORTED` | ไม่มีผู้ใช้ขอ และแต่ละ format เพิ่มพื้นที่ต้องทดสอบ |
| ตั้งค่า xlsx | ชีตเดียวต่อเนื่อง (`onePagePerSheet=false`), ตรวจชนิดเซลล์, พื้นหลังหน้าไม่ขาว, ตัดแถว/คอลัมน์ว่างระหว่างกัน | ผลที่คนเอาไปเปิดใน Excel ต้องการ ไม่ใช่สำเนาของหน้ากระดาษ |
| ไม่บังคับ `ignorePagination` | ปล่อยให้ผู้เขียนรายงานตั้งใน JRXML | JasperReports แยกไม่ได้ว่า `false` ตั้งเองหรือค่าเริ่มต้น และการบังคับจะเปลี่ยนผลของ `max-pages` โดยไม่บอก |
| csv | UTF-8 + BOM (`report.export.csv-bom`, ค่าเริ่มต้น `true`), `,` และ `CRLF` | Excel บน Windows อ่าน UTF-8 ไม่มี BOM เป็นภาษาท้องถิ่น ภาษาไทยจะเพี้ยน; โปรแกรมอื่นข้าม BOM ได้ |
| `Content-Disposition` | ค่าเริ่มต้น pdf `inline`, xlsx/csv `attachment` (override ได้ด้วย `disposition` ใน request); เติมนามสกุลของ format ให้ถ้า `fileName` ยังไม่มี | เบราว์เซอร์เปิด pdf ได้เอง แต่ไม่ควรพยายามเปิด xlsx |

ทดสอบ: `ExportFormatsTest` (เปิดไฟล์ xlsx เป็น zip ตรวจข้อความไทย, รูป/QR ฝังเป็น `xl/media/`, BOM, ชื่อไฟล์) และ `CsvWithoutBomTest`

### 20.2 virtualizer

- ทุก render สร้าง `JRSwapFileVirtualizer` ของตัวเอง (`RenderVirtualizers`) ใส่ผ่าน `REPORT_VIRTUALIZER` และ `RenderService` ปิดด้วย try-with-resources จึงลบไฟล์เสมอ รวมกรณีล้มกลางทาง (ทดสอบด้วย `PAGE_LIMIT_EXCEEDED` ที่เกิดหลังสลับหน้าลงไฟล์แล้ว)
- เปิดเป็นค่าเริ่มต้นเพราะ JasperReports สลับลงไฟล์เมื่อเกิน `max-pages-in-memory` เท่านั้น รายงานเล็กเสียแค่การสร้างไฟล์เปล่า; ถ้าสร้างไฟล์ไม่ได้ให้ render ต่อโดยไม่ใช้ virtualizer และ log WARN ไม่ให้ระบบล้มเพราะโฟลเดอร์เขียนไม่ได้
- ไฟล์ค้างจาก process ที่ล้ม (`swap_*` เก่ากว่า 1 ชั่วโมง ซึ่งเกินเวลา render สูงสุดมาก) ลบตอนเริ่มระบบ ไม่ลบไฟล์ใหม่เผื่อมี process อื่นใช้โฟลเดอร์เดียวกัน
- ลดเฉพาะ heap ระหว่าง fill ผลลัพธ์ยังประกอบใน memory ก่อนตอบ ถ้าต้องการลดด้วยต้องทำ async (ยังไม่ทำ)
- ทดสอบ: `VirtualizerTest` (ตั้ง 2 หน้าใน memory แล้ว render รายงาน ~20 หน้า เฝ้าดูโฟลเดอร์ swap ระหว่างทำงานว่ามีไฟล์ที่ขนาด > 0 จริง ผลลัพธ์ครบ และไฟล์ถูกลบ) และ `RenderVirtualizersTest`

### 20.3 สัญญา OpenAPI

เขียนมือที่ `src/main/resources/openapi/openapi.yaml` เปิดที่ `GET /api/v1/openapi.yaml` (ไม่ต้องใช้ key) ไม่ใช้ springdoc เพราะ (1) ต้องอธิบาย `code` ของ error และ response สามชนิดซึ่ง annotation ทำได้ไม่ดี (2) ไม่ต้องเพิ่ม dependency และ reflection ตอนเริ่มระบบ (3) เทสต์ `OpenApiContractTest` กันไม่ให้ไฟล์ล้าหลังโค้ด: เทียบ route ทั้งหมดกับ `RequestMappingHandlerMapping`, ฟิลด์กับ record `RenderRequest`/`ReportRef`/`ParamInput`, `format` กับ `OutputFormat` และ `ErrorCode` กับ code ที่ค้นได้ใน `src/main/java` (ทดลองลบ code หรือเปลี่ยนชื่อฟิลด์แล้วเทสต์ล้มจริง)

### 20.4 เทสต์ subreport ของ JSON `data`

`JsonSubreportTest` ยืนยันวิธีใน README: `JsonDataSource.subDataSource("visits")` จาก `$P{REPORT_DATA_SOURCE}` ส่งให้ subreport — แต่ละผู้ป่วยได้เฉพาะ array ของตัวเอง, array ว่างหรือไม่มี key ไม่ทำให้ล้ม

### 20.5 ตัวแปลง JRXML 6.x → 7 (`POST /v1/reports/convert`)

ทำใน phase 3 ตามแผน §16 เป็น Java ล้วน (DOM + XML ของ JDK ไม่มี dependency เพิ่ม) แทนที่จะรัน JR 6 ใน class loader แยก เพราะ JR 6 กับ 7 ใช้ชื่อคลาสเดียวกัน แปลงข้าม engine ไม่ได้ และไม่อยากแบก JR 6 ไว้ใน jar

- **ตรวจจาก engine จริง ไม่ใช่จากความจำ:** loader ของ JR 7.0.8 ปฏิเสธ attribute/element ที่ไม่รู้จัก (`UnrecognizedPropertyException`) แต่บางตำแหน่งที่ผิดที่ถูกทิ้งเงียบ (เช่น `<import value="x"/>` โหลดเป็น import ว่าง, `<style>` ซ้อนใน `conditionalStyle`) จึงมีเทสต์ round trip ผ่าน `JRXmlWriter` แยกต่างหาก "โหลดได้" อย่างเดียวพิสูจน์ไม่ได้
- **ไม่ทิ้งเงียบ:** เทสต์ฉีด attribute และ child ที่ไม่รู้จักเข้าทุก element ของ fixture ทุกไฟล์ ต้องมี warning ทุกครั้ง; element ที่แปลงไม่ได้ถูกแทนด้วย `<!-- not converted: ตำแหน่ง -->`
- **รายชื่อคลาสที่ย้าย/หายใน 7** (`RelocatedClasses`) ได้จากการ diff jar 6.21.5 กับโมดูลของ 7.0.8 — เตือนอย่างเดียว ไม่เขียน expression ใหม่
- **`<reportFont>`:** JR 6.17–6.21 ไม่อ่าน `size` จาก `reportFont` (ข้อความจึงออก 10pt) ตัวแปลงรุ่นแรกรักษาผลนั้นไว้ แต่ตัดสินใจ (2026-10-07) เปลี่ยนเป็น**ใช้ขนาดที่ประกาศ** เพราะเป็นสิ่งที่ผู้ออกแบบตั้งใจและตรงกับที่ Studio 7 แสดง; หน้าตาจึงต่างจาก 6.x ได้ จึงมี warning ทุก reportFont ที่มี `size` และวิธีกลับไปแบบเดิมคือลบ `fontSize` ออกจาก style
- **ผลตรวจกับรายงานจริงของนักพัฒนา (รันครั้งเดียว 2026-10-06 ไม่อยู่ใน repo เพราะเป็นไฟล์ลูกค้า):** 674 ไฟล์ซ้ำกันบางส่วน เหลือ 471 ไฟล์ต่างกัน แปลงได้ทุกไฟล์ 450 ไม่มี warning, 21 มี warning (14 `JsonDataSource` ย้ายที่, 3 ขนาด `reportFont`, 2 barbecue, 2 query `plsql`); โหลดใน JR 7 ได้ 467 (4 ที่ไม่ได้ทั้งหมดมี warning ไว้ก่อน); คอมไพล์ได้ 408 โดย 45 จาก 59 ที่ไม่ได้ก็ไม่ผ่านใน JR 6 เช่นกัน (ขาดคลาสช่วยของลูกค้า) อีก 14 คือ `JsonDataSource` ที่เตือนแล้ว
- **เทียบ design ที่โหลดแล้ว:** dump ทุก property ของ JR 6.21.5 (ต้นฉบับ) กับ JR 7 (ผลแปลง) 467 รายงาน ราว 1.37 ล้านบรรทัด ต่างกัน 0 (ทดลองทำให้ตัวแปลงพิมพ์ `isBold` ผิด ไฟล์ต่าง 363 ไฟล์ จึงเชื่อได้ว่าเทสต์จับความผิดได้)
- **เทียบการ render:** fill ได้ทั้งสอง engine 282 รายงาน (อีก 188 ล้มทั้งคู่ เพราะคลาสลูกค้าหรือข้อมูลขาด) จำนวนหน้า ข้อความ PDF และฟอนต์ตรงกันทั้งหมด; ต่างแค่ 1 ไฟล์ที่ JR 6 ตัด newline ท้าย static text หนึ่งตัว ส่วน JR 7 เก็บไว้ (เป็นพฤติกรรมของ engine ไม่ใช่ของตัวแปลง); ความต่างพิกเซลสูงสุดต่อหน้า 0.15%
- **property ที่ JR 7 เลิกอ่าน (2026-10-07):** เก็บทุกสตริง `net.sf.jasperreports.*` จากทุก jar ของ 6.21.5 และ 7.0.8 แล้วหาที่มีเฉพาะฝั่ง 6 (ตัดชื่อคลาส, ข้อความ UI, ชื่อที่เป็นแค่ส่วนต้นของ key อื่น) ได้ 33 key ที่เหลือหลังคัดกรอง (`RemovedProperties`) พร้อมกฎตามกลุ่ม: map component, header toolbar, query executer ของภาษาที่เลิก ไม่พบ property ที่ *เปลี่ยนชื่อ* (ไม่มี key ใหม่ใน 7 ที่คล้ายกับ key ที่หาย) ทั้งหมดเลิกไปพร้อมฟีเจอร์ — ขีดจำกัด: นี่คือ "ไม่พบสตริงนั้นใน 7.0.8" ไม่ใช่การพิสูจน์ว่า engine เมิน และไม่ครอบคลุม property ที่ความหมายเปลี่ยนโดยชื่อเดิม; jar ของ 6.x ใน Maven ในเครื่องมีเฉพาะโมดูลหลัก/fonts/functions/metadata จึงไม่ครอบคลุม key ของโมดูลอื่นที่แยกอยู่ใน 6
- **Engine ของ Studio 7 โหลดผลลัพธ์ได้:** ใช้ JR 7.0.3 ที่ติดมากับ Jaspersoft Studio 7.0.3 (ติดตั้งในเครื่อง) โหลดผลแปลงของ 473 ไฟล์ที่ต่างกัน (fixture + รายงานจริงในเครื่อง) ได้ 470 อีก 3 ไฟล์คือ query `plsql` ที่มี warning อยู่แล้ว — ยืนยันว่า loader เดียวกับที่ Studio ใช้ยอมรับรูปแบบที่ตัวแปลงเขียน (ไม่ใช่การเปิดหน้าจอ Studio จริง ซึ่งต้องให้คนทำ)
- **ยังไม่ได้ตรวจ:** property ที่ความหมายเปลี่ยนโดยชื่อเดิม, chart/map/part (ไม่แปลง), และการเปิดผลลัพธ์ในหน้าจอ Jaspersoft Studio 7 จริง (ทำแทนด้วยเครื่องมือไม่ได้)
- **โมดูล legacy ของ Jaspersoft:** Studio 7 มี `jasperreports-legacy-jrxml-core` (กลุ่ม `com.jaspersoft.jasperreports`, Cloud Software Group) ที่อ่าน JRXML 6 ได้ แต่เป็นโมดูลของ JasperReports Pro (pom ชี้ไปที่ repo `jr-pro-releases` ภายใน ไม่มีบน Maven Central) และโค้ดเรียก `LicenseManager` ก่อนใช้ จึงไม่นำมาใช้หรือแจกจ่ายใน API นี้ และไม่พยายามเลี่ยงการตรวจ license — ข้อความใน §13 ที่ว่า "JR 7 ไม่มี module อ่านรูปแบบเก่า" จึงถูกเฉพาะชุดโอเพนซอร์ส
- **ตรวจก่อนใช้งานจริง:** `scripts/jrxml-migrate-check.sh` แปลงทั้งโฟลเดอร์, validate, render และเทียบกับ PDF ของระบบเดิม (จำนวนหน้า ข้อความ ภาพของหน้า) ทดลองกับ API ที่รันจริง: PDF ที่เหมือนกันผ่าน, PDF ที่ข้อความต่างกันตก (exit 1) พร้อมแสดง diff
- ขอบเขตของ endpoint: รับ JSON `{"jrxml": ...}` จำกัดด้วย `report.sources.max-bytes` (ตัวแปลงเองรับได้ 16M ตัวอักษรแต่ถือทั้งไฟล์เป็น DOM จึงจำกัดที่ขอบ API) และเพิ่ม warning ถ้าผลลัพธ์โหลดใน JR 7 ไม่ได้
