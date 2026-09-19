#!/usr/bin/env python3
import hashlib, json, pathlib, sys
from datetime import datetime, timezone
root=pathlib.Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
out=pathlib.Path(sys.argv[2] if len(sys.argv)>2 else root/'release'/'GitHubble_SPDX.json')
properties={}
for line in (root/'version.properties').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key,value=line.split('=',1); properties[key.strip()]=value.strip()
version=properties.get('VERSION_NAME','UNKNOWN')
files=[]
for base in [root/'app'/'src', root/'app'/'build.gradle', root/'build.gradle', root/'settings.gradle']:
    paths=[base] if base.is_file() else sorted(p for p in base.rglob('*') if p.is_file())
    for p in paths:
        if any(part in {'.gradle','build'} for part in p.parts): continue
        data=p.read_bytes(); files.append({
            'SPDXID':'SPDXRef-File-'+hashlib.sha1(str(p.relative_to(root)).encode()).hexdigest()[:12],
            'fileName':'./'+str(p.relative_to(root)),
            'checksums':[{'algorithm':'SHA256','checksumValue':hashlib.sha256(data).hexdigest()}],
            'licenseConcluded':'NOASSERTION','copyrightText':'NOASSERTION'})
doc={
 'spdxVersion':'SPDX-2.3','dataLicense':'CC0-1.0','SPDXID':'SPDXRef-DOCUMENT',
 'name':'GitHubble-'+version,
 'documentNamespace':'https://gihubble.local/spdx/'+hashlib.sha256((str(root)+version).encode()).hexdigest()[:20],
 'creationInfo':{'created':datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z'),'creators':['Tool: GitHubble-SBOM-Generator/1.1']},
 'packages':[{'name':'GitHubble','SPDXID':'SPDXRef-Package-GitHubble','versionInfo':version,'downloadLocation':'NOASSERTION','filesAnalyzed':True,'licenseConcluded':'NOASSERTION','copyrightText':'NOASSERTION'}],
 'files':files,
 'relationships':[{'spdxElementId':'SPDXRef-DOCUMENT','relationshipType':'DESCRIBES','relatedSpdxElement':'SPDXRef-Package-GitHubble'}]
}
out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(doc,ensure_ascii=False,indent=2),encoding='utf-8')
print(out)
