# เปรียบเทียบ `jasperreports-generater` กับ `jasperreports-pdf`

> สำรวจเมื่อ: 2026-10-06
> วิธีสำรวจ: อ่านโค้ด, `pom.xml`, Dockerfile, docker-compose, properties และ git log ของทั้งสอง repo (static analysis เท่านั้น **ยังไม่ได้ build / รันจริง**)

## 1. สรุปสั้น

ทั้งสองโปรเจกต์คือ **บริการ REST (Spring Boot 2.5.2 / Java 8) ที่รับ JRXML + parameter แล้วคืน PDF ด้วย JasperReports** โดย `jasperreports-pdf` เป็น **รุ่นต่อยอด (v2) ของ `jasperreports-generater`** — โครงสร้างแพ็กเกจ, endpoint, DTO, exception handler และ healthcheck เหมือนกันเกือบทั้งหมด (ไฟล์ `ErrorMessage`, `ReportGenerationException`, `HealthcheckController` เหมือนกันทุกบรรทัด ต่างแค่ package)

ความต่างหลักอยู่ที่ **"วิธีเชื่อมต่อฐานข้อมูล"**, **"กลไก cache ของ .jasper"**, **"แหล่งที่มาของ JRXML"** และ **ความพร้อมใช้งาน production (logging / Sentry / profile)**

| | `jasperreports-generater` | `jasperreports-pdf` |
|---|---|---|
| ลำดับเวลา | commit แรก 2021-09-15, ล่าสุด 2024-01-30 (4 commits) | commit แรก 2023-09-08, ล่าสุด 2025-07-23 (13 commits) |
| ตำแหน่งใน lifecycle | v1 / prototype | v2 / production-oriented |
| Git remote | `somprasongd/jasperreports-generater` | `somprasongd/jasperreports-pdf` |

## 2. สิ่งที่เหมือนกัน

- **Stack:** Spring Boot 2.5.2, Java 1.8, Maven (wrapper 3.8.1), Lombok, Groovy 2.0.1, PostgreSQL driver, ZXing (barcode/QR)
- **Endpoint เดียวกัน:** `POST /api/v1/jasper/generate` (context path `/api`) และ `GET /api/healthz`
- **Request shape:** `mainReport {name,url}`, `subReports[]`, `parameters[{name,type,value}]`
- **ชนิด parameter เดียวกัน:** `string, integer, number, date, time, timestamp, bool, array_str, array_int` (แปลงใน `ParameterDto.getConvertedValue()`)
- **Flow หลัก:** โหลด/คอมไพล์ JRXML → บันทึก `.jasper` ลง `jaspers/` → compile sub-report → inject `SUBREPORT_DIR` → `fillReport` ผ่าน JDBC connection → `exportReportToPdfStream`
- **Error response:** `ReportGenerationException` → HTTP 500 JSON `{statusCode, error, message}` (ไม่มี handler สำหรับ validation error)
- **ฟอนต์ไทย:** แพ็กด้วย jar `hosos-jasperreports-font` แบบ `system` scope ใน `libs/` (THSarabunNew, IDAutomationHC39M, mmrtext ฯลฯ)
- **Docker:** `openjdk:8-jdk-alpine`, `apk add ttf-dejavu`, รันเป็น user `spring`, ต้อง build jar ด้วย Maven ก่อนแล้ว `COPY` เข้า image
- **Healthcheck ใน compose:** `curl -f http://127.0.0.1:8080/api/healthz`

## 3. สิ่งที่ต่างกัน

### 3.1 การเชื่อมต่อฐานข้อมูล (ต่างที่สุด)

