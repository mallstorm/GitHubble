package app.githubble.mobile.repository;

/**
 * Disposable destination-side workflow. Only a newly created PRIVATE destination
 * is accepted; source repository is not needed for restoration.
 *
 * SAF ZIP -> private temporary GitHub Release asset -> Actions runner -> verified
 * local git bundle -> atomic guarded ref push + metadata replay.
 */
public final class FullRepositoryRestoreWorkflow {
    private FullRepositoryRestoreWorkflow(){}
    public static final String PATH=".github/workflows/githubble-full-restore.yml";
    public static final String FILE="githubble-full-restore.yml";
    public static String yaml() {
        return """
name: GitHubble Full Restore
run-name: GitHubble Full Restore @GH@ inputs.request_id }}
on:
  workflow_dispatch:
    inputs:
      request_id:
        type: string
        required: true
      archive_sha256:
        type: string
        required: true
      asset_id:
        type: string
        required: true
      bootstrap_branch:
        type: string
        required: true
      bootstrap_sha:
        type: string
        required: true
      source_sha:
        type: string
        required: true
permissions:
  contents: write
  issues: write
  pull-requests: read
concurrency:
  group: githubble-full-restore-@GH@ github.repository }}
  cancel-in-progress: false
jobs:
  restore:
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - name: Сверка информации · download staged ZIP
        shell: bash
        env:
          RESTORE_TOKEN: @GH@ secrets[format('GITHUBBLE_FULL_RESTORE_{0}', inputs.request_id)] }}
          ARCHIVE_SHA: @GH@ inputs.archive_sha256 }}
          ASSET_ID: @GH@ inputs.asset_id }}
          REQUEST_ID: @GH@ inputs.request_id }}
          BOOTSTRAP_BRANCH: @GH@ inputs.bootstrap_branch }}
          BOOTSTRAP_SHA: @GH@ inputs.bootstrap_sha }}
          SOURCE_SHA: @GH@ inputs.source_sha }}
        run: |
          set -euo pipefail
          umask 077
          python3 - <<'PY'
          import os,re,urllib.request,urllib.error,urllib.parse,pathlib,hashlib
          e=os.environ
          assert re.fullmatch('[0-9a-f]{32}',e['REQUEST_ID'])
          assert re.fullmatch('[0-9a-f]{64}',e['ARCHIVE_SHA'])
          assert re.fullmatch('[0-9a-f]{40}',e['SOURCE_SHA'])
          assert re.fullmatch('[0-9a-f]{40}',e['BOOTSTRAP_SHA'])
          assert re.fullmatch('[A-Za-z0-9_./-]{1,250}',e['BOOTSTRAP_BRANCH'])
          assert '..' not in e['BOOTSTRAP_BRANCH']
          assert re.fullmatch('[1-9][0-9]{0,17}',e['ASSET_ID'])
          assert e['RESTORE_TOKEN'],'Missing one-time restore credential'
          base='https://api.github.com/repos/'+e['GITHUB_REPOSITORY']
          url=base+'/releases/assets/'+e['ASSET_ID']
          headers={'Authorization':'Bearer '+e['RESTORE_TOKEN'],
                   'Accept':'application/octet-stream','User-Agent':'GitHubble-restore',
                   'X-GitHub-Api-Version':'2022-11-28'}
          class NoRedirect(urllib.request.HTTPRedirectHandler):
              def redirect_request(self,req,fp,code,msg,headers,newurl):
                  return None
          opener=urllib.request.build_opener(NoRedirect())
          try:
              response=opener.open(urllib.request.Request(url,headers=headers),timeout=60)
          except urllib.error.HTTPError as error:
              if error.code not in (301,302,307,308): raise
              signed=error.headers.get('Location','')
              destination=urllib.parse.urlparse(signed)
              host=destination.hostname or ''
              if destination.scheme!='https' or destination.username or destination.password or destination.port not in (None,443) or not (
                  host=='release-assets.githubusercontent.com' or host=='objects.githubusercontent.com' or host.endswith('.blob.core.windows.net')):
                  raise RuntimeError('Invalid archive CDN redirect')
              response=urllib.request.urlopen(urllib.request.Request(signed,headers={'User-Agent':'GitHubble-restore'}),timeout=60)
          archive=pathlib.Path(e['RUNNER_TEMP'])/'restore.zip'
          total=0;digest=hashlib.sha256()
          with response,open(archive,'wb') as output:
              while True:
                  chunk=response.read(1024*1024)
                  if not chunk:break
                  total+=len(chunk)
                  if total>2_000_000_000:raise RuntimeError('ZIP exceeds 2 GB restore limit')
                  digest.update(chunk);output.write(chunk)
          assert total>0 and digest.hexdigest()==e['ARCHIVE_SHA'],'Staged ZIP integrity mismatch'
          PY
      - name: Архив применён и валидирован · verify all hashes
        shell: bash
        env:
          SOURCE_SHA: @GH@ inputs.source_sha }}
        run: |
          set -euo pipefail
          python3 - <<'PY'
          import os,re,json,zipfile,hashlib,pathlib,stat
          archive=pathlib.Path(os.environ['RUNNER_TEMP'])/'restore.zip'
          root=pathlib.Path(os.environ['RUNNER_TEMP'])/'unpacked';root.mkdir()
          with zipfile.ZipFile(archive) as z:
              names=z.namelist()
              if len(names)<4 or len(names)>100000 or len(set(names))!=len(names):
                  raise RuntimeError('Duplicate, incomplete or oversized ZIP file listing')
              if 'manifest.json' not in names:raise RuntimeError('Manifest missing')
              info=z.getinfo('manifest.json')
              if info.file_size>8*1024*1024:raise RuntimeError('Manifest too large')
              manifest=json.loads(z.read('manifest.json'))
              if manifest.get('format')!='githubble-full-repository-v1' or not manifest.get('complete'):
                  raise RuntimeError('Invalid full backup format')
              if manifest.get('default_sha')!=os.environ['SOURCE_SHA']:
                  raise RuntimeError('Source SHA was changed')
              required=('repository.bundle','metadata.json','git-refs.txt')
              expected=manifest.get('files',{})
              if not all(key in expected for key in required):raise RuntimeError('Missing data')
              if set(names)!=set(expected)|{'manifest.json'}:raise RuntimeError('Unexpected ZIP entry')
              unpacked=0
              for entry in z.infolist():
                  name=entry.filename
                  parts=name.split('/')
                  if not name or len(name)>300 or name.startswith('/') or '\\\\' in name or ':' in name or any(p in ('','.', '..') for p in parts):
                      raise RuntimeError('Unsafe archive entry path')
                  if name!='manifest.json' and not (name in required or name.startswith('lfs/objects/') or name.startswith('release-assets/')):
                      raise RuntimeError('Unrecognized file in ZIP')
                  mode=(entry.external_attr>>16)&0o170000
                  if entry.is_dir() or mode==stat.S_IFLNK:raise RuntimeError('Symlink/directory not permitted in archive')
                  data=expected.get(name)
                  if name=='manifest.json':continue
                  if not isinstance(data,dict) or entry.file_size!=data.get('bytes') or entry.file_size>2_000_000_000:
                      raise RuntimeError('Length mismatch: '+name)
                  target=root.joinpath(*parts);target.parent.mkdir(parents=True,exist_ok=True)
                  h=hashlib.sha256();size=0
                  with z.open(entry) as source,open(target,'wb') as out:
                      while True:
                          chunk=source.read(1024*1024)
                          if not chunk:break
                          size+=len(chunk);unpacked+=len(chunk)
                          if unpacked>2_000_000_000:raise RuntimeError('ZIP expansion limit exceeded')
                          h.update(chunk);out.write(chunk)
                  if h.hexdigest()!=data.get('sha256') or size!=entry.file_size:
                      raise RuntimeError('Invalid checksum: '+name)
              (root/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
              print('Verified full archive:',len(expected),'files')
          PY
      - name: Восстановление · apply guarded Git refs and LFS
        shell: bash
        env:
          RESTORE_TOKEN: @GH@ secrets[format('GITHUBBLE_FULL_RESTORE_{0}', inputs.request_id)] }}
          REQUEST_ID: @GH@ inputs.request_id }}
          BOOTSTRAP_BRANCH: @GH@ inputs.bootstrap_branch }}
          BOOTSTRAP_SHA: @GH@ inputs.bootstrap_sha }}
        run: |
          set -euo pipefail
          umask 077
          export GIT_TERMINAL_PROMPT=0
          test -n "$RESTORE_TOKEN"
          cat > "$RUNNER_TEMP/restore-askpass" <<'ASKPASS'
          #!/bin/sh
          case "$1" in
            *Username*) printf '%s\\n' x-access-token ;;
            *Password*) printf '%s\\n' "$RESTORE_TOKEN" ;;
            *) exit 1 ;;
          esac
          ASKPASS
          chmod 700 "$RUNNER_TEMP/restore-askpass"
          export GIT_ASKPASS="$RUNNER_TEMP/restore-askpass"
          DST="https://github.com/$GITHUB_REPOSITORY.git"
          RESTORED="$RUNNER_TEMP/unpacked"
          git clone --mirror "$RESTORED/repository.bundle" "$RUNNER_TEMP/source.git"
          cd "$RUNNER_TEMP/source.git"
          # Verify exact ref inventory embedded in the bundle, no partial clones.
          git for-each-ref --format='%(refname) %(objectname)' refs/heads refs/tags | sort > "$RUNNER_TEMP/restored-refs.txt"
          sort "$RESTORED/git-refs.txt" > "$RUNNER_TEMP/archive-refs.txt"
          cmp "$RUNNER_TEMP/restored-refs.txt" "$RUNNER_TEMP/archive-refs.txt"
          while IFS= read -r ref; do
            if git cat-file -e "$ref:.lfsconfig" 2>/dev/null; then
              echo "::error::Custom .lfsconfig blocked"; exit 33
            fi
          done < <(git for-each-ref --format='%(refname)' refs/heads refs/tags)
          actual="$(git ls-remote --heads "$DST" "refs/heads/$BOOTSTRAP_BRANCH" | cut -f1)"
          test "$actual" = "$BOOTSTRAP_SHA" || { echo "::error::Target changed after approval"; exit 34; }
          test "$(git ls-remote --heads "$DST" | wc -l)" -eq 1 || {
            echo "::error::Target has additional branches"; exit 35;
          }
          # Only the temporary restore tag is permitted until the final cleanup.
          git ls-remote --tags "$DST" > "$RUNNER_TEMP/existing-tags.txt"
          if grep -v "refs/tags/githubble-restore-$REQUEST_ID$" "$RUNNER_TEMP/existing-tags.txt" | grep -v '^$'; then
            echo "::error::Unexpected destination tags"; exit 36;
          fi
          if [ -d "$RESTORED/lfs/objects" ]; then
            mkdir -p lfs
            cp -a "$RESTORED/lfs/objects" lfs/
            git lfs push --all "$DST"
          fi
          # Revoke the staging credential before importing any untrusted workflow.
          SECRET_NAME="GITHUBBLE_FULL_RESTORE_$(echo "$REQUEST_ID" | tr '[:lower:]' '[:upper:]')"
          curl --fail --silent --show-error -X DELETE -H "Authorization: Bearer $RESTORE_TOKEN" \\
            -H 'Accept: application/vnd.github+json' \\
            "https://api.github.com/repos/$GITHUB_REPOSITORY/actions/secrets/$SECRET_NAME"
          # An existing main bootstrap is the ONLY ref that may be replaced.
          specs=()
          while IFS= read -r ref; do
            if [ "$ref" = "refs/heads/$BOOTSTRAP_BRANCH" ]; then
              specs+=("+$ref:$ref")
            else
              specs+=("$ref:$ref")
            fi
          done < <(git for-each-ref --format='%(refname)' refs/heads refs/tags)
          test "${#specs[@]}" -gt 0
          git push --atomic --force-with-lease="refs/heads/$BOOTSTRAP_BRANCH:$BOOTSTRAP_SHA" "$DST" "${specs[@]}"
          git show-ref --heads --tags | awk '$2 ~ /^refs\\/(heads|tags)\\//' > "$RUNNER_TEMP/source-refs.txt"
          git ls-remote --heads --tags "$DST" > "$RUNNER_TEMP/target-refs.txt"
          python3 - "$RUNNER_TEMP/source-refs.txt" "$RUNNER_TEMP/target-refs.txt" <<'PY'
          import sys
          def refs(path):
              rows={}
              for line in open(path,encoding='utf-8'):
                  sha,ref=line.split()[:2]
                  if ref.endswith('^{}'):continue
                  rows[ref]=sha
              return rows
          src,dst=refs(sys.argv[1]),refs(sys.argv[2])
          assert src and all(dst.get(ref)==sha for ref,sha in src.items()),'Git ref verification failed'
          print('Git refs validated:',len(src))
          PY
      - name: Восстановление · labels, milestones, Issues and Releases
        shell: bash
        env:
          RESTORE_TOKEN: @GH@ github.token }}
        run: |
          set -euo pipefail
          test -n "$RESTORE_TOKEN"
          python3 - <<'PY'
          import os,json,urllib.request,urllib.error,urllib.parse,pathlib,re,time
          root=pathlib.Path(os.environ['RUNNER_TEMP'])/'unpacked'
          data=json.loads((root/'metadata.json').read_text(encoding='utf-8'))
          sections=data.get('sections',{})
          repo=os.environ['GITHUB_REPOSITORY']
          base='https://api.github.com/repos/'+repo
          token=os.environ['RESTORE_TOKEN']
          headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json',
                   'User-Agent':'GitHubble-full-restore','X-GitHub-Api-Version':'2022-11-28'}
          def call(method,path,payload=None):
              body=None if payload is None else json.dumps(payload).encode()
              for attempt in range(7):
                  request=urllib.request.Request(base+path,body,headers={**headers,'Content-Type':'application/json'},method=method)
                  try:
                      with urllib.request.urlopen(request,timeout=60) as response:
                          raw=response.read(4*1024*1024)
                          if method!='GET':time.sleep(1.1)
                          return json.loads(raw) if raw else {}
                  except urllib.error.HTTPError as error:
                      text=error.read(4096).decode('utf-8','replace').lower()
                      wait=error.headers.get('Retry-After')
                      limited=error.code==429 or (error.code==403 and (wait is not None or 'rate limit' in text or error.headers.get('X-RateLimit-Remaining')=='0'))
                      transient=error.code in (502,503,504) and method=='GET'
                      if not (limited or transient) or attempt==6:raise
                      delay=int(wait) if wait and wait.isdigit() else min(90,5*2**attempt)
                      print('GitHub asked to slow down (HTTP '+str(error.code)+'); waiting '+str(delay)+' s',flush=True)
                      time.sleep(min(delay,300))
          def pages(path):
              values=[]
              for page in range(1,41):
                  sep='&' if '?' in path else '?'
                  result=call('GET',path+sep+'per_page=100&page='+str(page))
                  if not isinstance(result,list):raise RuntimeError('Invalid page '+path)
                  values+=result
                  if len(result)<100:break
              return values
          old_labels={v['name'].casefold() for v in pages('/labels')}
          for label in sections.get('labels',[]):
              name=label.get('name','')
              if name and name.casefold() not in old_labels:
                  call('POST','/labels',{'name':name,'color':label.get('color','ededed'),'description':label.get('description','')})
                  old_labels.add(name.casefold())
          milestones={}
          for item in pages('/milestones?state=all'):
              milestones[item['title'].casefold()]=item['number']
          number_map={}
          for item in sections.get('milestones',[]):
              key=item['title'].casefold()
              if key not in milestones:
                  payload={'title':item['title'],'description':item.get('description',''),'state':item.get('state','open')}
                  if item.get('due_on'):payload['due_on']=item['due_on']
                  milestones[key]=call('POST','/milestones',payload)['number']
              number_map[item['number']]=milestones[key]
          # Resume support: Issues created by an interrupted run carry a provenance header.
          header=re.compile('^Restored from '+re.escape(data['repository'])+'/issues/([0-9]+)\\\\n')
          issues={};state={}
          for existing in pages('/issues?state=all'):
              match=header.match(existing.get('body') or '')
              if 'pull_request' in existing or not match:
                  raise RuntimeError('Destination already has unrelated Issues: restore aborted')
              issues[int(match.group(1))]=existing['number'];state[existing['number']]=existing.get('state','open')
          if issues:print('Resuming: found',len(issues),'Issues restored by an earlier run',flush=True)
          originals=sections.get('issues',[])
          for item in originals:
              number=item['number']
              if number in issues:continue
              provenance='Restored from '+data['repository']+'/issues/'+str(number)+'\\nOriginal author: '+(item.get('user') or {}).get('login','unknown')+'\\nOriginal date: '+item.get('created_at','')+'\\n\\n'
              payload={'title':item.get('title') or 'Restored Issue','body':provenance+(item.get('body') or ''),
                       'labels':[x.get('name','') for x in item.get('labels',[]) if x.get('name')]}
              if item.get('milestone') and item['milestone']['number'] in number_map:
                  payload['milestone']=number_map[item['milestone']['number']]
              created=call('POST','/issues',payload)
              issues[number]=created['number'];state[created['number']]='open'
          done_comments=set()
          if issues:
              for existing in pages('/issues/comments'):
                  marker=re.search('<!-- githubble-comment:([0-9]+) -->',existing.get('body') or '')
                  if marker:done_comments.add(int(marker.group(1)))
          for item in sections.get('issue_comments',[]):
              m=re.search('/issues/([0-9]+)$',item.get('issue_url',''))
              cid=item.get('id')
              if not m or int(m.group(1)) not in issues or not isinstance(cid,int) or cid in done_comments:continue
              new=issues[int(m.group(1))]
              body='Original comment by '+(item.get('user') or {}).get('login','unknown')+' ('+item.get('created_at','')+'):\\n\\n'+(item.get('body') or '')+'\\n\\n<!-- githubble-comment:'+str(cid)+' -->'
              call('POST','/issues/'+str(new)+'/comments',{'body':body})
          for item in originals:
              new=issues.get(item['number'])
              if item.get('state')=='closed' and new and state.get(new)!='closed':
                  call('PATCH','/issues/'+str(new),{'state':'closed'})
                  state[new]='closed'
          # Original PR reviews and timestamps cannot be forged through GitHub API.
          # Preserve their complete reference data in metadata.json inside the ZIP.
          manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
          import http.client
          from urllib.parse import quote
          existing_releases={r.get('tag_name'):r for r in pages('/releases') if r.get('tag_name')}
          # The API lists newest first; recreate oldest first to keep the original order.
          for item in reversed(sections.get('releases',[])):
              if not item.get('tag_name'):continue
              release=existing_releases.get(item['tag_name'])
              if release is None:
                  release=call('POST','/releases',{'tag_name':item['tag_name'],'name':item.get('name') or '',
                          'body':item.get('body') or '', 'draft':item.get('draft',False),
                          'prerelease':item.get('prerelease',False)})
              if manifest.get('release_binaries'):
                  have={a.get('name') for a in release.get('assets',[])}
                  for asset in item.get('assets',[]):
                      file=root/'release-assets'/(str(asset['id'])+'.bin')
                      if not file.is_file() or file.stat().st_size!=asset.get('size'):
                          raise RuntimeError('Release asset missing from full backup')
                      name=asset.get('name') or (str(asset['id'])+'.bin')
                      if name in have:continue
                      path='/repos/'+repo+'/releases/'+str(release['id'])+'/assets?name='+quote(name,safe='')
                      connection=http.client.HTTPSConnection('uploads.github.com',timeout=120)
                      try:
                          connection.putrequest('POST',path)
                          connection.putheader('Authorization','Bearer '+token)
                          connection.putheader('Accept','application/vnd.github+json')
                          connection.putheader('User-Agent','GitHubble-full-restore')
                          connection.putheader('Content-Type',asset.get('content_type') or 'application/octet-stream')
                          connection.putheader('Content-Length',str(file.stat().st_size))
                          connection.endheaders()
                          with open(file,'rb') as source:
                              while True:
                                  chunk=source.read(1024*1024)
                                  if not chunk:break
                                  connection.send(chunk)
                          response=connection.getresponse()
                          response.read(65536)
                          if response.status!=201:raise RuntimeError('Release asset upload failed: '+str(response.status))
                      finally:connection.close()
          print('Restored metadata:',len(originals),'Issues and',len(sections.get('releases',[])),'Releases')
          PY
      - name: Archive applied and validated
        shell: bash
        env:
          SOURCE_SHA: @GH@ inputs.source_sha }}
          SOURCE_BRANCH: @GH@ inputs.bootstrap_branch }}
        run: |
          set -euo pipefail
          echo "Full restoration completed; original Git refs were verified."
      - name: Revoke temporary restore credential
        if: always()
        shell: bash
        env:
          RESTORE_TOKEN: @GH@ secrets[format('GITHUBBLE_FULL_RESTORE_{0}', inputs.request_id)] }}
          REQUEST_ID: @GH@ inputs.request_id }}
        run: |
          set -euo pipefail
          [[ "$REQUEST_ID" =~ ^[0-9a-f]{32}$ ]] || exit 1
          [[ -n "$RESTORE_TOKEN" ]] || exit 0
          SECRET_NAME="GITHUBBLE_FULL_RESTORE_$(echo "$REQUEST_ID" | tr '[:lower:]' '[:upper:]')"
          status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' -X DELETE -H "Authorization: Bearer $RESTORE_TOKEN" -H 'Accept: application/vnd.github+json' "https://api.github.com/repos/$GITHUB_REPOSITORY/actions/secrets/$SECRET_NAME")" || status="000"
          if [[ "$status" != "204" && "$status" != "404" ]]; then
            echo "::warning::Restore secret cleanup returned HTTP $status"
          fi
      - name: Remove runner files
        if: always()
        run: rm -rf "$RUNNER_TEMP/restore.zip" "$RUNNER_TEMP/unpacked" "$RUNNER_TEMP/source.git" "$RUNNER_TEMP/restore-askpass"
""".replace("@GH@","$"+"{{");
    }
}
