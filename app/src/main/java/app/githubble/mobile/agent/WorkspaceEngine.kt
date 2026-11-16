package app.githubble.mobile.agent
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/** Host adapter for OmniChat's original editor/index/checkpoint/archive/SAF engine. */
object WorkspaceEngine {
    private fun engine(context: Context,root: File,id: String): WorkspaceTools {
        val meta=JSONObject(File(root,".workspace.json").readText())
        val relative=root.relativeTo(context.filesDir).path
        return WorkspaceTools.forInternalRoot(context,relative,id,meta.optString("storage_uri"))
    }
    @JvmStatic fun taskPaths(context:Context,root:File,id:String)=engine(context,root,id).taskPaths()
    @JvmStatic fun taskDirectories(context:Context,root:File,id:String)=engine(context,root,id).taskDirectories()
    @JvmStatic fun taskRead(context:Context,root:File,id:String,path:String)=engine(context,root,id).taskRead(path)
    @JvmStatic fun files(context: Context,root: File,id: String): JSONArray {
        val e=engine(context,root,id);val a=JSONArray()
        e.list().filter { !it.endsWith(".workspace.json") }.forEach { path -> a.put(JSONObject().put("path",path).put("size",e.editorSnapshot(path).size)) }
        return a
    }
    @JvmStatic fun read(context: Context,root: File,id: String,path: String)=engine(context,root,id).readForEditor(path)
    @JvmStatic fun write(context: Context,root: File,id: String,path: String,text: String) { engine(context,root,id).saveFromEditor(path,text) }
    @JvmStatic fun importUri(context: Context,root: File,id: String,uri: Uri)=engine(context,root,id).import(uri)
    @JvmStatic fun exportZip(context: Context,root: File,id: String,out: OutputStream) { engine(context,root,id).export(out) }
    @JvmStatic fun search(context: Context,root: File,id: String,query: String)=engine(context,root,id).searchContent(query).joinToString("\n") { "${it.path}:${it.line}: ${it.preview}" }
    @JvmStatic fun index(context: Context,root: File,id: String,query: String): String {
        val e=engine(context,root,id)
        return e.projectMap()+"\n"+e.projectIndexStatus()+"\n"+e.searchSymbols(query)
    }
    @JvmStatic fun checkpoints(context: Context,root: File,id: String): JSONArray = JSONArray().also { a -> engine(context,root,id).checkpoints().forEach { a.put(JSONObject().put("id",it.id).put("label",it.label)) } }
    @JvmStatic fun checkpoint(context: Context,root: File,id: String,label: String)=engine(context,root,id).createCheckpoint(label).id
    @JvmStatic fun restore(context: Context,root: File,id: String,checkpoint: String)=engine(context,root,id).restoreCheckpoint(checkpoint)
    @JvmStatic fun operation(context: Context,root: File,id: String,op: String,from: String,to: String): String {
        val e=engine(context,root,id)
        return when(op){"mkdir"->e.createDirectory(from);"move"->e.renamePath(from,to);"delete"->e.deletePath(from);"rename_symbol"->e.renameSymbol(from,to).toString();else->error("Неизвестная операция")}
    }
}
