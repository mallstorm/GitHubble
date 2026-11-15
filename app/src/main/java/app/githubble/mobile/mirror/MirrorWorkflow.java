package app.githubble.mobile.mirror;

/** A transient workflow, installed on the new destination only for a confirmed operation. */
public final class MirrorWorkflow {
    private MirrorWorkflow() {}
    public static String yaml() {
        return """
name: GitHubble Mirror
run-name: GitHubble Mirror ${{ inputs.request_id }} - ${{ inputs.source }}
on:
  workflow_dispatch:
    inputs:
      source:
        required: true
        type: string
      request_id:
        required: true
        type: string
permissions:
  contents: write
concurrency:
  group: githubble-mirror
  cancel-in-progress: false
jobs:
  transfer:
    runs-on: ubuntu-latest
    timeout-minutes: 90
    env:
      SOURCE_REPO: ${{ inputs.source }}
      TARGET_REPO: ${{ github.repository }}
      GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    steps:
      - name: Transfer all Git branches, tags, history and LFS
        shell: bash
        run: |
          set -euo pipefail
          [[ "$SOURCE_REPO" =~ ^[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+$ ]] || exit 21
          [[ "$TARGET_REPO" =~ ^[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+$ ]] || exit 22
          [[ "$SOURCE_REPO" != "$TARGET_REPO" ]] || exit 23
          [[ "$SOURCE_REPO" != *..* && "$TARGET_REPO" != *..* ]] || exit 24
          git clone --bare "https://github.com/$SOURCE_REPO.git" "$RUNNER_TEMP/mirror.git"
          cd "$RUNNER_TEMP/mirror.git"
          git fetch --force origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
          git lfs fetch --all origin
          cat >"$RUNNER_TEMP/githubble-askpass" <<'ASKPASS'
          #!/bin/sh
          case "$1" in
            *Username*) printf '%s\n' 'x-access-token' ;;
            *Password*) printf '%s\n' "$GH_TOKEN" ;;
            *) printf '\n' ;;
          esac
          ASKPASS
          chmod 700 "$RUNNER_TEMP/githubble-askpass"
          export GIT_ASKPASS="$RUNNER_TEMP/githubble-askpass" GIT_TERMINAL_PROMPT=0
          TARGET_URL="https://github.com/$TARGET_REPO.git"
          git lfs push --all "$TARGET_URL"
          git push --force --prune "$TARGET_URL" '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
          git ls-remote --heads --tags "$TARGET_URL" > "$RUNNER_TEMP/target-refs.txt"
          git show-ref --heads --tags > "$RUNNER_TEMP/source-refs.txt"
          python3 - "$RUNNER_TEMP/source-refs.txt" "$RUNNER_TEMP/target-refs.txt" <<'PY'
          import sys
          def refs(path):
              out={}
              with open(path,encoding="utf8") as f:
                  for line in f:
                      sha,ref=line.split()[:2]
                      if (ref.startswith("refs/heads/") or ref.startswith("refs/tags/")) and not ref.endswith("^{}"):
                          out[ref]=sha
              return out
          source,target=refs(sys.argv[1]),refs(sys.argv[2])
          assert source == target, f"Git refs differ: {len(source)} vs {len(target)}"
          print(f"Verified {len(source)} branch/tag refs")
          PY
""";
    }
}
