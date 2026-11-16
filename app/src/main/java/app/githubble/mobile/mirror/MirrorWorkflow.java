package app.githubble.mobile.mirror;

/** Temporary source-repository workflow. No separate controller or GitHub App PEM. */
public final class MirrorWorkflow {
    private MirrorWorkflow(){}

    public static String path(String requestId){
        if(requestId==null || !requestId.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid OneShot request ID");
        return ".github/workflows/githubble-oneshot-"+requestId+".yml";
    }

    public static String yaml() {
        return """
name: GitHubble OneShot Mirror
run-name: GitHubble OneShot ${{ inputs.request_id }}
on:
  workflow_dispatch:
    inputs:
      destination:
        type: string
        required: true
      source_default:
        type: string
        required: true
      baseline_sha:
        type: string
        required: true
      request_id:
        type: string
        required: true
      operation:
        type: choice
        required: true
        options: [copy, sync, return]
      destination_base:
        type: string
        required: false
        default: ''
      target_sha:
        type: string
        required: false
        default: ''
permissions:
  contents: read
concurrency:
  group: githubble-oneshot-${{ inputs.destination }}
  cancel-in-progress: false
jobs:
  mirror:
    runs-on: ubuntu-latest
    timeout-minutes: 55
    steps:
      - name: Validate inputs and secrets
        shell: bash
        env:
          SOURCE: ${{ github.repository }}
          TARGET: ${{ inputs.destination }}
          DEFAULT_BRANCH: ${{ inputs.source_default }}
          BASE_SHA: ${{ inputs.baseline_sha }}
          REQUEST_ID: ${{ inputs.request_id }}
          OPERATION: ${{ inputs.operation }}
          DEST_BASE: ${{ inputs.destination_base }}
          TARGET_SHA: ${{ inputs.target_sha }}
          DST_TOKEN: ${{ secrets[format('GITHUBBLE_ONESHOT_DST_{0}', inputs.request_id)] }}
        run: |
          set -euo pipefail
          python3 - <<'PY'
          import os,re
          a=os.environ
          repo=r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}'
          assert re.fullmatch(repo,a['SOURCE']) and re.fullmatch(repo,a['TARGET'])
          assert '..' not in a['SOURCE'] and '..' not in a['TARGET']
          assert a['SOURCE'].split('/')[0].lower()!=a['TARGET'].split('/')[0].lower()
          assert re.fullmatch(r'[A-Za-z0-9_./-]{1,250}',a['DEFAULT_BRANCH'])
          assert '..' not in a['DEFAULT_BRANCH'] and not a['DEFAULT_BRANCH'].startswith('/')
          assert re.fullmatch(r'[0-9a-f]{40}',a['BASE_SHA'])
          assert re.fullmatch(r'[0-9a-f]{32}',a['REQUEST_ID'])
          assert a['OPERATION'] in ('copy','sync','return')
          if a['OPERATION'] != 'copy':
              assert re.fullmatch(r'[A-Za-z0-9_./-]{1,250}',a['DEST_BASE'])
              assert '..' not in a['DEST_BASE']
              assert re.fullmatch(r'[0-9a-f]{40}',a['TARGET_SHA'])
          else:
              assert not a['DEST_BASE'] and not a['TARGET_SHA']
          assert bool(a['DST_TOKEN']), 'Destination credential missing; check permissions and secret setup'
          PY
      - name: Transfer independent Git snapshot, tags, LFS
        shell: bash
        env:
          SOURCE: ${{ github.repository }}
          TARGET: ${{ inputs.destination }}
          DEFAULT_BRANCH: ${{ inputs.source_default }}
          BASE_SHA: ${{ inputs.baseline_sha }}
          REQUEST_ID: ${{ inputs.request_id }}
          OPERATION: ${{ inputs.operation }}
          DEST_BASE: ${{ inputs.destination_base }}
          TARGET_SHA: ${{ inputs.target_sha }}
          SRC_TOKEN: ${{ github.token }}
          DST_TOKEN: ${{ secrets[format('GITHUBBLE_ONESHOT_DST_{0}', inputs.request_id)] }}
        run: |
          set -euo pipefail
          umask 077
          export GIT_TERMINAL_PROMPT=0
          cat >"$RUNNER_TEMP/oneshot-askpass" <<'ASKPASS'
          #!/bin/sh
          case "$1" in
            *Username*) printf '%s\\n' 'x-access-token' ;;
            *Password*) printf '%s\\n' "$GH_MIRROR_TOKEN" ;;
            *) exit 1 ;;
          esac
          ASKPASS
          chmod 700 "$RUNNER_TEMP/oneshot-askpass"
          export GIT_ASKPASS="$RUNNER_TEMP/oneshot-askpass"
          SRC_URL="https://github.com/$SOURCE.git"
          DST_URL="https://github.com/$TARGET.git"
          export GH_MIRROR_TOKEN="$SRC_TOKEN"
          git clone --bare "$SRC_URL" "$RUNNER_TEMP/oneshot.git"
          cd "$RUNNER_TEMP/oneshot.git"
          git fetch origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
          git cat-file -e "$BASE_SHA^{commit}"
          git merge-base --is-ancestor "$BASE_SHA" "refs/heads/$DEFAULT_BRANCH"
          # Copy the pre-bootstrap snapshot; temporary workflow commits are never mirrored.
          git update-ref "refs/heads/$DEFAULT_BRANCH" "$BASE_SHA"
          # Fail closed for custom LFS credential endpoints on any mirrored branch/tag.
          while IFS= read -r ref; do
            if git cat-file -e "$ref:.lfsconfig" 2>/dev/null; then
              echo "::error::Custom .lfsconfig; review manually before transfer"
              exit 29
            fi
          done < <(git for-each-ref --format='%(refname)' refs/heads refs/tags)
          git lfs fetch --all origin
          export GH_MIRROR_TOKEN="$DST_TOKEN"
          git ls-remote --heads --tags "$DST_URL" >"$RUNNER_TEMP/oneshot-before.tsv"
          if [[ "$OPERATION" == "return" ]]; then
            # Write to review branch only. No force, no write to destination main.
            REVIEW_REF="refs/heads/mirror-return/$REQUEST_ID"
            if grep -Fq "$REVIEW_REF" "$RUNNER_TEMP/oneshot-before.tsv"; then
              echo "::error::Review branch already exists"; exit 31
            fi
            git fetch --no-tags "$DST_URL" "refs/heads/$DEST_BASE:refs/mirror-target/base"
            actual="$(git rev-parse refs/mirror-target/base)"
            test "$actual" = "$TARGET_SHA" || { echo "::error::Destination changed since approval"; exit 32; }
            git merge-base "$BASE_SHA" "$TARGET_SHA" >/dev/null || { echo "::error::Unrelated Git histories"; exit 33; }
            if git merge-base --is-ancestor "$BASE_SHA" "$TARGET_SHA"; then
              echo "::error::Destination already contains source history"; exit 34
            fi
            git lfs push --all "$DST_URL"
            git push "$DST_URL" "$BASE_SHA:$REVIEW_REF"
            actual="$(git ls-remote --heads "$DST_URL" "$REVIEW_REF" | cut -f1)"
            test "$actual" = "$BASE_SHA" || { echo "::error::Review branch verification failed"; exit 35; }
            echo "Return branch verified: $REVIEW_REF"
          elif [[ "$OPERATION" == "sync" ]]; then
            # Synchronize only fast-forward refs. Never force, delete or replace tags.
            git fetch --no-tags "$DST_URL" '+refs/heads/*:refs/mirror-target/heads/*'
            actual="$(git rev-parse "refs/mirror-target/heads/$DEST_BASE")"
            test "$actual" = "$TARGET_SHA" || { echo "::error::Destination default changed"; exit 36; }
            while read -r old ref; do
              [[ "$ref" != *'^{}' ]] || continue
              case "$ref" in
                refs/heads/*)
                  # Target-only branch: keep it unchanged, do not prune.
                  if ! git show-ref --verify --quiet "$ref"; then
                    continue
                  fi
                  new="$(git rev-parse "$ref")"
                  if [[ "$old" != "$new" ]] && ! git merge-base --is-ancestor "$old" "$new"; then
                    echo "::error::Non-fast-forward branch $ref"; exit 38
                  fi
                  ;;
                refs/tags/*)
                  new="$(git rev-parse "$ref" 2>/dev/null || true)"
                  # Target-only tags stay intact; conflicting shared tags abort.
                  if [[ -n "$new" && "$old" != "$new" ]]; then
                    echo "::error::Conflicting Git tag $ref"; exit 39
                  fi
                  ;;
              esac
            done <"$RUNNER_TEMP/oneshot-before.tsv"
            git lfs push --all "$DST_URL"
            # GitHub supports atomic multi-ref updates: fail completely if any ref fails.
            git push --atomic "$DST_URL" 'refs/heads/*:refs/heads/*' 'refs/tags/*:refs/tags/*'
            git show-ref --heads --tags >"$RUNNER_TEMP/oneshot-source.tsv"
            git ls-remote --heads --tags "$DST_URL" >"$RUNNER_TEMP/oneshot-target.tsv"
          else
            test ! -s "$RUNNER_TEMP/oneshot-before.tsv" || { echo "::error::Destination is not empty"; exit 30; }
            git lfs push --all "$DST_URL"
            git push "$DST_URL" "refs/heads/$DEFAULT_BRANCH:refs/heads/$DEFAULT_BRANCH"
            git push "$DST_URL" 'refs/heads/*:refs/heads/*' 'refs/tags/*:refs/tags/*'
            git show-ref --heads --tags >"$RUNNER_TEMP/oneshot-source.tsv"
            git ls-remote --heads --tags "$DST_URL" >"$RUNNER_TEMP/oneshot-target.tsv"
          fi
          if [[ "$OPERATION" != "return" ]]; then
            python3 - "$RUNNER_TEMP/oneshot-source.tsv" "$RUNNER_TEMP/oneshot-target.tsv" <<'PY'
          import sys
          def refs(path):
              result={}
              with open(path,encoding='utf8') as f:
                  for row in f:
                      sha,ref=row.split()[:2]
                      if ref.endswith('^{}'): continue
                      if ref.startswith(('refs/heads/','refs/tags/')): result[ref]=sha
              return result
          source,target=refs(sys.argv[1]),refs(sys.argv[2])
          assert source and all(target.get(ref)==sha for ref,sha in source.items()), 'Source Git refs missing after copy/sync'
          print('Verified source refs:',len(source),'destination-only refs preserved:',len(set(target)-set(source)))
          PY
          fi
      - name: Remove local credentials
        if: always()
        shell: bash
        run: rm -rf "$RUNNER_TEMP/oneshot.git" "$RUNNER_TEMP/oneshot-askpass" "$RUNNER_TEMP/oneshot-before.tsv" "$RUNNER_TEMP/oneshot-source.tsv" "$RUNNER_TEMP/oneshot-target.tsv"
""";
    }
}
