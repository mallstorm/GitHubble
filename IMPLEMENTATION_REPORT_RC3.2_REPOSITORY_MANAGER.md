# GitHubble 1.0.0 RC3.2 — Repository Manager

Version: `1.0.0-rc3.2-repository-manager`  
versionCode: `36`

## Scope

This pass extends the RC3.1 advanced-search baseline with full repository administration and Android-native source import.

## Implemented

- repository creation (public/private + optional README) remains available from **My repositories**; after creation the app opens the repository management screen;
- edit repository name and description;
- switch visibility `Public ↔ Private` with an explicit confirmation warning;
- delete a repository only after typing the exact `owner/repository` name;
- select an Android folder with `ACTION_OPEN_DOCUMENT_TREE`;
- recursively preserve folder structure and upload it as Git blobs + one Git tree + one commit;
- select a ZIP with Storage Access Framework and import its entries directly into the repository;
- select a single file (including a ZIP archive) and upload it as one repository file;
- streaming base64 upload to the Git blobs API avoids loading large files entirely into RAM;
- practical per-file ceiling is 99 MiB, with a clear Git LFS / Release asset message beyond that;
- import preview shows file count, known total size, sample paths, ignored service files and workflow permission warning;
- progress displays current file and `done / total`;
- empty repositories are bootstrapped through the Contents API and the temporary bootstrap file is removed in the final import commit;
- import target survives the Android document picker through SharedPreferences;
- unsafe ZIP paths (`..`) and VCS/OS metadata (`.git`, `__MACOSX`, `.DS_Store`, `Thumbs.db`) are rejected/ignored;
- repository cache is invalidated after mutations.

## Required GitHub App permissions

- **Contents: Read and write** — file/folder/ZIP import;
- **Administration: Read and write** — repository settings, visibility and deletion;
- **Workflows: Read and write** — only if `.github/workflows/*` is imported/edited;
- existing permissions for Issues, PRs, Discussions, etc. remain unchanged.

## Safety

Repository deletion requires exact-name confirmation. Public visibility shows a warning about secrets. Folder access uses Storage Access Framework and does not require broad storage permission on modern Android. OAuth tokens remain Keystore-backed and are never exported.
