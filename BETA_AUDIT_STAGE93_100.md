# GitHubble 1.0 RC1 audit

## Green gates
- package and update line preserved: `app.githubble.mobile`;
- versionCode 28 exceeds 0.27.0;
- signer SHA-256 matches 0.25–0.27;
- minified release compiles;
- AAB, mapping and SBOM are generated;
- local-data export excludes secrets;
- full local deletion is available;
- database migrations are additive;
- no private key material is present in source;
- no demo/fallback copy remains in the production UI.

## Yellow gates
- instrumentation suite is compiled but not executed on API 28/33/36;
- Custom Tabs return behavior must be tested across Chrome/MIUI variants;
- WorkManager is not used; existing JobScheduler behavior needs the device matrix;
- TalkBack, contrast and 200% font scaling need manual verification;
- PR write/merge flows depend on actual GiHubble permission installation;
- translation provider reliability depends on the user-configured endpoint.

## RC decision
Suitable for controlled on-device Release Candidate testing. Not yet labelled stable production 1.0 until the yellow gates are executed and blocking defects are resolved.