| | generater | pdf |
|---|---|---|
| ผู้กำหนด DB | **client ส่ง `dbUrl` มาใน request** (JDBC URL พร้อม user/password) | **client ส่งแค่ชื่อ `datasource`** (`opd` / `ipd`) |
| Connection | `DriverManager.getConnection(dbUrl)` ทุก request (ไม่มี pool) | `DataSource` 2 ตัว (`dataSourceOPD`, `dataSourceIPD`) + `JdbcTemplate` ผ่าน `DatabaseConfig` / `JdbcTemplateConfig` (HikariCP pool) |
| ที่เก็บ credential | อยู่ใน request body ของผู้เรียก | อยู่ใน properties / env var (`SPRING_DATASOURCE_OPD_*`, `..._IPD_*`) |
| จำนวน DB | ไม่จำกัด (multi-tenant ตามที่ client ส่งมา) | คงที่ 2 ก้อน; ค่าอื่นที่ไม่ใช่ `opd` ตกไปใช้ **IPD** ทั้งหมด (`else`) |
| Dependency | ไม่มี `spring-boot-starter-jdbc` | มี `spring-boot-starter-jdbc` |

### 3.2 แหล่งที่มาของ JRXML

| | generater | pdf |
|---|---|---|
| `url` | ต้องเป็น URL (`new URL(url).openStream()`) เช่น MinIO | เป็น URL **หรือ** ชื่อไฟล์ใน `jrxmls/` (ตรวจด้วย `isValidURL`) |
| โฟลเดอร์ | `jaspers/` | `jaspers/`, `jrxmls/`, `images/` (ทั้งหมดประกาศ `VOLUME`) |
| parameter ที่ระบบ inject | `SUBREPORT_DIR` | `SUBREPORT_DIR` และ `IMAGE_DIR` (ชี้ `images/`) |
| ตัวอย่าง JRXML ใน repo | มี 5 ไฟล์ใน `src/main/resources/static/` (medical_certificate, sub1, sub2, sub_diag_rk01x/02x) | ไม่มี (เก็บภายนอกผ่าน volume) |

### 3.3 กลไก cache / versioning ของ `.jasper`

| | generater | pdf |
|---|---|---|
| Key ของ cache | `MD5(url)` ต่อท้ายชื่อไฟล์: `name.<HASH>.jasper` | `modified_at` (epoch ที่ client ส่งมา): `name.<modified_at>.jasper` |
| เมื่อเนื้อไฟล์เปลี่ยนแต่ URL เดิม | **ไม่ถูก recompile** (hash ไม่เปลี่ยน) ต้องเปลี่ยน URL หรือลบไฟล์เอง | recompile เมื่อ client ส่ง `modified_at` ใหม่ |
| Sub-report | ไฟล์ `name.<HASH>.jasper` | ไฟล์ `name.jasper` + ไฟล์ marker ว่าง `name::<modified_at>`; ลบ marker เวอร์ชันเก่าก่อนสร้างใหม่ |
| ไฟล์ `.jasper` เสียหาย | main: `loadJasperReport` คืน `null` → **NPE ตอน fill**; sub: ข้ามไปเฉยๆ ไม่ recompile | main: คอมไพล์ใหม่ทับ; sub: เข้าสู่ขั้น recompile |

> หมายเหตุ: pdf ย้าย `getHash()` ออกจาก `JasperDto` และเพิ่มฟิลด์ `modified_at` แทน

### 3.4 การแปลง parameter (`ParameterDto`)

| ชนิด | generater | pdf |
|---|---|---|
| `date` | `SimpleDateFormat("yyyy-MM-dd")` → `java.util.Date` | `DateTime.parseDate` (รูปแบบเดิม) |
| `time` | `HH:mm:ss` → `java.util.Date` | `HH:mm:ssX` (UTC) → **`java.sql.Time`** |
| `timestamp` | `yyyy-MM-dd'T'HH:mm:ss'Z'` → `java.util.Date` (`'Z'` เป็น literal ไม่ใช่ timezone) | RFC3339 `yyyy-MM-dd'T'HH:mm:ssX` (UTC) → **`java.sql.Timestamp`** |
| เหตุผล (จาก comment/commit) | – | ถ้าใช้ `java.util.Date` แล้ว query จะตัดเวลาเป็น 00:00:00 (commit `update java.util.Data to java.sql.Timestamp`) |
| รูปแบบ client | **เวลาไม่มี timezone offset** | **ต้องมี offset/`Z`** เช่น `10:30:00Z` ← **breaking change** หากย้าย client จาก generater มา pdf |
| Parse ผิด | พิมพ์ stacktrace แล้วคืน `null` | พิมพ์ error แล้วคืน `null` (time/timestamp ไม่ NPE) |

