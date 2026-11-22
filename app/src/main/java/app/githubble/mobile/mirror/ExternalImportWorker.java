package app.githubble.mobile.mirror;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.security.SecureStore;

/**
 * Continues import verification after the Activity closes and removes the
 * short-lived Actions secret as soon as the import job has completed.
 */
public final class ExternalImportWorker extends Worker {
    public static final String JOB_ID = "external_import_job_id";

    public ExternalImportWorker(@NonNull Context context,
                                @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        String jobId = getInputData().getString(JOB_ID);
        if (jobId == null || !jobId.matches("[0-9a-f]{32}"))
            return Result.failure();
        try {
            Context app = getApplicationContext();
            GitHubAccountStore accounts =
                    new GitHubAccountStore(app, new SecureStore(app));
            ExternalRepositoryImport importer = new ExternalRepositoryImport(app, accounts);
            ExternalRepositoryImport.Job job = importer.find(jobId);
            if (job == null) return Result.failure();
            if ((ExternalRepositoryImport.DONE.equals(job.state)
                    || ExternalRepositoryImport.FAILED.equals(job.state))
                    && !job.secretInstalled)
                return Result.success();

            job = importer.refresh(jobId);
            if ((ExternalRepositoryImport.DONE.equals(job.state)
                    || ExternalRepositoryImport.FAILED.equals(job.state))
                    && !job.secretInstalled)
                return Result.success();

            // Status polling or credential cleanup must be retried after a
            // temporary network outage, no unencrypted token is ever stored.
            return Result.retry();
        } catch (Exception transientError) {
            return Result.retry();
        }
    }
}
