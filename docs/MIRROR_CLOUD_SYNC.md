# Mirror Manager Cloud Registry — Android SAF

GitHubble can store its **linked repository pair registry** in ONE user-selected JSON document in a cloud storage provider visible through Android's system document picker (Storage Access Framework).

## Cloud choices

- Google Drive: supported through a DocumentsProvider if available on the phone.
- Microsoft OneDrive: supported when the OneDrive integration appears in the Android document picker with read/write access.
- Yandex Disk: supported ONLY when its installed Android application exposes documents through the Android DocumentsProvider. This is not a dedicated Yandex REST API/OAuth connection. If it is missing from the picker, pick another provider or request a native Yandex implementation.
- An arbitrary compatible cloud provider or local folder document can also be selected; no client ID, redirect URI or embedded OAuth secret is necessary.

## Setup in the app

1. Settings → Account and GitHub → Mirror setup → Cloud registry.
2. Tap **Create cloud registry JSON** to choose a provider and create GitHubble-Mirror-Registry.json, or **Connect existing JSON** on another device.
3. Android grants long-lived URI read/write permission to this exact selected file only. GitHubble immediately merges local and cloud links.
4. Tap **Synchronize now** any time in Settings or Mirror Manager. Background WorkManager sync retries after new links and periodically (about 1 hour), subject to Android/provider availability.
5. Disconnect to release the document grant; connected repositories and local links remain untouched.

## Data model

Only an undirected list of linked GitHub repository names, stable pair IDs, original owner, original copyJobId and timestamps is stored in the cloud file. **No GitHub OAuth credentials, GitHub App keys, tokens, workflow secrets, repository source code, LFS blobs or local OneShot action history are uploaded.** The cloud JSON is not end-to-end encrypted, so project names may be visible to the chosen cloud provider.

Schema v1: {format: githubble-mirror-links, schema: 1, exportedAt: unixMilliseconds, links: [{id,left,right,original,copyJobId,createdAt,updatedAt}]}

Import validates pair hashes, that the original belongs to the pair, uniqueness, maximum 10000 pairs and file size <=2 MB. An unrelated/corrupt JSON fails without overwriting it. On sync links are merged as a union, not replaced; the registry does not currently implement deletion. A cloud file written concurrently by two devices cannot provide atomic compare-and-swap through generic Android SAF. WorkManager and explicit manual resync provide eventual best-effort convergence; avoid simultaneous edits on separate phones.

## Platform limitations

- Provider availability in Android DocumentsUI varies by installed app, Android distribution, and provider implementation.
- Storage may be offline or cached. The last sync time and an error indicator are shown. Background work is best effort.
- A new device or reinstall requires selecting the existing JSON file again in Android's file picker.
- This is the **linked-pair registry** only. The operation history is still stored locally and does not recover from the cloud document.
- A single selected registry file is active at a time. Multi-cloud replication and native vendor OAuth integrations are separate possible future features.
