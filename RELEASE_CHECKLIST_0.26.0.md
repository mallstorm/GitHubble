# Release checklist — GitHubble 0.26.0

## Build and identity

- [x] Package `app.githubble.mobile`
- [x] versionCode 26
- [x] versionName `0.26.0-beta-stage73-84`
- [x] minSdk 28 / targetSdk 36
- [x] Client ID injected at build time
- [x] Client Secret absent
- [x] Signing key absent from source and delivery archives

## Verification

- [x] Offline Gradle build
- [x] JVM regression tests
- [x] Instrumentation APK compiled
- [x] ZIP integrity
- [x] zipalign
- [x] APK Signature Scheme v3
- [x] Reproducible clean build comparison
- [x] Source secret scan
- [ ] Instrumentation tests on physical Android
- [ ] API 28 device test
- [ ] API 33 device test
- [ ] API 36 device/emulator test
- [ ] Tablet test
- [ ] MIUI background tracking test

## Functional smoke test

- [ ] Device Flow login and return to profile
- [ ] Search and open repository
- [ ] Create test Issue
- [ ] Create test Pull Request
- [ ] Submit review
- [ ] Merge test PR in disposable repository
- [ ] Apply label/assignee/milestone
- [ ] Star/unstar repository
- [ ] Follow/unfollow test user
- [ ] Offline reopen cached repository
- [ ] Kill process and restore current screen
- [ ] Export diagnostics ZIP and inspect for secrets
