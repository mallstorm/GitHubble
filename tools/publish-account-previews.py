#!/usr/bin/env python3
"""Publish synthetic emulator screenshots to an isolated preview branch, never the app branch."""
import base64
import json
import os
from pathlib import Path
import urllib.request
import urllib.error

repo=os.environ['GITHUB_REPOSITORY']
head=os.environ['PREVIEW_HEAD_SHA']
kind=os.environ.get('PREVIEW_KIND','account-ui')
if kind not in ('account-ui','agent-ui'):raise SystemExit('Unsupported preview kind')
branch='preview/'+kind+'-'+head[:12]
base='https://api.github.com/repos/'+repo

def call(path, data=None):
    request=urllib.request.Request(base+path, data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json','Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=60) as response:return json.load(response)

files=sorted(Path(kind+'-screenshots').glob('*.png'))
expected=36 if kind=='agent-ui' else 18
if len(files)!=expected:raise SystemExit('Unexpected synthetic screenshot count: '+str(len(files)))
tree=[]
for path in files:
    blob=call('/git/blobs',{'content':base64.b64encode(path.read_bytes()).decode(),'encoding':'base64'})
    tree.append({'path':'docs/previews/'+kind+'/'+path.name,'mode':'100644','type':'blob','sha':blob['sha']})
parent=call('/git/commits/'+head)
newtree=call('/git/trees',{'base_tree':parent['tree']['sha'],'tree':tree})
commit=call('/git/commits',{'message':'docs: synthetic '+kind+' previews for '+head[:12], 'tree':newtree['sha'],'parents':[head]})
try:
    call('/git/refs',{'ref':'refs/heads/'+branch,'sha':commit['sha']})
except urllib.error.HTTPError as error:
    if error.code!=422:raise
    # A rerun may already have published previews for this exact source commit.
    call('/git/ref/heads/'+branch)
print('::notice title=Native UI previews::https://github.com/'+repo+'/tree/'+branch+'/docs/previews/'+kind)