### 3.5 Dependency และเวอร์ชัน

| | generater | pdf |
|---|---|---|
| JasperReports | 6.17.0 | **6.20.6** + `jasperreports-fonts`, `-metadata`, `-functions` |
| Barcode | ZXing 3.4.1 | ZXing 3.5.0 + `barbecue` 1.5-beta1 + `barcode4j` 2.1 |
| PostgreSQL driver | 42.6.0 | 42.7.2 |
| Lombok | 1.18.20 | 1.18.30 |
| Font jar | `hosos-jasperreports-font` 1.0.0 | `hosos-jasperreports-font` **1.1.1** (เพิ่มฟอนต์ RSU, Arthit, AngsanaDSE ฯลฯ); ยังเก็บ 1.0.0 ค้างใน `libs/` แต่ pom อ้าง 1.1.1 |
| ไลบรารีภายใน | – | `hospital-os-utils` 1.0.0 (จาก hosv4) |
| Observability | – | `logstash-logback-encoder` 7.0.1, **Sentry** 7.20.0 (`sentry-spring-boot-starter`, `sentry-logback`) |
| Test | `spring-boot-starter-test` + `contextLoads` 1 เทส | **ไม่มี** test dependency / โฟลเดอร์ `src/test` |

### 3.6 Configuration / Profile / Logging

| | generater | pdf |
|---|---|---|
| Profile | ไม่มี (ไฟล์ `application.properties` ไฟล์เดียว) | `dev` (ค่าเริ่มต้น) / `prod` (Dockerfile ส่ง `--spring.profiles.active=prod`) |
| Log format | console ปกติ | JSON (Logstash encoder) ผ่าน `logback-spring.xml` + Sentry appender (WARN ขึ้นไป) |
| Sentry | ไม่มี | ส่ง exception + tracing span ใน service (`service.generateReport`, `loadMainReport`, `compileSubReports`, `createPDF`); README ใช้ header `sentry-trace` |
| Prod config | – | ใช้ placeholder `${...}` ให้ env/property ภายนอกเติม (DB, DSN, sample rate ฯลฯ) |
| Redis | ปิด/ลบแล้ว (เหลือ `spring.redis.timeout` ค้างใน properties และ compose ที่ comment ไว้) | ลบแล้ว (commit `remove redis`) |

### 3.7 Deployment

| | generater | pdf |
|---|---|---|
| Image | `somprasongd/jasperreports-generater` | `somprasongd/jasperreports-pdf` |
| compose version | 2.4 | 3.8 (มี `build: .`) |
| Port (host) | **9099** → 8080 | **9091** → 8080 (README ตัวอย่างใช้ **9090** — ไม่ตรงกับ compose) |
| Volume | `./jaspers:/app/jaspers` | ปิดไว้ (comment) — เปิดเมื่อรันหลาย container เพื่อ share `.jasper` / `.jrxml` |
| ENV | ไม่ต้องใช้ (DB มากับ request) | ต้องกำหนด `SPRING_DATASOURCE_OPD_*` / `IPD_*` |
| README build | `docker buildx ... --push multi-platform` | `./mvnw clean package -DskipTests` → `docker build` (ระบุ multi-platform เป็น comment) |
| Artifact ใน repo | มี `out/artifacts/generater_jar/generater.jar` (~43 MB) **ถูก track ใน git** | ไม่มี |

### 3.8 โค้ดและ error handling

