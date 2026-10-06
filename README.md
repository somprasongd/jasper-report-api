# jasper-report-api

REST API สำหรับสร้างรายงาน PDF จาก JasperReports (JRXML) — ต่อยอดจาก `jasperreports-pdf` และ `jasperreports-generater`

- รับ JRXML จาก **โฟลเดอร์ที่ mount ไว้** หรือ **S3-compatible storage** (rustfs, MinIO, AWS S3) หรือ URL ของ host ที่อนุญาตไว้
- **การเชื่อมต่อ DB อยู่ที่ API** — client ส่งแค่ชื่อ datasource (`opd`, `ipd`, ...) ไม่ส่ง JDBC URL/รหัสผ่าน
- รองรับ **ฟอนต์ไทย** (TH Sarabun New ฝังใน PDF), **barcode** (Code128, Code39, EAN, PDF417, ...) และ **QR code** (รวมข้อความภาษาไทย)
- JasperReports **7.0.8**, Java 21, Spring Boot 4.1
- API key, จำกัดจำนวน render พร้อมกัน/เวลา/จำนวนหน้า, metrics (Prometheus), health/readiness, JSON error แบบ RFC 9457
- รูปแบบ request เข้ากันได้กับ `jasperreports-pdf` (`POST /api/v1/jasper/generate` ยังใช้ได้)

เอกสารออกแบบและเหตุผลของแต่ละข้อตัดสินใจ: [docs/design/jasper-report-api-design.md](docs/design/jasper-report-api-design.md)

