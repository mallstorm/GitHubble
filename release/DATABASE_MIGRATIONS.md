# Database migrations

## Tracking database 1 → 2
Additive column: `tracked_developers.last_repo_cursor TEXT NOT NULL DEFAULT ''`.
Existing developers, repository snapshots and events remain untouched.

## Offline queue 1 → 2
Additive column: `offline_actions.last_http_status INTEGER NOT NULL DEFAULT 0`.
Pending, conflicted and failed actions remain in the queue.

No destructive migration or automatic database reset is permitted in GitHubble 1.0 RC.

## Offline queue 3 → 4
Additive claim_id / claim_at columns; IN_FLIGHT rows reserve an entity while HTTP executes outside database locks. Interrupted leases become UNKNOWN and require server reconciliation before explicit retry. Existing PENDING, CONFLICT and FAILED rows survive.
