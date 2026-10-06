# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

REST API (Spring Boot 4.1, Java 21, JasperReports 7.0.8) that renders JRXML reports to PDF. Clients send only a logical datasource name (`opd`, `ipd`, ...); JDBC credentials live on the server. README.md (Thai) is the user-facing reference; `docs/design/jasper-report-api-design.md` records the design decisions and their reasons.

## Commands

`make help` lists everything. Maven needs JDK 21 (the Makefile picks it on macOS; elsewhere set `JAVA_HOME`).

```bash
make build                                   # ./mvnw -DskipTests package -> target/jasper-report-api-*.jar
make test                                    # all tests
./mvnw -B test -Dtest=RenderApiTest          # one test class
./mvnw -B test -Dtest=RenderApiTest#method   # one test method
make run                                     # spring-boot:run; config via env vars or config/application.yml
make dev-up / make dev-down                  # demo stack: API + PostgreSQL sample data + rustfs (S3) + sample report; dev-down deletes volumes
make api-key CLIENT=hosos-web                # generate an API key (scripts/api-key.sh)
```

- No linter/formatter is configured.
- `S3SourceTest` and `PresignedUrlTest` start a `rustfs/rustfs` container, and `PostgresQueryTimeoutTest` a `postgres:16-alpine` one, via Testcontainers; all are skipped without Docker.
- The Maven repo at `libs/maven-repo` is a `file://` repository holding the Thai font jar (TH Sarabun New). `FontJarPinTest` and the Dockerfile (`sha256sum -c libs/font-jar.sha256`) pin it by SHA-256 because the font distribution approval is bound to that exact jar — do not replace it without a new approval.
- CI: `.github/workflows/ci.yml` runs `./mvnw verify` and a Docker build (not pushed) on every push to `main` and every PR. `.github/dependabot.yml` opens weekly update PRs; it ignores the font jar and JasperReports major versions (a new major changes the JRXML format).
- Releases: see [Releasing](#releasing).

## Releasing

`pom.xml` is the single source of truth for the version: it is `X.Y.Z-SNAPSHOT` on `main` between releases and equals the tag (without `v`) at the tagged commit. Never hand-edit the version or push a `v*` tag yourself.

1. While working, add user-visible changes under `## [Unreleased]` in `CHANGELOG.md` (Keep a Changelog sections: Added / Changed / Fixed / Removed). Commits use conventional prefixes (`feat:`, `fix:`, `docs:`, `test:`, `build:`, `chore:`).
2. Run `make release VERSION=0.2.0` (`NEXT=0.3.0` overrides the next snapshot; `VERSION=0.2.0-rc.1` makes a pre-release). `scripts/release.sh` requires a clean `main` in sync with `origin/main` and a non-empty `[Unreleased]`, then: runs `./mvnw verify` → commits `release: v0.2.0` (pom = `0.2.0`, `[Unreleased]` renamed to `[0.2.0] - <date>` with compare links) → tags `v0.2.0` on that commit → commits `chore: start 0.2.1-SNAPSHOT` → `git push --atomic origin main v0.2.0`.
3. The tag triggers `.github/workflows/release.yml`, which fails unless the tag equals the pom version and `CHANGELOG.md` has a section for it, then runs `./mvnw verify`, pushes `ghcr.io/somprasongd/jasper-report-api` (tags `0.2.0`, `0.2`, and `0` unless pre-release) and creates the GitHub Release with the jar via `gh release create`, using that CHANGELOG section as the notes. Watch it with `gh run watch`.

If the workflow fails after the push, fix forward: delete the tag locally and on origin (`git push --delete origin vX`, `git tag -d vX`) only if the release was not created, and re-tag the fixed commit by hand; never move a tag of a published release.

## Architecture

Package root `com.github.somprasongd.jasperreport.api`. A render request flows through these layers:

1. `security/` — `ApiKeyFilter` (`X-API-Key` or `Authorization: Bearer`; a wrong key is always rejected, even in `optional` mode) and `RequestIdFilter`. `web/` holds `ReportController` and RFC 9457 problem responses (`ApiException` → `ProblemExceptionHandler`).
2. `source/` — `SourceResolver` picks a `BundleSource` by the scheme of `mainReport.url`: none → `LocalBundleSource` (mounted folder), `s3://` → `S3BundleSource` (allow-listed buckets), `http(s)://` → `HttpBundleSource` (disabled by default, allow-listed hosts, no redirects). Each materialises a **bundle** (main JRXML + sub-reports + images + message `.properties`) into a local dir as a `ResolvedBundle` whose `version` fingerprint changes whenever any file changes. Resolutions are cached for `report.cache.check-interval`; remote sources use stale-if-error (last good copy is served when storage is down, but never to mask not-found/403/expired presigned URLs).
3. `compile/` — `ReportCompiler` compiles JRXML once per bundle version (Caffeine, concurrent callers share one compile; failures are briefly cached). `LazySubreports` backs the `SUBREPORTS` parameter so `$P{SUBREPORTS}.get("sub_x")` compiles the sibling JRXML on first use.
4. `render/` — `RenderService` fills and exports with concurrency/time/page limits (`max-pages`, semaphore). `DatasourceSelector` resolves the datasource: request → `report.datasource` property in the JRXML → tenant default. `LocaleSelector` + `BundleClassLoaders` implement per-request i18n: each bundle version gets its own class loader searched before the app class path so `resourceBundle="messages"` resolves the bundle's own `messages*.properties`.
5. `datasource/` — `DataSourceRegistry` lazily builds named HikariCP pools per tenant from `tenants.*` config. `params/` — `ParameterBinder` converts request parameter values to the types declared in the JRXML. `inspect/` — `ReportInspector` backs `POST /api/v1/reports/validate`.
6. `config/` — `ReportProperties` (`report.*`), readiness indicators; `application.yml` plus `config/application.example.yml`.

Two API paths exist: `/api/v1/reports/render` and the legacy-compatible `/api/v1/jasper/generate` (request shape compatible with `jasperreports-pdf`).

## Things that are easy to get wrong

- JasperReports 7 cannot read 6.x JRXML. Test fixture `src/test/resources/reports/old6/legacy_demo.jrxml` exists to prove this; real reports must be re-saved with Jaspersoft Studio 7.
- JRXML expressions are executed code, so the source layer is the trust boundary: path normalisation/symlink checks for local, bucket and host allow-lists for S3/http. Keep those checks intact when touching `source/`.
- Request parameter values are never logged (they can be patient data). Don't add logging of them.
- Test report fixtures live in `src/test/resources/reports/`; demo reports for `make dev-up` live in `samples/reports/`.
- The `Dockerfile` builds the jar itself inside the image (`mvn package` in the build stage), independent of the jar built on the host or in CI.
