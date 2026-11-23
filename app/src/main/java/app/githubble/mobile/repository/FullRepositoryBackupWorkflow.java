package app.githubble.mobile.repository;

/**
 * Generates a disposable, source-side GitHub Actions workflow.
 *
 * A GitHub-hosted runner is required because Android's REST API cannot export
 * Git pack history or bulk Git LFS objects. The resulting Actions artifact is
 * one portable ZIP holding the bundle, LFS objects and GitHub metadata.
 *
 * The workflow is read-only with respect to Git refs and GitHub metadata.
 */
public final class FullRepositoryBackupWorkflow {
    private FullRepositoryBackupWorkflow() {}

    public static final String PATH=".github/workflows/githubble-full-backup.yml";
    public static final String WORKFLOW="githubble-full-backup.yml";
    public static final String NAME="GitHubble Full Backup";

    public static String yaml() {
        return """
name: GitHubble Full Backup
run-name: GitHubble Full Backup @GH@ inputs.request_id }}
on:
  workflow_dispatch:
    inputs:
      request_id:
        type: string
        required: true
      source_sha:
        type: string
        required: true
      default_branch:
        type: string
        required: true
      release_binaries:
        type: boolean
        required: false
        default: false
permissions:
  contents: read
  issues: read
  pull-requests: read
  actions: read
concurrency:
  group: githubble-full-backup-@GH@ github.repository }}
  cancel-in-progress: false
jobs:
  backup:
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - name: Validate source and snapshot
        env:
          SOURCE_SHA: @GH@ inputs.source_sha }}
          SOURCE_BRANCH: @GH@ inputs.default_branch }}
          REQUEST_ID: @GH@ inputs.request_id }}
          GH_TOKEN: @GH@ github.token }}
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PY'
          import os, re
          env=os.environ
          assert re.fullmatch(r'[0-9a-f]{32}',env['REQUEST_ID'])
          assert re.fullmatch(r'[0-9a-f]{40}',env['SOURCE_SHA'])
          branch=env['SOURCE_BRANCH']
          assert re.fullmatch(r'[A-Za-z0-9_./-]{1,250}',branch)
          assert '..' not in branch and not branch.startswith('/') and not branch.endswith('/')
          assert bool(env['GH_TOKEN'])
          PY
          umask 077
          cat > "$RUNNER_TEMP/gh-backup-askpass" <<'ASKPASS'
          #!/bin/sh
          case "$1" in
            *Username*) printf '%s\n' x-access-token ;;
            *Password*) printf '%s\n' "$GH_TOKEN" ;;
            *) exit 1 ;;
          esac
          ASKPASS
          chmod 700 "$RUNNER_TEMP/gh-backup-askpass"
          echo "GH_BACKUP_ASKPASS=$RUNNER_TEMP/gh-backup-askpass" >> "$GITHUB_ENV"
      - name: Fetch complete Git history and all refs
        env:
          SOURCE_SHA: @GH@ inputs.source_sha }}
          SOURCE_BRANCH: @GH@ inputs.default_branch }}
          GH_TOKEN: @GH@ github.token }}
        shell: bash
        run: |
          set -euo pipefail
          export GIT_TERMINAL_PROMPT=0
          export GIT_ASKPASS="$GH_BACKUP_ASKPASS"
          git clone --bare "https://github.com/$GITHUB_REPOSITORY.git" "$RUNNER_TEMP/repository.git"
          cd "$RUNNER_TEMP/repository.git"
          git fetch origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
          git cat-file -e "$SOURCE_SHA^{commit}"
          git merge-base --is-ancestor "$SOURCE_SHA" "refs/heads/$SOURCE_BRANCH"
          # Exclude only the temporary workflow bootstrap commit from the saved default ref.
          git diff --name-only "$SOURCE_SHA" "refs/heads/$SOURCE_BRANCH" |
            grep -vFx '.github/workflows/githubble-full-backup.yml' > "$RUNNER_TEMP/unexpected-diff.txt"
          test ! -s "$RUNNER_TEMP/unexpected-diff.txt" || {
            echo "::error::Repository changed while preparing backup"; exit 40;
          }
          git update-ref "refs/heads/$SOURCE_BRANCH" "$SOURCE_SHA"
          git for-each-ref --format='%(refname) %(objectname)' refs/heads refs/tags > "$RUNNER_TEMP/git-refs.txt"
          test -s "$RUNNER_TEMP/git-refs.txt"
          while IFS= read -r ref; do
            if git cat-file -e "$ref:.lfsconfig" 2>/dev/null; then
              echo "::error::Custom .lfsconfig requires manual approval"; exit 41;
            fi
          done < <(git for-each-ref --format='%(refname)' refs/heads refs/tags)
      - name: Capture complete LFS objects
        env:
          GH_TOKEN: @GH@ github.token }}
        shell: bash
        run: |
          set -euo pipefail
          export GIT_TERMINAL_PROMPT=0
          export GIT_ASKPASS="$GH_BACKUP_ASKPASS"
          cd "$RUNNER_TEMP/repository.git"
          git lfs version
          git lfs fetch --all origin
          mkdir -p "$RUNNER_TEMP/portable/lfs"
          if [ -d lfs/objects ]; then
            cp -a lfs/objects "$RUNNER_TEMP/portable/lfs/objects"
          fi
      - name: Create Git bundle with complete history
        shell: bash
        run: |
          set -euo pipefail
          cd "$RUNNER_TEMP/repository.git"
          git bundle create "$RUNNER_TEMP/portable/repository.bundle" --all
          test -s "$RUNNER_TEMP/portable/repository.bundle"
          git bundle verify "$RUNNER_TEMP/portable/repository.bundle"
          cp "$RUNNER_TEMP/git-refs.txt" "$RUNNER_TEMP/portable/git-refs.txt"
      - name: Export GitHub metadata and optional Release files
        env:
          GH_TOKEN: @GH@ github.token }}
          SOURCE_SHA: @GH@ inputs.source_sha }}
          SOURCE_BRANCH: @GH@ inputs.default_branch }}
          INCLUDE_RELEASES: @GH@ inputs.release_binaries }}
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PY'
          import os,json,urllib.request,urllib.error,re,hashlib,pathlib
          root=pathlib.Path(os.environ['RUNNER_TEMP'])/'portable'
          repo=os.environ['GITHUB_REPOSITORY']
          token=os.environ['GH_TOKEN']
          base='https://api.github.com/repos/'+repo
          headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','User-Agent':'GitHubble-FullBackup','X-GitHub-Api-Version':'2022-11-28'}
          def api(path):
              url=base+path
              with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=60) as response:
                  if response.geturl().split('?')[0]!=url.split('?')[0]:
                      raise RuntimeError('Untrusted GitHub API redirect')
                  raw=response.read(24*1024*1024+1)
                  if len(raw)>24*1024*1024: raise RuntimeError('GitHub API page is too large')
                  return json.loads(raw)
          def pages(path):
              values=[]
              for page in range(1,14):
                  suffix=('&' if '?' in path else '?')+'per_page=100&page='+str(page)
                  result=api(path+suffix)
                  if not isinstance(result,list): raise RuntimeError('Invalid page '+path)
                  if page==13 and result: raise RuntimeError('Pagination bound exceeded: '+path)
                  if page==13: break
                  values+=result
                  if len(result)<100:break
              return values
          sections={'repository':api('')}
          endpoints={
            'branches':'/branches','tags':'/tags','labels':'/labels',
            'milestones':'/milestones?state=all','issues':'/issues?state=all',
            'issue_comments':'/issues/comments','pulls':'/pulls?state=all',
            'pull_comments':'/pulls/comments','releases':'/releases'
          }
          for name,path in endpoints.items():
              values=pages(path)
              if name=='issues':values=[v for v in values if 'pull_request' not in v]
              sections[name]=values
          output={'format':'githubble-full-repository-v1','repository':repo,
                  'default_branch':os.environ['SOURCE_BRANCH'],
                  'default_sha':os.environ['SOURCE_SHA'],'sections':sections}
          (root/'metadata.json').write_text(json.dumps(output,ensure_ascii=False),encoding='utf-8')
          if os.environ['INCLUDE_RELEASES'].lower()=='true':
              class NoRedirect(urllib.request.HTTPRedirectHandler):
                  def redirect_request(self,req,fp,code,msg,headers,newurl):
                      return None
              opener=urllib.request.build_opener(NoRedirect())
              folder=root/'release-assets';folder.mkdir()
              total=0
              for release in sections['releases']:
                  for asset in release.get('assets',[]):
                      aid=asset.get('id'); size=asset.get('size',-1)
                      if not isinstance(aid,int) or size<0 or size>2*1024**3:
                          raise RuntimeError('Release asset is unsupported or too large')
                      url=base+'/releases/assets/'+str(aid)
                      request=urllib.request.Request(url,headers={**headers,'Accept':'application/octet-stream'})
                      try: response=opener.open(request,timeout=60)
                      except urllib.error.HTTPError as e:
                          if e.code not in (301,302,307,308): raise
                          location=e.headers.get('Location','')
                          from urllib.parse import urlparse
                          dest=urlparse(location)
                          host=dest.hostname or ''
                          if dest.scheme!='https' or dest.username or dest.password or dest.port not in (None,443) or not (
                              host=='release-assets.githubusercontent.com' or host.endswith('.blob.core.windows.net') or host=='objects.githubusercontent.com'):
                              raise RuntimeError('Untrusted Release asset redirect')
                          response=urllib.request.urlopen(urllib.request.Request(location,headers={'User-Agent':'GitHubble-FullBackup'}),timeout=60)
                      target=folder/(str(aid)+'.bin')
                      with response,open(target,'wb') as out:
                          count=0
                          while True:
                              chunk=response.read(1024*1024)
                              if not chunk:break
                              count+=len(chunk);total+=len(chunk)
                              if count>size or total>4*1024**3:
                                  raise RuntimeError('Release payload exceeds size limit')
                              out.write(chunk)
                          if count!=size:raise RuntimeError('Release asset truncated: '+str(aid))
                      digest=asset.get('digest') or ''
                      if digest.startswith('sha256:'):
                          calculated=hashlib.file_digest(open(target,'rb'),'sha256').hexdigest()
                          if calculated!=digest[7:]:raise RuntimeError('Release asset checksum mismatch')
          PY
      - name: Validate snapshot and create SHA-256 manifest
        env:
          SOURCE_SHA: @GH@ inputs.source_sha }}
          SOURCE_BRANCH: @GH@ inputs.default_branch }}
          INCLUDE_RELEASES: @GH@ inputs.release_binaries }}
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PY'
          import os,hashlib,json,pathlib
          root=pathlib.Path(os.environ['RUNNER_TEMP'])/'portable'
          refs=(root/'git-refs.txt').read_text().splitlines()
          expected='refs/heads/'+os.environ['SOURCE_BRANCH']+' '+os.environ['SOURCE_SHA']
          if expected not in refs:raise RuntimeError('Source default branch SHA mismatch')
          files={}
          for file in root.rglob('*'):
              if file.is_symlink():raise RuntimeError('Symlink is not allowed')
              if not file.is_file():continue
              rel=file.relative_to(root).as_posix()
              h=hashlib.sha256();length=0
              with open(file,'rb') as f:
                  for chunk in iter(lambda:f.read(1024*1024),b''):
                      length+=len(chunk);h.update(chunk)
              files[rel]={'sha256':h.hexdigest(),'bytes':length}
          if 'repository.bundle' not in files or 'metadata.json' not in files or 'git-refs.txt' not in files:
              raise RuntimeError('Incomplete full-backup payload')
          manifest={'format':'githubble-full-repository-v1','source':os.environ['GITHUB_REPOSITORY'],
                    'default_branch':os.environ['SOURCE_BRANCH'],
                    'default_sha':os.environ['SOURCE_SHA'],
                    'release_binaries':os.environ['INCLUDE_RELEASES'].lower()=='true',
                    'files':files,'complete':True}
          (root/'manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True),encoding='utf-8')
          print('Validated package:',len(files),'files',sum(f['bytes'] for f in files.values()),'bytes')
          PY
      - name: Publish portable repository ZIP
        uses: actions/upload-artifact@v4
        with:
          name: githubble-full-@GH@ inputs.request_id }}
          path: @GH@ runner.temp }}/portable/
          include-hidden-files: true
          if-no-files-found: error
          retention-days: 1
          compression-level: 0
      - name: Remove runner credentials
        if: always()
        run: rm -rf "$RUNNER_TEMP/gh-backup-askpass" "$RUNNER_TEMP/repository.git"
""".replace("@GH@", "$" + "{{");
    }
}
