# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

Add entries under `## [Unreleased]` as you work; `make release` moves them into a dated version section.

## [Unreleased]

### Added
- Render without a database: `datasource: "none"` (or no datasource at all for a report that has no `<query>`) fills with one empty record, so blank forms and parameter-only reports need no tenant or DB.
- `data` field in render and validate requests: JSON read by a report's `<query language="json">` instead of a database. Limited by `report.limits.max-data-size` (default 10MB); not logged. New error codes `DATA_AND_DATASOURCE`, `DATA_NOT_SUPPORTED`, `DATA_TOO_LARGE` and `DATASOURCE_NONE_NOT_ALLOWED`.
- `jasperreports-json` dependency.

### Changed
- `JSON_INPUT_STREAM` and `JSON_SOURCE` report parameters can no longer be set by a client.
- Release workflow actions upgraded to their Node 24 versions.

## [0.1.0] - 2026-10-06

First release.

### Added
- `POST /api/v1/reports/render` renders a JRXML report to PDF (JasperReports 7.0.8); `POST /api/v1/jasper/generate` stays compatible with the `jasperreports-pdf` request shape.
- `POST /api/v1/reports/validate` compiles a report and warns about risky constructs such as `$P!{...}` in SQL.
- Report sources: a mounted folder, S3-compatible storage (rustfs, MinIO, AWS S3) restricted to allow-listed buckets, and http(s) restricted to allow-listed hosts. Pre-signed URLs work for private buckets.
- Report folders ("bundles") with sub-reports, images and message bundles on every source, plus parallel downloads for http(s).
- Stale-if-error for S3 and http(s): the last good copy is served while the storage is down, but never for not-found, forbidden or expired pre-signed URLs.
- Per-request language (`i18n`) through message bundles stored next to the JRXML.
- Logical datasource names per tenant; JDBC URLs and passwords stay on the server.
- Embedded Thai font (TH Sarabun New), barcodes and QR codes, including Thai text.
- API keys, limits on concurrent renders, render time and page count, Prometheus metrics, health and readiness endpoints, RFC 9457 error responses.
- Docker image and a one-command demo (`make dev-up`) with PostgreSQL sample data and rustfs.
- Release workflow: a `v*` tag builds the jar, publishes the image to GHCR and creates a GitHub Release.

[Unreleased]: https://github.com/somprasongd/jasper-report-api/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/somprasongd/jasper-report-api/releases/tag/v0.1.0
