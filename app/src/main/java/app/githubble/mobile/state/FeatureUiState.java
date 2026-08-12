package app.githubble.mobile.state;

import android.os.Bundle;

/** Small serializable state model for staged features; contains no secrets. */
public final class FeatureUiState {
    public String diffMode="unified";
    public boolean collapseDiffContext=true;
    public String lastAdminRepository="";
    public int pendingOfflineActions;
    public int conflicts;
    public void save(Bundle out){out.putString("feature_diff_mode",diffMode);out.putBoolean("feature_diff_collapse",collapseDiffContext);out.putString("feature_admin_repo",lastAdminRepository);out.putInt("feature_pending",pendingOfflineActions);out.putInt("feature_conflicts",conflicts);}
    public void restore(Bundle in){if(in==null)return;diffMode=in.getString("feature_diff_mode",diffMode);collapseDiffContext=in.getBoolean("feature_diff_collapse",collapseDiffContext);lastAdminRepository=in.getString("feature_admin_repo",lastAdminRepository);pendingOfflineActions=in.getInt("feature_pending",0);conflicts=in.getInt("feature_conflicts",0);}
}
