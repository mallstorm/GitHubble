# GitHubble beta audit — stages 73–84

## Passed

- PR creation payload and input validation.
- Review events: COMMENT / APPROVE / REQUEST_CHANGES.
- Merge methods filtered by repository capabilities.
- Issue/PR metadata editor.
- Star and follow operations use zero-length PUT requests.
- Unsupported GitHub App repository-subscription mutation removed.
- Entity cache with ETag/TTL and safe fallback rules.
- Route and scroll restoration without secrets in Bundle.
- Repository/Profile/Translation controllers extracted.
- Auth and pagination regression suite compiled.
- Large-font and tablet adaptive rules present.
- Privacy-safe diagnostics export.
- Reproducible unsigned and signed APK verification.

## Remaining beta risks

1. GitHub App permissions can differ between installations; 403 errors must be tested on the registered GiHubble configuration.
2. MIUI may still restrict JobScheduler until battery optimization is adjusted.
3. Android process-death and rotation need instrumentation validation on API 28, 33 and 36.
4. PR review and merge need tests against repositories with branch protection and required checks.
5. Long lists still need performance profiling with very large accounts.

## Release decision

Status: **Beta candidate for on-device testing**, not stable 1.0.