- **Controller:** generater โยน checked exception ออกมา (`throws IOException, JRException, SQLException`) และมีโค้ด comment ทิ้งจำนวนมาก; pdf ห่อ `JRException | IOException` เป็น `ReportGenerationException` แล้วสะอาดกว่า
- **Service:** generater เป็น method เดียวยาวๆ; pdf แยกเป็น `loadMainReport` / `compileSubReports` / `createPDF` + helper (`deleteFilesWithPrefix`, `createVersionFile`, `removeFileExtension`) และรองรับชื่อไฟล์ที่มี `.jrxml`
- **Exception handler:** เหมือนกัน (pdf มีโค้ด Sentry + request-body capture ที่ comment ทิ้งไว้)
- **DTO `ReportDto`:** `dbUrl` ↔ `datasource`; `JasperDto`: `getHash()` ↔ `modified_at`

## 4. ข้อสังเกต / ความเสี่ยง (ที่พบจากการอ่านโค้ด)

**ใช้ได้กับทั้งสอง**
1. **Validation ไม่ทำงาน** — `@NotBlank/@NotNull` อยู่ใน DTO แต่ `@RequestBody` ไม่มี `@Valid` และไม่มี handler รองรับ จึงไม่มีการตรวจจริง (pdf ยังใช้ `@NotBlank` กับ `long modified_at` ซึ่งใช้กับ primitive ไม่ได้)
2. **ไม่มี authentication** บน endpoint
3. **Log parameter ทั้งหมด** ระดับ INFO (`name:value:class`) — อาจมีข้อมูลผู้ป่วยหลุดลง log; และ `params.get(key).getClass()` จะ NPE เมื่อค่า parameter เป็น `null` (เช่น date/time parse ผิด)
4. **`new URL(url).openStream()`** ดึง JRXML จาก URL ที่ client ระบุ → ความเสี่ยง SSRF / รัน JRXML ที่ไม่น่าเชื่อถือ (JRXML รันโค้ด Groovy/Java expression ได้)
5. Groovy `groovy-all` 2.0.1 และ Spring Boot 2.5.2 / `openjdk:8-alpine` เก่ามากและ EOL
6. `ParameterDto` มี `@Value("${some.key:string}")` บนฟิลด์ใน DTO ซึ่งไม่มีผล (DTO ไม่ใช่ Spring bean) — `type` ที่ไม่ส่งมาจะ NPE ที่ `switch`

**เฉพาะ generater**
- `dbUrl` ที่มี user/password รับผ่าน request: เสี่ยงต่อการรั่วไหลของ credential, ผู้เรียกเชื่อมต่อ DB ใดก็ได้ที่ container เข้าถึง, ไม่มี connection pool
- cache key เป็น hash ของ URL → แก้ JRXML แล้วไม่ถูก recompile (stale report)
- main `.jasper` เสียหาย → NPE; sub-report เสียหาย → ถูกข้ามแบบเงียบ
- ไฟล์ `out/.../generater.jar` 43 MB ถูก commit

**เฉพาะ pdf**
- โหมด local file: `JRXML_DIR + File.separator + path` ไม่ normalize/ตรวจ path → **path traversal** (`../`) ได้ แม้จะบังคับนามสกุล `.jrxml` (และตรวจ `isFile()` ก่อน `exists()` ซึ่งซ้ำซ้อน)
- `datasource` ที่ไม่ใช่ `opd` ตกไป IPD เงียบๆ (typo จะไม่ error)
- `modified_at` ต้องให้ client ส่ง "เวลาแก้ไขจริง" มาให้ถูกต้อง มิฉะนั้น cache ค้าง; sub-report ใช้ marker file ชื่อ `name::<ts>` ซึ่งเสี่ยงบน filesystem ที่ไม่รองรับ `:` (เช่น Windows)
- `application.properties` ตั้ง `spring.profiles.active=dev` เป็นค่าเริ่มต้น และ `application-dev.properties` มี credential `postgres/postgres` hard-code (เฉพาะ dev)
- prod properties มี placeholder (`sentry.sample.rate`, `sentry.traces.sample.rate`, `sentry.enable.tracing` ฯลฯ) ที่ `docker-compose.yml` ไม่ได้กำหนด — ถ้าไม่มี env เติมอาจ start ไม่ขึ้น (**ยังไม่ได้ทดสอบ**)
- `logback-spring.xml` เปิด `DEBUG` ของ Hikari/`java.sql`/Spring JDBC ซึ่งใน prod จะ log ปริมาณมากและอาจมีข้อมูลละเอียดอ่อน
- ไม่มี test ใดๆ
- มี jar 1.0.0 ของฟอนต์ค้างใน `libs/` ที่ไม่ได้ใช้
- ค่า healthcheck ใช้ `curl` ซึ่ง image `openjdk:8-jdk-alpine` ปกติไม่มี (**ยังไม่ได้ตรวจใน image จริง**) — ปัญหาเดียวกันกับ generater

