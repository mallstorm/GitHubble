# GitHubble 0.27.0 architecture delta

`MainActivity` остаётся UI shell для native Android Platform UI. Новые этапы 85–92 разделены по предметным компонентам:

- offline: `OfflineActionQueue`, `OfflineMutationDispatcher`;
- repository administration: `RepositoryAdminController`;
- advanced PR: `PullRequestAdvancedController`;
- diff parsing: `DiffModel`;
- file preview: `FilePreviewController`;
- portable user data: `PortableSettingsManager`;
- composition: `FeatureControllerRegistry`;
- restorable UI state: `FeatureUiState`.

Сетевой токен остаётся только в `SecureStore`/`GitHubApiClient`. Offline DB, screen state и portable backup секретов не содержат.
