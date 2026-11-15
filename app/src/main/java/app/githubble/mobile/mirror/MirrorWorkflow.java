package app.githubble.mobile.mirror;

/**
 * Trusted, standalone workflow installed ONLY inside a private controller repository.
 * Its GitHub App private key is an Actions Environment Secret, never an Android secret.
 */
public final class MirrorWorkflow {
    private MirrorWorkflow() {}
    public static final String CONTROLLER_NAME="GitHubble-Mirror-Control";
    public static final String WORKFLOW_PATH=".github/workflows/githubble-mirror-controller.yml";
    public static final String ENVIRONMENT="mirror-production";

    public static String yaml() {
        return """
name: GitHubble Secure Mirror
run-name: Mirror ${{ inputs.request_id }}
on:
  workflow_dispatch:
    inputs:
      source:
        description: Git source repository (owner/name)
        required: true
        type: string
      target:
        description: Git destination repository (owner/name)
        required: true
        type: string
      mode:
        description: copy or sync
        required: true
        type: choice
        options: [copy, sync]
      expected_target:
        description: EMPTY or SHA-256 of last confirmed target refs
        required: true
        type: string
      request_id:
        description: Opaque operation ID
        required: true
        type: string
permissions:
  contents: read
concurrency:
  group: githubble-mirror-${{ inputs.target }}
  cancel-in-progress: false
jobs:
  mirror:
    name: Secure Git and LFS transfer
    runs-on: ubuntu-latest
    timeout-minutes: 55
    environment: mirror-production
    steps:
      - name: Validate controller and operation
        id: preflight
        shell: bash
        env:
          ACTOR_ID: ${{ github.actor_id }}
          SOURCE: ${{ inputs.source }}
          TARGET: ${{ inputs.target }}
          MODE: ${{ inputs.mode }}
          EXPECTED: ${{ inputs.expected_target }}
          REQUEST_ID: ${{ inputs.request_id }}
        run: |
          set -euo pipefail
          python3 - <<'PY'
          import os,re
          assert os.environ["ACTOR_ID"] == "142712475", "Only the control account owner may dispatch"
          pattern=r"^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}$"
          s,t=os.environ["SOURCE"],os.environ["TARGET"]
          assert re.fullmatch(pattern,s) and re.fullmatch(pattern,t), "Invalid repository name"
          assert ".." not in s and ".." not in t and s.lower()!=t.lower(), "Invalid repository pair"
          assert s.split("/")[0].lower()!=t.split("/")[0].lower(), "Select different owners"
          assert re.fullmatch(r"[0-9a-f]{32}",os.environ["REQUEST_ID"]), "Invalid request identifier"
          mode,expected=os.environ["MODE"],os.environ["EXPECTED"]
          assert mode in ("copy","sync"), "Unsupported operation"
          assert expected=="EMPTY" if mode=="copy" else bool(re.fullmatch(r"[0-9a-f]{64}",expected)), "Invalid target baseline"
          with open(os.environ["GITHUB_OUTPUT"],"a",encoding="utf8") as o:
              o.write(f"source_owner={s.split('/')[0]}\\n")
              o.write(f"source_repo={s.split('/')[1]}\\n")
              o.write(f"target_owner={t.split('/')[0]}\\n")
              o.write(f"target_repo={t.split('/')[1]}\\n")
          PY
      - name: Mint scoped source read-only installation token
        id: source_token
        uses: actions/create-github-app-token@v3
        with:
          client-id: ${{ vars.GITHUBBLE_MIRROR_APP_CLIENT_ID }}
          private-key: ${{ secrets.GITHUBBLE_MIRROR_APP_PRIVATE_KEY }}
          owner: ${{ steps.preflight.outputs.source_owner }}
          repositories: ${{ steps.preflight.outputs.source_repo }}
          permission-contents: read
      - name: Mint scoped destination write and workflows installation token
        id: target_token
        uses: actions/create-github-app-token@v3
        with:
          client-id: ${{ vars.GITHUBBLE_MIRROR_APP_CLIENT_ID }}
          private-key: ${{ secrets.GITHUBBLE_MIRROR_APP_PRIVATE_KEY }}
          owner: ${{ steps.preflight.outputs.target_owner }}
          repositories: ${{ steps.preflight.outputs.target_repo }}
          permission-contents: write
          permission-workflows: write
      - name: Mirror Git objects, all heads, all tags and LFS
        shell: bash
        env:
          SOURCE: ${{ inputs.source }}
          TARGET: ${{ inputs.target }}
          MODE: ${{ inputs.mode }}
          EXPECTED: ${{ inputs.expected_target }}
          SRC_TOKEN: ${{ steps.source_token.outputs.token }}
          DST_TOKEN: ${{ steps.target_token.outputs.token }}
        run: |
          set -euo pipefail
          export GIT_TERMINAL_PROMPT=0
          umask 077
          cat > "$RUNNER_TEMP/githubble-askpass" <<'ASKPASS'
          #!/bin/sh
          case "$1" in
            *Username*github.com*) printf '%s\\n' 'x-access-token' ;;
            *Password*github.com*) printf '%s\\n' "$GH_MIRROR_TOKEN" ;;
            *) exit 1 ;;
          esac
          ASKPASS
          chmod 700 "$RUNNER_TEMP/githubble-askpass"
          export GIT_ASKPASS="$RUNNER_TEMP/githubble-askpass"
          export GH_MIRROR_TOKEN="$SRC_TOKEN"
          SOURCE_URL="https://github.com/$SOURCE.git"
          TARGET_URL="https://github.com/$TARGET.git"
          git clone --bare "$SOURCE_URL" "$RUNNER_TEMP/source.git"
          cd "$RUNNER_TEMP/source.git"
          git fetch --force origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
          # Git LFS config inside an untrusted repository may redirect token-bearing
          # requests to another domain. Fail closed instead of following that config.
          for branch in $(git for-each-ref --format='%(refname)' refs/heads refs/tags); do
            if git cat-file -e "$branch:.lfsconfig" 2>/dev/null; then
              echo "::error::Custom .lfsconfig found. LFS credentials require manual review."
              exit 29
            fi
          done
          git lfs fetch --all origin
          export GH_MIRROR_TOKEN="$DST_TOKEN"
          # The destination is an independently created repository, NOT a fork.
          git ls-remote --heads --tags "$TARGET_URL" > "$RUNNER_TEMP/target-before.tsv"
          cat >"$RUNNER_TEMP/check-refs.py" <<'PY'
          import hashlib,sys
          def refs(path):
              result={}
              with open(path,encoding="utf-8") as inp:
                  for row in inp:
                      sha,ref=row.split()[:2]
                      if ref.endswith("^{}"):
                          continue
                      if ref.startswith("refs/heads/") or ref.startswith("refs/tags/"):
                          result[ref]=sha
              return result
          def digest(data):
              payload="".join(f"{sha}\\t{ref}\\n" for ref,sha in sorted(data.items()))
              return hashlib.sha256(payload.encode("utf-8")).hexdigest()
          if __name__=="__main__":
              old=refs(sys.argv[1])
              mode,expected=sys.argv[2:]
              if mode=="copy":
                  assert not old, "Destination is not empty; refusing overwrite"
              else:
                  assert digest(old)==expected, "Target refs changed since last verification; refusing overwrite"
          PY
          python3 "$RUNNER_TEMP/check-refs.py" "$RUNNER_TEMP/target-before.tsv" "$MODE" "$EXPECTED"
          git lfs push --all "$TARGET_URL"
          # Copy full commit graph and all user branch/tag refs; exclude GitHub
          # internal read-only refs/pull/* which GitHub will reject.
          if [[ "$MODE" == "copy" ]]; then
            git push --prune "$TARGET_URL" '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
          else
            git push --force --prune "$TARGET_URL" '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
          fi
          git ls-remote --heads --tags "$TARGET_URL" > "$RUNNER_TEMP/target-after.tsv"
          git show-ref --heads --tags > "$RUNNER_TEMP/source-refs.tsv"
          python3 - "$RUNNER_TEMP/source-refs.tsv" "$RUNNER_TEMP/target-after.tsv" <<'PY'
          import sys
          def refs(path):
              result={}
              with open(path,encoding="utf8") as f:
                  for row in f:
                      sha,ref=row.split()[:2]
                      if ref.endswith("^{}"): continue
                      if ref.startswith("refs/heads/") or ref.startswith("refs/tags/"):
                          result[ref]=sha
              return result
          source,destination=refs(sys.argv[1]),refs(sys.argv[2])
          assert source and source==destination, "Verification failed: branch/tag SHA mismatch"
          print("Verified matching Git refs:",len(source))
          PY
      - name: Cleanup
        if: always()
        shell: bash
        run: rm -rf "$RUNNER_TEMP/source.git" "$RUNNER_TEMP/githubble-askpass" "$RUNNER_TEMP/target-before.tsv" "$RUNNER_TEMP/target-after.tsv" "$RUNNER_TEMP/source-refs.tsv" "$RUNNER_TEMP/check-refs.py"
""";
    }
}
