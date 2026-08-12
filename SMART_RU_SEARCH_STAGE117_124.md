# GitHubble — stages 117–124: intelligent Russian GitHub search

## 117 — Inline RU ✦ switch
The Search input is now a framed composite control with an inline `RU ✦` toggle on the right edge. The state is persisted in local preferences and defaults on for Russian device locale.

## 118 — Query parser
`SmartSearchPlanner` tokenizes the query and separates GitHub search qualifiers before semantic processing. Qualifiers and boolean operators remain byte-for-byte in the generated variants.

## 119 — Offline technical dictionary
Added Russian technical phrase and word mappings for Android, cloud/storage, authentication, messaging, media, networking, firmware, AI, developer tooling and related domains. Common Russian inflections are covered.

## 120 — Bounded semantic expansion
A Cyrillic query produces at most three searches: primary technical English, an alternate synonym formulation, and the original Russian wording for repositories whose README/description is Russian. Latin-only queries stay direct.

## 121 — Multi-query repository aggregation
The app requests bounded pages for each variant, merges `full_name` duplicates, and tolerates a partial variant failure when at least one GitHub request succeeds.

## 122 — Client-side ranking
`RepositorySearchRanker` favors repositories returned by multiple variants, then combines GitHub position, title/description term matches, language, stars and recent activity. A repeated semantic match is intentionally stronger than raw popularity.

## 123 — Transparent UX and history
Results show `Искали также` with actual generated variants. Repositories returned by multiple variants receive a compact `RU ✦ ×N` marker. The last eight explicit searches are stored locally, up to three are shown on the Search screen, and the user can clear them immediately.

## 124 — Regression and release hardening
Added instrumentation checks for Russian expansion, qualifier preservation and multi-query dedup/ranking. Build remains unminified/unshrunk for RC diagnostics and uses the existing 0.25+ release signing line.
