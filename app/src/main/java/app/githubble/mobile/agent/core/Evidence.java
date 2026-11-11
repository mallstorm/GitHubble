package app.githubble.mobile.agent.core;

import java.util.List;

/**
 * Independent facts about the world, fetched from GitHub/Git/the file system — never from the
 * model. Methods return null when the fact could not be established (treated as UNVERIFIED).
 */
public interface Evidence {
    final class PullInfo {
        public final boolean exists; public final String state, headSha, baseBranch;
        public PullInfo(boolean exists, String state, String headSha, String baseBranch) {
            this.exists = exists; this.state = state == null ? "" : state;
            this.headSha = headSha == null ? "" : headSha; this.baseBranch = baseBranch == null ? "" : baseBranch;
        }
    }
    final class RunInfo {
        public final String status, conclusion, headSha, branch;
        public RunInfo(String status, String conclusion, String headSha, String branch) {
            this.status = status == null ? "" : status; this.conclusion = conclusion == null ? "" : conclusion;
            this.headSha = headSha == null ? "" : headSha; this.branch = branch == null ? "" : branch;
        }
    }
    final class ArtifactInfo {
        public final String name; public final long sizeBytes; public final boolean expired;
        public ArtifactInfo(String name, long sizeBytes, boolean expired) {
            this.name = name; this.sizeBytes = sizeBytes; this.expired = expired;
        }
    }

    /** Head SHA of the branch, or "" if the branch does not exist; null if unknown. */
    String branchHead(String repo, String branch) throws Exception;
    /** @return null if unknown */
    Boolean commitExists(String repo, String sha) throws Exception;
    PullInfo pullRequest(String repo, int number) throws Exception;
    RunInfo workflowRun(String repo, long runId) throws Exception;
    List<ArtifactInfo> artifacts(String repo, long runId) throws Exception;
}
