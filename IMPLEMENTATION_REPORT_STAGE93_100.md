# GitHubble 1.0.0 RC1 — implementation report, stages 93–100

## 93. RC instrumentation package

A dependency-free instrumentation runner now covers:
- eight-character Device Flow normalization;
- REST and GraphQL pagination state;
- rotation/state restoration through `Bundle`;
- simulated process recreation through `ScreenStateStore`;
- durable offline queue enqueue/retry/clear behavior;
- tracking and offline database opening on schema v2;
- GitHub HTTP error objects for 401, 403 and 422;
- diff parsing.

The instrumentation APK compiles successfully. No emulator or physical device was available, so API 28/33/36 runs remain an on-device release gate.

## 94. Performance hardening

Added `PerformanceMonitor` with local-only cold-start and memory snapshots. Large image previews now use bounds-first sampled decoding rather than always allocating the original resolution. The RC status screen exposes cold-start and heap diagnostics without uploading telemetry.

## 95. Release automation

Added:
- reproducible Gradle build command for APK, AAB and instrumentation APK;
- R8 mapping output;
- release-key continuity verification;
- APK zipalign and v3 signing;
- AAB signing;
- SPDX 2.3 SBOM generation;
- CI artifact workflow;
- SHA-256 manifest.

No signing key or password is included in source or delivery archives.

## 96. Release Candidate matrices and migrations

Prepared device, GitHub App permission and database-migration matrices. Tracking database schema 1→2 and offline queue schema 1→2 are additive and preserve user data. No destructive migration is used.

## 97. Accessibility hardening

Added runtime guardrails for:
- minimum 48 dp interactive targets;
- content descriptions on interactive images;
- forward focus order matching visual order;
- system font scaling and large-text layout;
- text-labelled destructive actions.

TalkBack and 200% font-scale checks still require devices.

## 98. Privacy and user-data controls

New `Настройки → Конфиденциальность и данные` screen provides:
- clear explanation of local storage and third-party requests;
- safe JSON export excluding OAuth token, translation key and mutation bodies;
- irreversible deletion of token, profile cache, translations, favorites, tracking history, offline queue and settings;
- explicit statement that local deletion does not delete GitHub content.

## 99. Store-ready package

Added:
- Android adaptive icons for light, beige and dark launcher variants;
- 1024×500 feature graphic;
- framed phone screenshots;
- Russian and English store descriptions;
- Russian release notes;
- privacy policy draft;
- data-safety declaration draft.

## 100. GitHubble 1.0 RC1 freeze

Version moved to `1.0.0-rc1-stage93-100`, versionCode 28. Release build now enables R8 minification and resource shrinking. Demo-oriented UI copy was removed. The in-app RC screen states that major feature development is frozen until stable 1.0; only defects, compatibility, migrations, accessibility and performance are accepted.

## Result

The original functional development line has reached Release Candidate status. The remaining work is physical-device validation and defect correction, not another broad feature expansion.
