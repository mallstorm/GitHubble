package app.githubble.mobile.agent;

import java.util.List;

/** A read-only project snapshot. Both local/SAF and GitHub backends obey this boundary. */
public interface WorkspaceSource {
    final class Snapshot {
        public final String text,sha,originSha,mode;
        public Snapshot(String text,String originSha,String mode) {
            this.text=text;sha=GitObjectId.blob(text);this.originSha=originSha==null?sha:originSha;
            this.mode=mode==null?"100644":mode;
        }
    }
    Snapshot snapshot(String path) throws Exception;
    List<String> paths() throws Exception;
    default java.util.Set<String> directories()throws Exception {
        java.util.Set<String> dirs=new java.util.TreeSet<>();for(String path:paths()){int at=path.indexOf('/');while(at>0){dirs.add(path.substring(0,at));at=path.indexOf('/',at+1);}}return dirs;
    }
    String identity();
    default String importReport() throws Exception {return "";}
}
