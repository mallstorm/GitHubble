package app.githubble.mobile.agent

import org.json.JSONArray
import org.json.JSONObject

/** Lazily reuse the existing index for one explicit page of at most 32 safe files. */
object TaskIndex {
    @JvmStatic fun query(tools:WorkspaceToolset,kind:String,query:String,prefix:String,offset:Int):JSONObject {
        require(query.length<=120&&offset>=0)
        val candidates=tools.visiblePaths().filter { it.startsWith(prefix)&&it.substringAfterLast('.') in setOf("java","kt","kts","py","js","ts","tsx","jsx","go","rs","c","cpp","h","swift","cs","rb","php") }
        val page=candidates.drop(offset).take(32)
        val snapshots=mutableMapOf<String,WorkspaceSource.Snapshot>()
        fun snapshot(path:String)=snapshots.getOrPut(path){tools.snapshot(path)}
        val reader:(String)->String={path->snapshot(path).text?:""}
        val stat:(String)->ProjectIndexFileStat={path->ProjectIndexFileStat((snapshot(path).text?:"").toByteArray().size.toLong(),0)}
        val identity=tools.source.identity()+":"+tools.changes.task+":"+prefix+":"+offset
        val result=JSONObject().put("indexed_scope",JSONArray(page)).put("partial",offset+page.size<candidates.size)
            .put("next_offset",if(offset+page.size<candidates.size)offset+page.size else JSONObject.NULL)
        if(kind=="find_usages"){
            val found=ProjectIndexRegistry.usages(identity,query,page,stat,reader,80)
            result.put("usages",JSONArray().also{a->found.usages.forEach{u->a.put(JSONObject().put("path",u.path).put("line",u.line).put("preview",app.githubble.mobile.memory.MemoryPolicy.redactSecrets(u.preview)).put("definition",u.isDefinition))}})
        }else{
            val found=if(kind=="find_definition")ProjectIndexRegistry.definition(identity,query,null,page,stat,reader)
                else ProjectIndexRegistry.search(identity,query,page,stat,reader,80)
            result.put("symbols",JSONArray().also{a->found.symbols.forEach{s->a.put(JSONObject().put("name",s.name).put("kind",s.kind).put("path",s.path).put("line",s.line).put("signature",app.githubble.mobile.memory.MemoryPolicy.redactSecrets(s.signature)))}})
        }
        return result
    }
}
