# Data safety declaration draft

Collected by developer: none by default.

Processed on device:
- GitHub OAuth token (encrypted with Android Keystore);
- GitHub profile/repository content and local cache;
- favorites, tracked developers, offline actions and UI preferences;
- optional translation provider endpoint and API key (encrypted).

Shared with third parties:
- GitHub API requests are sent to GitHub;
- text selected for translation is sent only to the provider configured by the user.

User controls:
- safe local export;
- clear cache;
- sign out;
- delete all local data.
