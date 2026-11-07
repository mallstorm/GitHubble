package app.githubble.mobile.screen;

import android.content.Context;
import android.content.SharedPreferences;
import app.githubble.mobile.backup.PortableSettingsManager;
import app.githubble.mobile.controller.PullRequestAdvancedController;
import app.githubble.mobile.controller.RepositoryAdminController;
import app.githubble.mobile.controller.RepositoryImportController;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubGraphQlClient;
import app.githubble.mobile.offline.OfflineActionQueue;
import app.githubble.mobile.offline.OfflineMutationDispatcher;
import app.githubble.mobile.preview.FilePreviewController;
import app.githubble.mobile.tracking.TrackingDatabase;

/** Central feature registry keeps screen-specific services out of MainActivity construction logic. */
public final class FeatureControllerRegistry {
    public final OfflineActionQueue offlineQueue;
    public final OfflineMutationDispatcher offlineDispatcher;
    public final RepositoryAdminController repositoryAdmin;
    public final RepositoryImportController repositoryImport;
    public final PullRequestAdvancedController pullAdvanced;
    public final FilePreviewController filePreview;
    public final PortableSettingsManager portableSettings;
    public FeatureControllerRegistry(Context context, GitHubApiClient api, GitHubGraphQlClient graphql, SharedPreferences prefs, TrackingDatabase tracking){
        offlineQueue=new OfflineActionQueue(context);offlineDispatcher=new OfflineMutationDispatcher(context,api,offlineQueue);
        repositoryAdmin=new RepositoryAdminController(api);repositoryImport=new RepositoryImportController(context,api);pullAdvanced=new PullRequestAdvancedController(api);filePreview=new FilePreviewController(api);portableSettings=new PortableSettingsManager(prefs,tracking);
    }
}
