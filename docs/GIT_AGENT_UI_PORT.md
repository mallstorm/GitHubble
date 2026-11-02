# Git Agent native UI port

Target: PR #22, feature/omnichat-native-agent-phase1. No merge into main.

## Source

OmniChat source reference: Digger401/OmniChat at
407cc6722f52f2e360b8e4449e48d98f2989e509.
WelcomeBrainView directly adapts WelcomeBrainArt's original Canvas paths,
coordinates, colors, orbits and stroke widths from OmniChatRoot.kt.
This is the actual drawing used by the reference screen, rather than a robot
placeholder or a generated illustration. The separate welcome WebP in that
repository failed decoding and is not used by the current reference Canvas.
The white and graphite palettes are taken from OmniThemes.kt. Composer radius,
input height, role names, sheet handle and connection cards follow OmniChatRoot.
The implementation remains Android Views because the shipping GitHubble app
uses Java/Views; the archived Compose source set is not enabled by its build.

## Working integration

- Git Agent branding, original welcome art, centered toolbar, multi-line composer.
- Real per-account independent conversations, search, rename, delete and selection.
- Persistent per-account/per-thread drafts; choosing a model never creates a thread.
- Connected API models, search and API/subscription filters, encrypted credentials,
  retained server model catalog, immediate model selection and refresh.
- Chat/own agent/Copilot are distinct modes. Chat text cannot dispatch repository
  creation or workflow commands. Cloud Copilot runs through the existing bridge.
- Text attachment preview, existing speech input and cancel-request handling.
- Existing code generation, file preview, explicit commit approval, workflow
  selection, CI diagnostics, repair proposals and artifact downloads are retained.
- Approved commits are serialized and recheck the pinned HEAD to prevent a
  duplicate proposal from being committed twice.
- A request's provider credentials and model are captured before asynchronous
  work; switching accounts does not substitute a new account's credentials.
- Other root screens keep their existing theme and GitHub controllers.

## Explicit limits

This is a native adaptation, not a declaration of complete pixel equivalence.
OAuth subscription transports, Subscription Proxy, multi-role orchestration,
automatic execution, image analysis/generation, camera/gallery upload, OCR,
web-search and plugin execution are not integrated. Their UI explains this;
they are not advertised as authorized or running. Orchestration roles and
automatic approval are disabled; a single executor uses the selected API model.
Live provider/Copilot authorization and remote writes require the owner's real
account and are not exercised by credential-free CI.

## Verification

The PR workflow runs `test assembleDebug assembleRelease`, then compiles the
instrumentation APK and runs the existing regression suite plus GitAgentUiChecks
on Android 13 (API 33). Added checks cover separate conversation histories,
cross-account isolation, recreated-store state, both rendered themes, branding,
draft hydration, integrated composer restoration and modal-window screenshots.
Screenshots include the keyboard, model sheet, tools sheet, agent dialog and
drawer. They are included with the test build for visual inspection.
CI completion, screenshot inspection and final delivery status must be reported
separately; adding these checks alone is not proof that they passed.

Material toolbar/tool icons are vector paths from google/material-design-icons
(Apache License 2.0, included in MATERIAL_ICONS_LICENSE.txt); these are the same
Material icon families used by the reference Compose screen.
The keyboard regression requires visible IME insets after a real injected tap,
and checks that the composer remains visible while the IME is open.