## 5. คำแนะนำการเลือกใช้

- **งานใหม่ / ใช้งานจริง:** ใช้ **`jasperreports-pdf`** — credential ไม่ผ่านเครือข่ายต่อ request, มี connection pool, cache ที่ควบคุมได้ด้วย `modified_at`, รองรับ local JRXML และ `IMAGE_DIR`, มี observability (Sentry + JSON log), JasperReports ใหม่กว่า และฟอนต์ครบกว่า
- **`jasperreports-generater`:** เหมาะกับงานที่ต้องให้ client ชี้ DB เองหลายปลายทาง (multi-tenant แบบไม่ตั้งค่าไว้ล่วงหน้า) หรือใช้เป็นต้นแบบ — แต่ถ้าจะเก็บไว้ควรรับความเสี่ยงข้อ 4 ให้ได้

### ถ้าจะย้าย client จาก generater → pdf ต้องปรับ
1. แทน `dbUrl` ด้วย `datasource: "opd" | "ipd"` และตั้ง env ของ DB ให้ container
2. เพิ่ม `modified_at` ให้ `mainReport` และทุก `subReports[]`
3. ปรับรูปแบบ `time` เป็น `HH:mm:ssZ`/`HH:mm:ss+07` และ `timestamp` เป็น RFC3339 (มี offset)
4. ถ้า JRXML ใช้รูปภาพ ให้ใช้ `$P{IMAGE_DIR}` และ mount `images/`
5. ตรวจว่า JRXML เข้ากับ JasperReports 6.20.6 (ต้อง recompile; `.jasper` จาก 6.17.0 ใช้ข้ามเวอร์ชันไม่ได้ จึงควรเริ่มด้วย `jaspers/` ว่าง)
6. พอร์ตเปลี่ยนจาก 9099 → 9091 (ตาม compose)

## 6. ภาคผนวก — โครงสร้างโค้ด

```
jasperreports-generater/src/main/java/.../generater        jasperreports-pdf/src/main/java/.../pdf
├── JasperreportsGeneraterApplication                      ├── JasperreportsGeneraterApplication
├── controller/                                            ├── config/                  ← เพิ่ม
│   ├── HealthcheckController                              │   ├── DatabaseConfig       (2 DataSource)
│   └── JasperreportsGenneraterController                  │   └── JdbcTemplateConfig
├── dto/ (JasperDto[hash], ParameterDto, ReportDto[dbUrl]) ├── controller/ (เหมือนเดิม)
├── exception/ (3 ไฟล์)                                    ├── dto/ (JasperDto[modified_at], ParameterDto, ReportDto[datasource])
└── service/ReportService                                  ├── exception/ (3 ไฟล์ เหมือนเดิม)
                                                           ├── service/ReportService   (แยก method + Sentry span)
                                                           └── util/DateTime           ← เพิ่ม
```

> หมายเหตุ: ชื่อ `Gennerater` (n สองตัว) ใน `JasperreportsGenneraterController` และ `Generater` ในชื่อโปรเจกต์/คลาสหลัก สะกดผิดจาก "Generator" ทั้งสอง repo (ยังคงสะกดเดิมเพื่อไม่ให้ชื่อคลาส/Main-Class ใน MANIFEST เสีย) — `jasperreports-pdf` ยังใช้ชื่อคลาสหลัก `JasperreportsGeneraterApplication` ซึ่งเป็นร่องรอยว่า fork มาจาก generater
