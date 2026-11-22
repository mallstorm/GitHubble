package app.githubble.mobile.mirror;

/**
 * Disposable destination-side runner for importing a public third-party Git repository.
 * Uses a per-run sealed GitHub Actions secret containing the connected destination
 * account's workflow-authorized credential. GitHub's default GITHUB_TOKEN cannot
 * write workflow files in imported branches and tags. No token is logged or copied
 * into the Git repository or kept on the runner beyond the job.
 */
public final class ExternalImportWorkflow {
    private ExternalImportWorkflow() {}

    public static final String PATH = ".github/workflows/githubble-external-import.yml";
    public static final String NAME = "GitHubble External Import";

    public static String yaml() {
        return """
name: GitHubble External Import
run-name: GitHubble Import ${{ inputs.request_id }}
on:
  workflow_dispatch:
    inputs:
      request_id:
        description: Unique GitHubble import identifier
        type: string
        required: true
      source:
        description: Public owner/repository
        type: string
        required: true
      source_default:
        type: string
        required: true
      source_sha:
        type: string
        required: true
      bootstrap_branch:
        type: string
        required: true
      bootstrap_sha:
        type: string
        required: true
permissions:
  contents: write
  actions: read
concurrency:
  group: githubble-external-import
  cancel-in-progress: false
jobs:
  import:
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - name: Import public Git history, branches, tags and LFS
        shell: bash
        env:
          SOURCE: ${{ inputs.source }}
          SOURCE_DEFAULT: ${{ inputs.source_default }}
          SOURCE_SHA: ${{ inputs.source_sha }}
          BOOTSTRAP_BRANCH: ${{ inputs.bootstrap_branch }}
          BOOTSTRAP_SHA: ${{ inputs.bootstrap_sha }}
          REQUEST_ID: ${{ inputs.request_id }}
          GH_IMPORT_TOKEN: ${{ secrets[format('GITHUBBLE_EXT_IMPORT_{0}', inputs.request_id)] }}
        run: |
          set -euo pipefail
          umask 077
          export GIT_TERMINAL_PROMPT=0
          python3 - <<'PY'
          import os, re
          a = os.environ
          repo = r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}'
          branch = r'[A-Za-z0-9_./-]{1,250}'
          assert re.fullmatch(repo, a['SOURCE'])
          assert '..' not in a['SOURCE'] and not a['SOURCE'].endswith('.')
          for key in ('SOURCE_DEFAULT', 'BOOTSTRAP_BRANCH'):
              v = a[key]
              assert re.fullmatch(branch, v) and '..' not in v
              assert not v.startswith('/') and not v.endswith('/')
          for key in ('SOURCE_SHA', 'BOOTSTRAP_SHA'):
              assert re.fullmatch(r'[0-9a-f]{40}', a[key])
          assert re.fullmatch(r'[0-9a-f]{32}', a['REQUEST_ID'])
          assert a['SOURCE'].lower() != os.environ['GITHUB_REPOSITORY'].lower()
          assert bool(a['GH_IMPORT_TOKEN']), 'Missing import credential; grant Secrets and Workflows write permissions to GitHubble'
          PY

          cat > "$RUNNER_TEMP/githubble-import-askpass" <<'ASKPASS'
          #!/bin/sh
          case "$1" in
            *Username*) printf '%s\\n' 'x-access-token' ;;
            *Password*) printf '%s\\n' "$GH_IMPORT_TOKEN" ;;
            *) exit 1 ;;
          esac
          ASKPASS
          chmod 700 "$RUNNER_TEMP/githubble-import-askpass"
          export GIT_ASKPASS="$RUNNER_TEMP/githubble-import-askpass"

          SRC_URL="https://github.com/$SOURCE.git"
          DST_URL="https://github.com/$GITHUB_REPOSITORY.git"
          git clone --bare --quiet "$SRC_URL" "$RUNNER_TEMP/githubble-import.git"
          cd "$RUNNER_TEMP/githubble-import.git"
          git cat-file -e "$SOURCE_SHA^{commit}"
          test "$(git rev-parse "refs/heads/$SOURCE_DEFAULT")" = "$SOURCE_SHA" || {
            echo "::error::Source branch changed since import confirmation"; exit 21;
          }

          # Never follow a repository-provided LFS URL or credential configuration.
          while IFS= read -r ref; do
            if git cat-file -e "$ref:.lfsconfig" 2>/dev/null; then
              echo "::error::Custom .lfsconfig requires manual review"; exit 22
            fi
          done < <(git for-each-ref --format='%(refname)' refs/heads refs/tags)

          actual="$(git ls-remote --heads "$DST_URL" "refs/heads/$BOOTSTRAP_BRANCH" | cut -f1)"
          test "$actual" = "$BOOTSTRAP_SHA" || {
            echo "::error::Destination changed after bootstrap"; exit 23;
          }
          heads="$(git ls-remote --heads "$DST_URL" | wc -l)"
          tags="$(git ls-remote --tags "$DST_URL" | wc -l)"
          test "$heads" -eq 1 && test "$tags" -eq 0 || {
            echo "::error::Destination has other refs; refusing to overwrite them"; exit 24;
          }

          git lfs fetch --all origin
          # Delete the stored OAuth secret BEFORE untrusted imported workflows
          # can be triggered by the push. The running shell already has the
          # credential in its process environment; deleting a secret does not
          # revoke the credential itself or interrupt this import step.
          SECRET_NAME="GITHUBBLE_EXT_IMPORT_${REQUEST_ID^^}"
          curl --fail --silent --show-error --request DELETE --header "Authorization: Bearer $GH_IMPORT_TOKEN" --header "Accept: application/vnd.github+json" "https://api.github.com/repos/$GITHUB_REPOSITORY/actions/secrets/$SECRET_NAME" || { echo "::error::Could not revoke secret before importing third-party workflow files"; exit 25; }
          git remote add destination "$DST_URL"
          git lfs push --all destination
          # Only Git heads and tags: never copy PR refs or GitHub-internal refs.
          # An installation or OAuth token with Workflows:write is REQUIRED when
          # those refs contain .github/workflows/*.yml. The default GITHUB_TOKEN
          # cannot authorize this GitHub API policy check.
          git push --atomic --force destination 'refs/heads/*:refs/heads/*' 'refs/tags/*:refs/tags/*'

          git show-ref --heads --tags > "$RUNNER_TEMP/source-refs.tsv"
          git ls-remote --heads --tags "$DST_URL" > "$RUNNER_TEMP/destination-refs.tsv"
          python3 - "$RUNNER_TEMP/source-refs.tsv" "$RUNNER_TEMP/destination-refs.tsv" <<'PY'
          import sys
          def refs(path):
              result = {}
              with open(path, encoding='utf-8') as f:
                  for row in f:
                      sha, ref = row.split()[:2]
                      if ref.startswith(('refs/heads/', 'refs/tags/')) and not ref.endswith('^{}'):
                          result[ref] = sha
              return result
          src, dst = refs(sys.argv[1]), refs(sys.argv[2])
          assert src and all(dst.get(ref) == sha for ref, sha in src.items()), 'Git ref verification failed'
          print('Verified', len(src), 'heads and tags; imported independent repository')
          PY
          echo "Import complete; the Android client will set the source default branch."
      - name: Revoke one-time import secret
        if: always()
        shell: bash
        env:
          REQUEST_ID: ${{ inputs.request_id }}
          GH_IMPORT_TOKEN: ${{ secrets[format('GITHUBBLE_EXT_IMPORT_{0}', inputs.request_id)] }}
        run: |
          set -euo pipefail
          rm -f "$RUNNER_TEMP/githubble-import-askpass"
          [[ -n "$GH_IMPORT_TOKEN" ]] || exit 0
          [[ "$REQUEST_ID" =~ ^[0-9a-f]{32}$ ]] || exit 0
          SECRET_NAME="GITHUBBLE_EXT_IMPORT_${REQUEST_ID^^}"
          # Delete only our own secret; WorkManager retries removal if this fails.
          # The import step normally already removed this secret before pushing
          # untrusted workflows. GitHub returns 404 for that safe, expected case.
          status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --request DELETE --header "Authorization: Bearer $GH_IMPORT_TOKEN" --header "Accept: application/vnd.github+json" "https://api.github.com/repos/$GITHUB_REPOSITORY/actions/secrets/$SECRET_NAME")" || status="000"
          if [[ "$status" != "204" && "$status" != "404" ]]; then
            echo "::warning::GitHub Actions could not revoke import secret (HTTP $status); Android recovery will retry"
          fi
""";
    }
}
