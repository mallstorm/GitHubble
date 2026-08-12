# GitHubble 1.0 RC — device matrix

| API | Android | Form factor | Required RC checks |
|---:|---|---|---|
| 28 | 9 | Phone | Device Flow, process death, offline queue, file downloads |
| 30 | 11 | Phone | Scoped storage, Custom Tabs, notifications |
| 33 | 13 | Phone | POST_NOTIFICATIONS, background jobs, large text |
| 34 | 14 | Phone | task relaunch after auth, battery restrictions |
| 35 | 15 | Phone | edge-to-edge compatibility, foreground restrictions |
| 36 | 16 | Phone | targetSdk 36 regression |
| 33–36 | 13–16 | Tablet 7–12″ | adaptive columns, rotation, split diff |
| 33–36 | MIUI/HyperOS | Xiaomi/Redmi/POCO | autostart, JobScheduler and battery diagnostics |

No device result is marked PASS until executed on physical hardware or an emulator.