> **ข้อสำคัญ:** JasperReports 7.0.8 **อ่าน JRXML รูปแบบของ 6.x ไม่ได้** (ทดสอบแล้วกับไฟล์จริง) ต้องเปิดแล้วบันทึกใหม่ด้วย Jaspersoft Studio 7 ก่อน — ดู [การเขียน/แปลง JRXML](#การเขียนและแปลง-jrxml)

---

## เริ่มใช้งานเร็ว (demo ในคำสั่งเดียว)

ต้องมี Docker (compose v2)

```bash
make dev-up
```

คำสั่งนี้จะ: สร้าง `.env` พร้อม API key สำหรับ demo → เริ่ม API + PostgreSQL (มีข้อมูลตัวอย่าง) + rustfs → อัปโหลดรายงานตัวอย่างจาก `samples/reports/` ไปที่ rustfs → พิมพ์คำสั่ง `curl` ที่ใช้ได้ทันที (key เก็บไว้ที่ `.dev-api-key`)

```bash
curl -X POST http://127.0.0.1:8080/api/v1/reports/render \
  -H "X-API-Key: $(cat .dev-api-key)" -H "Content-Type: application/json" \
  -d '{"mainReport":{"url":"s3://reports/samples/demo/th_demo.jrxml"},"parameters":[{"name":"hn","value":"HN001"}]}' \
  -o demo.pdf
```

รายงานเดียวกันจากโฟลเดอร์ที่ mount: `"url":"demo/th_demo.jrxml"` — เลิกใช้ด้วย `make dev-down` (ลบ volume ของ demo ด้วย)

---

## การติดตั้งใช้งานจริง

### 1. เตรียม API key

```bash
make api-key CLIENT=hosos-web
```

ได้ key (`jra_...`) กับค่า SHA-256 — **ส่ง key ให้ client ครั้งเดียว** (ฝั่ง API เก็บแค่ hash กู้คืน key ไม่ได้) แล้วนำ hash ไปใส่ใน `.env` ตามที่สคริปต์พิมพ์ให้ (ดู [API key](#api-key))

### 2. ตั้งค่า `.env`

```bash
cp .env.example .env
```

อย่างน้อยต้องมี: `REPORT_SECURITY_API_KEYS_0_*` (จากข้อ 1), `OPD_DB_URL/USER/PASSWORD` และ `REPORTS_DIR` (โฟลเดอร์บน host ที่เก็บ JRXML) — ตัวแปรทั้งหมดอยู่ใน [การตั้งค่า](#การตั้งค่า) ใช้ **DB user แบบ read-only**

### 3. เริ่มระบบ

```bash
docker compose up -d --build        # หรือ make up
curl http://127.0.0.1:8080/api/healthz
```

- port เริ่มต้น bind ที่ `127.0.0.1:8080` (เปลี่ยนด้วย `API_BIND`, `API_PORT`) — เปิดให้ host อื่นเรียกเฉพาะเมื่อมี firewall/reverse proxy คุม
- ไม่ต้อง build jar นอก Docker (Dockerfile build เองแบบ multi-stage)
- โฟลเดอร์ JRXML mount แบบ **read-only** ที่ `/app/reports`; แก้ไฟล์บน host แล้ว request ถัดไปใช้ไฟล์ใหม่ทันที โดยไม่ต้อง restart

---

## การเรียกใช้

Base path คือ `/api`

| Method | Path | หน้าที่ | ต้องใช้ API key |
|---|---|---|---|
| POST | `/api/v1/reports/render` | สร้าง PDF | ใช่ |
| POST | `/api/v1/jasper/generate` | เหมือน `render` (alias ให้ client ของ `jasperreports-pdf`) | ใช่ |
| POST | `/api/v1/reports/validate` | compile รายงานแล้วบอกว่า API เห็นอะไร (parameter, datasource, ฟอนต์, คำเตือน) โดยไม่รัน query | ใช่ |
| GET | `/api/healthz` | liveness แบบเดิม | ไม่ |
| GET | `/api/actuator/health/liveness`, `/readiness` | ตรวจสุขภาพ (readiness ตรวจ DB ทุกตัวและ S3) | ไม่ |
| GET | `/api/actuator/prometheus` | metrics | ใช่ (`X-API-Key` หรือ `Authorization: Bearer <key>`) |

### Request

```json
{
  "tenant": "hospital-a",
  "datasource": "opd",
  "mainReport": { "name": "ใบรับรองแพทย์", "url": "s3://reports/opd/medical_certificate/main.jrxml" },
  "subReports": [],
  "parameters": [
    { "name": "visit_id", "value": "1234" },
    { "name": "print_time", "value": "2026-10-06T10:30:00+07:00" },
    { "name": "item_ids", "value": [1, 2, 3] }
  ],
  "format": "pdf",
  "fileName": "ใบรับรองแพทย์"
}
```

| ฟิลด์ | บังคับ | ความหมาย |
|---|---|---|
| `mainReport.url` | ใช่ | JRXML ที่จะใช้ ดู [แหล่งที่มาของ JRXML](#แหล่งที่มาของ-jrxml) |
| `mainReport.name` | ไม่ | ใช้ตั้งชื่อไฟล์ผลลัพธ์ถ้าไม่ส่ง `fileName` (ไม่ส่ง = ชื่อไฟล์ JRXML) |
| `mainReport.modified_at` | ไม่ | รับไว้เพื่อให้เข้ากับ `jasperreports-pdf` แต่ **ไม่ใช้** (API ดูการเปลี่ยนแปลงของไฟล์เอง) |
| `datasource` | ไม่ | ชื่อ datasource เชิงตรรกะ ดู [การเลือก datasource](#การเลือก-datasource) |
| `tenant` | ไม่ | ไม่ส่ง = `default`; header `X-Tenant-Id` ชนะค่าใน body |
| `subReports[]` | ไม่ | ใช้เฉพาะรายงานแบบ `SUBREPORT_DIR` (ระบุ subreport ที่จะ compile; ไม่ระบุ = ทุก `*.jrxml` ในโฟลเดอร์) |
| `parameters[].name` / `value` | ใช่ | ค่า parameter ดู [ชนิดของ parameter](#ชนิดของ-parameter) |
| `parameters[].type` | ไม่ | ใช้เฉพาะเมื่อ JRXML ประกาศชนิดกว้างๆ (`Object`, `Collection`) |
| `format` | ไม่ | `pdf` (ค่าเริ่มต้น; ตอนนี้รองรับเฉพาะ `pdf`) |
| `fileName` | ไม่ | ชื่อไฟล์ใน `Content-Disposition` (ภาษาไทยได้) |

Header: `X-API-Key` (ตามโหมด [API key](#api-key)), `X-Tenant-Id` (ไม่บังคับ), `X-Request-Id` (ไม่บังคับ; ไม่ส่งจะสร้างให้ และส่งกลับ + อยู่ใน log ทุกบรรทัดของ request)

### Response

- สำเร็จ `200`, `Content-Type: application/pdf`, `Content-Disposition: inline; filename*=UTF-8''...`, `X-Report-Version` (เวอร์ชันของโฟลเดอร์รายงานที่ใช้จริง), `X-Request-Id`
- ผิดพลาด `application/problem+json`:

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400, "code": "DATASOURCE_UNKNOWN",
  "detail": "datasource 'opdx' is not configured for tenant 'default'", "requestId": "..." }
```

| `code` | HTTP | เมื่อไร |
|---|---|---|
| `API_KEY_MISSING` / `API_KEY_INVALID` | 401 | ไม่ส่ง key (โหมด `required`) / key ไม่รู้จัก ปิดอยู่ หรือหมดอายุ |
| `VALIDATION_FAILED` | 400 | body ผิดรูปแบบหรือไม่ครบ |
| `TENANT_UNKNOWN`, `DATASOURCE_UNKNOWN` | 400 | ชื่อไม่มีใน config |
| `DATASOURCE_UNRESOLVED` | 400 | ไม่มี datasource ทั้งใน request, JRXML และค่า default |
| `DATASOURCE_OVERRIDE_DENIED` | 400 | request ไม่ตรงกับ JRXML และปิด override ไว้ |
| `SOURCE_NOT_ALLOWED` | 400 | path/bucket/host/scheme ไม่ได้รับอนุญาต หรือพยายามออกนอกโฟลเดอร์ |
| `PARAMETER_INVALID` | 400 | แปลงค่า parameter ไม่ได้ (ระบุชื่อ parameter) |
| `FORMAT_UNSUPPORTED` | 400 | `format` ที่ยังไม่รองรับ |
| `REPORT_NOT_FOUND` | 404 | หาไฟล์/bucket ไม่เจอ |
| `REPORT_COMPILE_FAILED` | 422 | JRXML compile ไม่ผ่าน (รวมถึงเป็นรูปแบบ 6.x) |
| `PAGE_LIMIT_EXCEEDED` | 422 | เกิน `report.limits.max-pages` |
| `RENDER_BUSY` | 503 + `Retry-After` | ช่อง render เต็มนานเกิน `queue-wait` |
| `RENDER_TIMEOUT` | 504 | fill นานเกิน `fill-timeout` |
| `DATABASE_ERROR` | 502 | DB ต่อไม่ได้หรือ SQL ผิด |
| `STORAGE_ERROR` | 502 | อ่าน S3/host ปลายทางไม่ได้ |
| `INTERNAL_ERROR` | 500 | อื่นๆ (เช่น รูปที่รายงานอ้างไม่มีไฟล์) |

### ชนิดของ parameter

ชนิดปลายทางคือ `class` ที่ **ประกาศใน JRXML** ค่าจาก JSON เป็น string/ตัวเลข/boolean/array ได้ ตัวอย่างที่ส่ง `type` ไม่ต้องแล้ว

| class ใน JRXML | ค่าที่รับ |
|---|---|
| `String` | ค่าอะไรก็ได้ (แปลงเป็น string) |
| `Integer`, `Long`, `Short`, `Byte`, `Double`, `Float`, `BigDecimal`, `BigInteger` | ตัวเลข หรือ string ตัวเลข |
| `Boolean` | `true`/`false`/`1`/`0` |
| `java.sql.Date`, `java.util.Date` | `yyyy-MM-dd` (`java.util.Date` รับ timestamp ได้ด้วย) |
| `java.sql.Time` | `HH:mm[:ss]` (ตามเขตเวลาของรายงาน) หรือมี offset: `10:30:00Z`, `10:30:00+07:00` |
| `java.sql.Timestamp` | ISO-8601: `2026-10-06T10:30:00+07:00`, `...Z` (= UTC), `2026-10-06T10:30:00` หรือ `2026-10-06 10:30:00` (ตามเขตเวลาของรายงาน) |
| `Collection`/`List`/`Set` | JSON array หรือ string คั่นด้วย `,`; ชนิดสมาชิกจาก `nestedType` ใน JRXML หรือ `type: "array_int"`/`"array_str"` |
| `Object` | ใช้ `type` (`string, integer, number, date, time, timestamp, bool, array_str, array_int`) ถ้าไม่ส่งจะได้ค่าตามที่ส่ง |

- parameter ที่ **ไม่ได้ประกาศใน JRXML** ถูกตัดทิ้ง (เตือนใน log; ตั้ง `report.parameters.strict=true` ให้ตอบ 400 แทน)
- `SUBREPORTS`, `SUBREPORT_DIR`, `IMAGE_DIR`, `REPORT_ASSETS_DIR`, `REPORT_CONNECTION`, `REPORT_TIME_ZONE`, ... เป็นของ API — ค่าที่ client ส่งมาถูกตัดทิ้ง
- ค่าที่แปลงไม่ได้ → `400 PARAMETER_INVALID` ระบุชื่อ parameter (ไม่ส่งค่า `null` เงียบๆ แบบเดิม)
- API ตั้ง time zone ของ JVM เป็น `report.timezone` (`Asia/Bangkok`) เพื่อไม่ให้ผลขึ้นกับเครื่อง/container; ไม่ตั้ง `REPORT_LOCALE` ให้ (ใช้ locale ของ JVM) เพราะ `th_TH` จะแสดงปี พ.ศ. ใน pattern วันที่ — ตั้งได้ด้วย `report.locale`

### `POST /api/v1/reports/validate`

ใช้ body เดียวกับ `render` (ไม่ต้องมี `parameters`) ตอบ JSON เช่น

```json
{ "report": "th_demo.jrxml", "bundle": "s3://reports/samples/demo/", "version": "71e9fff3477ebf58", "tenant": "default",
  "datasource": { "declaredInReport": "opd", "requested": null, "resolved": "opd" },
  "parameters": [ { "name": "hn", "class": "java.lang.String", "hasDefault": false } ],
  "fonts": { "used": ["TH Sarabun New"], "missing": [] },
  "subreports": ["sub_info.jrxml"],
  "warnings": [] }
```

`warnings` เตือนเมื่อ query ใช้ `$P!{...}` (ต่อค่าเข้า SQL ตรงๆ เสี่ยง SQL injection), ใช้ฟอนต์ที่ไม่มี, หรือ datasource ที่ resolve ไม่ได้

---

## แหล่งที่มาของ JRXML

`mainReport.url` ตีความตาม scheme:

| รูปแบบ | ตัวอย่าง | อ่านจาก |
|---|---|---|
| ไม่มี scheme | `opd/cert/main.jrxml`, `test.jrxml` | `report.sources.local.root` (ใน container คือ `/app/reports`) ห้าม path แบบ absolute, `..` หรือ symlink ที่ออกนอก root |
| `s3://` | `s3://reports/opd/cert/main.jrxml` | S3-compatible storage ด้วย credential ของ server เฉพาะ bucket ใน `S3_ALLOWED_BUCKETS` |
| `http://`, `https://` | `https://files.internal/a.jrxml` | **ปิดอยู่เป็นค่าเริ่มต้น** เปิดเฉพาะ host ใน `HTTP_ALLOWED_HOSTS`; ไม่ตาม redirect; จำกัดขนาดและเวลา; ได้ไฟล์เดียว (ไม่มี subreport/รูปประกอบ) |

> JRXML เป็น **โค้ดที่ถูกรัน** (expression เป็น Groovy/Java) — ให้เฉพาะคนที่เชื่อถือได้เขียนลง folder/bucket ของรายงานได้

### โฟลเดอร์ของรายงาน (bundle)

**โฟลเดอร์ (หรือ prefix ใน S3) ที่ไฟล์ main อยู่ = ชุดของรายงานนั้น** รวม subreport และรูป:

```
reports/opd/medical_certificate/
├── main.jrxml
├── sub_diag.jrxml
└── assets/logo.png          ← IMAGE_DIR และ REPORT_ASSETS_DIR ชี้มาที่นี่
```

- เวอร์ชันของชุดรายงานคำนวณจากไฟล์ทั้งโฟลเดอร์ (ชื่อ/เวลาแก้ไข/ขนาดสำหรับโฟลเดอร์ใน local, ETag สำหรับ S3) — **ไฟล์ใดไฟล์หนึ่งเปลี่ยน ทั้งชุด compile ใหม่** request ถัดไปจึงใช้ของใหม่เอง (ตรวจไม่ถี่กว่า `report.cache.check-interval` ค่าเริ่มต้น 10 วินาที) ไม่ต้องส่ง `modified_at`
- request หนึ่งใช้เวอร์ชันเดียวตลอด ไม่ปนไฟล์เก่า/ใหม่ถ้ามีคนแก้ไฟล์ระหว่างนั้น
- รายงานหลายตัววางรวมในโฟลเดอร์เดียว (แบบ `jrxmls/` ของ pdf) ก็ใช้ได้ แต่ไฟล์ใดเปลี่ยนจะ compile ของทุกตัวในโฟลเดอร์ใหม่ — แนะนำให้แยกโฟลเดอร์ต่อรายงาน
- ผลที่ compile แล้วเก็บใน memory ต่อเวอร์ชัน (compile ครั้งเดียวแม้มีหลาย request พร้อมกัน); JRXML ที่ compile ไม่ผ่านจะตอบ `422` ทันที ไม่ใช้ของเก่าแบบเงียบๆ
- S3: โฟลเดอร์ถูกซิงก์ลง `report.cache.work-dir` ตาม ETag (เก็บ 3 เวอร์ชันล่าสุด) ไฟล์ต้องอยู่ใน folder (ห้ามวาง `x.jrxml` ที่ราก bucket เพราะจะซิงก์ทั้ง bucket); จำกัด 2000 object/100 MB ต่อโฟลเดอร์
- ไฟล์ที่ชื่อขึ้นต้นด้วย `.` ถูกข้าม

### Subreport — รองรับ 2 แบบ

1. **แนะนำ:** ประกาศ `<parameter name="SUBREPORTS" class="java.util.Map"/>` แล้วเรียก `((net.sf.jasperreports.engine.JasperReport)$P{SUBREPORTS}.get("sub_diag"))` — API compile `sub_diag.jrxml` ในโฟลเดอร์เดียวกันเมื่อถูกเรียกครั้งแรกและเก็บไว้ ไม่ต้องส่ง `subReports`
2. **แบบเดิม (แบบ pdf):** ประกาศ `<parameter name="SUBREPORT_DIR" class="java.lang.String"/>` และใช้ `$P{SUBREPORT_DIR} + "sub_diag.jasper"` — API compile subreport เป็น `.jasper` ลง `report.cache.work-dir` ให้และตั้ง `SUBREPORT_DIR` ให้

---

## การเลือก datasource

Client ส่งเฉพาะ **ชื่อเชิงตรรกะ** (`opd`, `ipd`, ...) ส่วน DB จริงอยู่ในการตั้งค่าของ server (`tenants.<tenant>.datasources.<ชื่อ>`) ลำดับการเลือก:

1. `datasource` ใน request
2. property `report.datasource` ที่ประกาศในตัว JRXML (ผูกรายงานกับ DB ที่ถูกต้อง)
3. `default-datasource` ของ tenant

```xml
<jasperReport name="medical_certificate" ...>
    <property name="report.datasource" value="opd"/>
```

- ถ้า request กับ JRXML ไม่ตรงกัน **request ชนะ** (ค่าเริ่มต้น) แต่จะบันทึก WARN และ metric `report_datasource_override_total` ไว้ให้ไล่แก้ — ตั้ง `report.datasource.allow-request-override=false` เพื่อให้ตอบ 400 `DATASOURCE_OVERRIDE_DENIED`
- ชื่อที่ไม่รู้จักได้ 400 (ต่างจาก `jasperreports-pdf` ที่ตกไปใช้ IPD เงียบๆ)
- datasource ที่ไม่ตั้ง `url` (เช่น ตัวแปร `IPD_DB_URL` ว่าง) ถือว่าไม่ได้ตั้งค่า

### หลาย tenant / หลาย DB

ใช้ YAML: คัดลอก [config/application.example.yml](config/application.example.yml) เป็น `config/application.yml` แล้ว mount ที่ `/app/config` (เปิดบรรทัดใน `compose.yaml`) Tenant เลือกด้วย header `X-Tenant-Id` หรือ `tenant` ใน body — ถ้าติดตั้งทีละที่ ใช้ `tenants.default` ตัวเดียวก็พอ (ตั้งผ่าน `OPD_DB_*`/`IPD_DB_*` ใน `.env`)

---

## การเขียนและแปลง JRXML

### ต้องเป็น JRXML ของ JasperReports 7

รูปแบบใหม่ (ไม่มี namespace, ใช้ `<element kind="...">`) — เปิดไฟล์เก่าใน **Jaspersoft Studio 7** แล้วบันทึกใหม่เพื่อแปลง ถ้าส่งไฟล์รูปแบบ 6.x มา API ตอบ `422 REPORT_COMPILE_FAILED` พร้อมคำแนะนำนี้ (ทดสอบแล้ว: `medical_certificate.jrxml` ของ `jasperreports-generater` อ่านด้วย 7.0.8 ไม่ได้) ตัวอย่างที่ใช้ได้อยู่ใน [samples/reports/demo/](samples/reports/demo/)

### ฟอนต์ไทย

ใช้ฟอนต์ **`TH Sarabun New`** (`fontName="TH Sarabun New"`) — ฝังใน PDF อัตโนมัติ ฟอนต์นี้มาจาก jar `jasper-report-api-thai-fonts:2.0.0` (SHA-256 ล็อกใน [libs/font-jar.sha256](libs/font-jar.sha256) และมีเทสต์ตรวจ) license เป็น GPL-2.0-or-later พร้อม font-embedding exception (อยู่ใน `META-INF/LICENSES` ของ jar)

- **ไม่มี** `TH SarabunPSK` (license ของ DIP&SIPA จำกัดการแจกจ่าย) และฟอนต์อื่นจาก `hosos-jasperreports-font` — JRXML ที่ใช้ฟอนต์เหล่านี้ให้เปลี่ยนเป็น `TH Sarabun New` ฟอนต์ที่ไม่มี **ทำให้ render ไม่ผ่าน** ไม่ใช่แสดงผิดเงียบๆ และ `/validate` บอกชื่อฟอนต์ที่ขาด
- การเปลี่ยน jar ฟอนต์ต้องขออนุมัติการแจกจ่ายใหม่ (การอนุมัติผูกกับ SHA-256 นี้)

### Barcode และ QR code

ใช้ component ของ JasperReports (module `jasperreports-barcode4j` รวมอยู่แล้ว) ไม่ต้องใช้ฟอนต์ barcode:

```xml
<element kind="component" x="0" y="100" width="100" height="100">
    <component kind="barcode4j:QRCode" errorCorrectionLevel="M">
        <codeExpression><![CDATA["HN:" + $F{hn} + " " + $F{name}]]></codeExpression>
    </component>
</element>
<element kind="component" x="150" y="100" width="250" height="60">
    <component kind="barcode4j:Code128">
        <codeExpression><![CDATA[$F{hn}]]></codeExpression>
    </component>
</element>
```

- `kind` ที่มี: `barcode4j:QRCode`, `Code128`, `Code39`, `EAN13`, `EAN8`, `EAN128`, `Interleaved2Of5`, `Codabar`, `UPCA`, `UPCE`, `PDF417`, `DataMatrix` ฯลฯ
- QR เข้ารหัส UTF-8 → ใส่ภาษาไทยได้ ส่วน Code128/Code39 รองรับเฉพาะ ASCII
- **วาดเป็นภาพ raster 300 ppi** (ตั้งใน [jasperreports.properties](src/main/resources/jasperreports.properties)) แทนค่าเริ่มต้นของ JasperReports ที่เป็น SVG — ตอนทดสอบ QR แบบ SVG เกิดเส้นขาวบางๆ คั่นระหว่างจุดเมื่อ render เป็นภาพแล้ว ZXing ถอดรหัสไม่ได้ ส่วนแบบ raster ถอดได้ทั้ง QR (`HN:HN001 สมชาย ใจดี`) และ Code128 (เทสต์ถอดรหัสด้วย ZXing อยู่ใน `RenderApiTest`)
- ฟอนต์ barcode แบบเก่า (`IDAutomationHC39M`) ไม่รองรับ — เปลี่ยนเป็น `barcode4j:Code39`/`Code128`

### รูปภาพ

`IMAGE_DIR` (และ `REPORT_ASSETS_DIR`) ชี้ไปที่ `assets/` ในโฟลเดอร์ของรายงาน (ถ้าไม่มี ใช้ `report.sources.images-dir` ถ้าตั้งไว้ ไม่เช่นนั้นใช้โฟลเดอร์ของรายงาน) ประกาศ `<parameter name="IMAGE_DIR" class="java.lang.String"/>` แล้วใช้ `$P{IMAGE_DIR} + "logo.png"` รูปที่ไม่มีไฟล์ทำให้ render ผิดพลาด (500)

### ภาษาของ expression

ใส่ `language="groovy"` (หรือ `java`) ที่ `<jasperReport>` ให้ชัดเจน ทั้งสองแบบ compile ได้ใน image นี้

---

## API key

**หน้าที่:** ระบุว่าใครเรียก (log/metric) และกันการเรียกโดยไม่ตั้งใจ — ไม่ได้แบ่งสิทธิ์รายรายงาน (ระบบนี้ออกแบบสำหรับ client ภายในที่เชื่อถือได้)

- สร้าง: `make api-key CLIENT=<id>` (`openssl rand` 32 byte → `jra_<base64url>`) ได้ key + SHA-256
- ฝั่ง API เก็บ **เฉพาะ SHA-256** (เปรียบเทียบแบบ constant-time); key จริงอยู่กับ client เท่านั้น
- ส่งด้วย header `X-API-Key: jra_...` หรือ `Authorization: Bearer jra_...`
- **เปลี่ยน key (rotation):** client หนึ่งรายมีได้หลาย key — เพิ่ม key ใหม่ (`REPORT_SECURITY_API_KEYS_1_*`) → ให้ client เปลี่ยน → ลบ key เก่า; ตั้ง `expires-at` หรือ `enabled: false` ได้ใน YAML

| `API_KEY_MODE` | ไม่ส่ง key | key ผิด | ใช้เมื่อ |
|---|---|---|---|
| `required` (ค่าเริ่มต้น) | 401 | 401 | production |
| `optional` | ผ่าน (นับเป็น client `anonymous`) | **401** | ช่วงย้าย client ที่ยังไม่ส่ง key — ดู `report_requests_total{client="anonymous"}` ก่อนเปลี่ยนเป็น `required` |
| `disabled` | ผ่าน | ผ่าน | dev เท่านั้น |

โหมด `required` แต่ไม่มี key ใน config → **API ไม่ยอม start**; โหมดอื่นจะเตือนตอน start และควรจำกัดเครือข่าย

---

## การตั้งค่า

ตั้งผ่านตัวแปรสภาพแวดล้อม (`.env`) หรือ `config/application.yml` ตัวเลือกอื่นของ `report.*` ตั้งผ่าน env ได้แบบ Spring (เช่น `REPORT_LIMITS_MAX_PAGES=300`)

| ตัวแปร | ค่าเริ่มต้น | ความหมาย |
|---|---|---|
| `API_KEY_MODE` | `required` | `required` / `optional` / `disabled` |
| `REPORT_SECURITY_API_KEYS_<n>_CLIENT_ID`, `_SHA256` | — | key ของ client (`<n>` = 0,1,2,...) |
| `OPD_DB_URL`, `OPD_DB_USER`, `OPD_DB_PASSWORD`, `OPD_DB_POOL_SIZE` | — / — / — / 5 | datasource `opd` ของ tenant `default` |
| `IPD_DB_URL`, `IPD_DB_USER`, `IPD_DB_PASSWORD`, `IPD_DB_POOL_SIZE` | — / — / — / 3 | datasource `ipd` (ไม่ตั้ง `IPD_DB_URL` = ไม่มี datasource นี้) |
| `REPORTS_DIR` | `./reports` | โฟลเดอร์ JRXML บน host (compose mount เป็น `/app/reports` แบบ read-only) |
| `REPORT_LOCAL_ROOT` | `reports` | root ของโฟลเดอร์ JRXML ที่ API อ่าน (ใน container คือ `/app/reports`) |
| `REPORT_CACHE_DIR` | `cache` | ที่เก็บ `.jasper` ของ subreport แบบ `SUBREPORT_DIR` และโฟลเดอร์ที่ซิงก์จาก S3 (compose ใช้ volume `report-cache`) |
| `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | — / `us-east-1` | S3-compatible storage (ใช้ path-style access); เปิดใช้เมื่อตั้งทั้ง `S3_ENDPOINT` และ `S3_ALLOWED_BUCKETS` |
| `S3_ALLOWED_BUCKETS` | — | bucket ที่อ่านได้ (คั่นด้วย `,`) |
| `HTTP_ALLOWED_HOSTS` | — | host ที่ดึง JRXML ผ่าน http(s) ได้ (คั่นด้วย `,`) |
| `API_BIND`, `API_PORT` | `127.0.0.1`, `8080` | (compose) address/port ที่เปิดบน host |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | JVM options |

ตั้งค่าอื่นของ `report.*` (ชื่อใน YAML):

| key | ค่าเริ่มต้น | ความหมาย |
|---|---|---|
| `report.timezone` | `Asia/Bangkok` | time zone ของ JVM และ `REPORT_TIME_ZONE` |
| `report.locale` | ว่าง | `REPORT_LOCALE` (ว่าง = ใช้ locale ของ JVM) |
| `report.cache.check-interval` | `10s` | ตรวจการเปลี่ยนแปลงของโฟลเดอร์รายงานไม่ถี่กว่านี้ (`0s` = ทุก request) |
| `report.cache.max-entries` | `500` | จำนวนรายงานที่ compile แล้วเก็บใน memory |
| `report.cache.keep-versions` | `3` | จำนวนเวอร์ชันของโฟลเดอร์ S3 ที่เก็บไว้ในดิสก์ |
| `report.limits.max-concurrent-renders` | `4` | จำนวน render พร้อมกัน |
| `report.limits.queue-wait` | `10s` | รอช่อง render ได้นานเท่านี้ก่อนตอบ 503 |
| `report.limits.fill-timeout` | `60s` | เวลา fill สูงสุด (ตัดด้วย governor ของ JasperReports) |
| `report.limits.query-timeout` | `30s` | PostgreSQL `statement_timeout` ของ connection |
| `report.limits.max-pages` | `500` | จำนวนหน้าสูงสุด |
| `report.sources.max-bytes` | `5MB` | ขนาด JRXML สูงสุดที่ดึงผ่าน http(s) |
| `report.sources.s3.max-objects`, `max-bundle-bytes` | `2000`, `100MB` | เพดานของโฟลเดอร์ใน S3 |
| `report.datasource.allow-request-override` | `true` | ดู [การเลือก datasource](#การเลือก-datasource) |
| `report.parameters.strict` | `false` | `true` = parameter ที่ไม่ได้ประกาศใน JRXML → 400 |

---

## การดำเนินงาน

- **Metrics** (`/api/actuator/prometheus`): `report_render_seconds{tenant,report,datasource,format,outcome}`, `report_compile_seconds`, `report_cache_requests_total{result}`, `report_inflight`, `report_requests_total{client}`, `report_requests_rejected_total{code}`, `report_datasource_override_total{...}`, และ metric ของ JVM/HikariCP
- **Log:** มี `requestId` และ `clientId` ใน MDC; ไม่บันทึกค่า parameter (อาจเป็นข้อมูลผู้ป่วย) ใช้ log format แบบ structured ได้ด้วย `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash`
- **Readiness** `UP` ต่อเมื่อทุก datasource ที่ตั้งค่าไว้ต่อได้ และ (ถ้าเปิด S3) bucket แรกใน `S3_ALLOWED_BUCKETS` มีอยู่และเข้าถึงได้
- **ข้อจำกัด:** ตอนนี้ผลลัพธ์ถูกสร้างใน memory แล้วส่งกลับทั้งก้อน (sync); รายงานหลายพันหน้าควรจำกัดด้วย `max-pages` ยังไม่มี async job, `xlsx`/`csv`, และ tenant ที่มี root/bucket ของตัวเอง (ดู phase 2 ในเอกสารออกแบบ)
- container รันด้วย user `10001`, `TZ=Asia/Bangkok`, healthcheck ที่ `/api/healthz`

---

## พัฒนาและทดสอบ

ต้องใช้ JDK 21 (`make` เลือกให้เองบน macOS)

```bash
make test        # 30 เทสต์: render จริง (ไทย/ฟอนต์ฝัง/QR/barcode/subreport), API key, limits, S3 จริงด้วย rustfs container
make build       # target/jasper-report-api-*.jar
make run         # รันในเครื่อง — ตั้ง DB/key ผ่าน env หรือ config/application.yml
```

- `S3SourceTest` ใช้ Testcontainers + `rustfs/rustfs:latest` (ข้ามอัตโนมัติถ้าไม่มี Docker)
- โครงสร้างโค้ด: `security/` (API key), `datasource/` (registry ต่อ tenant), `source/` (local, S3, http), `compile/` (compile + cache + subreport), `params/` (แปลง parameter), `render/` (fill/export/limits), `inspect/` (`/validate`), `web/` (controller, RFC 9457)
- ตัวอย่างรายงาน: [samples/reports/demo/](samples/reports/demo/), ข้อมูล demo: [samples/dev-seed.sql](samples/dev-seed.sql)

---

## ย้ายจาก `jasperreports-pdf` / `jasperreports-generater`

| เรื่อง | pdf | generater | API นี้ | client ต้องแก้ |
|---|---|---|---|---|
| Endpoint | `/api/v1/jasper/generate` | เหมือนกัน | ใช้ต่อได้ (alias) หรือ `/api/v1/reports/render` | ไม่ |
| API key | ไม่มี | ไม่มี | `X-API-Key` | โหมด `optional` ระหว่างย้าย แล้วค่อยเป็น `required` |
| DB | `datasource`: `opd`/`ipd` | `dbUrl` (JDBC+รหัสผ่าน) | `datasource` เป็นชื่อ (หรือประกาศใน JRXML) | generater: **เปลี่ยน `dbUrl` เป็นชื่อ datasource** |
| `modified_at` | ใช้เป็น cache key | ไม่มี (ใช้ MD5 ของ URL) | ไม่ใช้ (ดูการเปลี่ยนไฟล์เอง) | ไม่ |
| `type` ของ parameter | บังคับ | บังคับ | ไม่บังคับ | ไม่ |
| เวลา/timestamp | ต้องมี offset (`Z`) | ไม่มี offset (`'Z'` = literal) | รับทั้งสองแบบ; ไม่มี offset = เขตเวลา `Asia/Bangkok`; `Z` = UTC | ตรวจ generater ที่ส่ง `...Z` แต่หมายถึงเวลาท้องถิ่น |
| `datasource` ผิด | ตกไปใช้ IPD | — | 400 | ถ้าเคยส่งค่าผิดแล้วได้ IPD ต้องแก้ |
| JRXML | 6.20.6 | 6.17.0 | **7.0.8 — ต้องแปลงไฟล์** | ทีมทำรายงานแปลงด้วย Jaspersoft Studio 7 |
| ฟอนต์ | หลายฟอนต์ (ไม่มี license) | TH Sarabun | **TH Sarabun New เท่านั้น** | แก้ `fontName` ใน JRXML |
| `IMAGE_DIR` | `images/` ส่วนกลาง | — | `assets/` ในโฟลเดอร์รายงาน (หรือ `report.sources.images-dir`) | ย้ายรูปหรือตั้ง `images-dir` |
| `SUBREPORT_DIR` | ใช้ได้ | ใช้ได้ | ใช้ได้ (หรือ `SUBREPORTS`) | ไม่ |

ระหว่างที่ยังแปลง JRXML 6.x ไม่ครบ ให้เปิด `jasperreports-pdf` ไว้คู่กันสำหรับรายงานที่ยังเป็นรุ่นเก่า

---

## ความปลอดภัย (สรุป)

- JDBC URL/รหัสผ่านอยู่ที่ server เท่านั้น ใช้ DB user แบบ read-only; ไม่ log ค่า parameter
- path ของรายงานถูก normalize และตรวจ symlink; bucket/host ต้องอยู่ใน allowlist; http(s) ปิดเป็นค่าเริ่มต้น
- ผู้ที่เขียน JRXML ได้ = รันโค้ดบน server ได้ ให้สิทธิ์เขียน folder/bucket เฉพาะคนที่เชื่อถือ และเปิด API เฉพาะเครือข่ายภายใน
- query ที่ใช้ `$P!{...}` ต่อค่าจาก client เข้า SQL เสี่ยง SQL injection — `/validate` เตือนให้
