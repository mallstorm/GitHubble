package app.githubble.mobile.agent;

import java.util.List;

public final class CachedWorkspaceSource implements WorkspaceSource {
    private final GitHubWorkspaceSource remote;
    private final LocalWorkspaceSource local;
    public CachedWorkspaceSource(GitHubWorkspaceSource remote,LocalWorkspaceSource local){this.remote=remote;this.local=local;}
    public List<String> paths()throws Exception{return remote.paths();}
    public String identity(){return remote.identity()+":"+local.identity();}
    public String importReport()throws Exception{return local.importReport();}
    public Snapshot snapshot(String path)throws Exception {
        String origin=remote.sha(path),mode=remote.mode(path);
        if(!java.util.Arrays.asList("100644","100755").contains(mode))throw new SecurityException("Не обычный файл");
        if(origin.equals("missing"))return new Snapshot(null,"missing",mode);
        String text=local.snapshot(path).text;
        return text==null?remote.snapshot(path):new Snapshot(text,origin,mode);
    }
}
