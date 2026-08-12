# Release checklist — GitHubble 1.0 RC1

## Build and identity
- [x] package `app.githubble.mobile`
- [x] versionCode 28
- [x] versionName `1.0.0-rc1-stage93-100`
- [x] minify and resource shrink
- [x] signed APK
- [x] signed AAB
- [x] signer continuity with 0.27.0
- [x] mapping file
- [x] SPDX SBOM

## Security and privacy
- [x] no Client Secret/private key in source or APK
- [x] token remains in Android Keystore
- [x] safe user-data export
- [x] full local-data deletion
- [x] privacy policy and data-safety draft

## Device gates
- [ ] API 28 instrumentation
- [ ] API 33 instrumentation
- [ ] API 36 instrumentation
- [ ] MIUI/HyperOS background tracking
- [ ] TalkBack and 200% font scale
- [ ] tablet portrait/landscape
- [ ] process death during repository/PR screens
- [ ] offline queue conflict resolution
- [ ] GitHub App permission matrix on a test organization

## Store gates
- [x] adaptive icons
- [x] feature graphic
- [x] screenshots
- [x] localized listing and release notes
- [ ] final public privacy-policy URL
- [ ] final Play Console data-safety answers
