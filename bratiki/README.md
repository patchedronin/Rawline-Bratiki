# Build and release operation

## Trust boundaries

- `bratiki-build.yml`: runs only `main`, no signing secrets, contents read-only. Revision `2` produces 1.1.4 versionCode `110002`, above the existing local mod `110001`.
- `bratiki-release.yml`: manual dispatch with successful unsigned build `run_id` and expected `revision`. The validation job checks run provenance and artifact hashes without project checkout. Publishing requires approval in environment `release`, restricted to `main`.
- The publishing job does not execute source code or Gradle from the unsigned build. It signs with `apksigner`, independently inspects package/version/ABI/certificate and publishes a draft only after checking the uploaded APK digest.
- Environment Secrets: `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS`. Never put these in repository variables, source files, artifacts or update manifests.
- Current signer SHA-256: `b3729dc9e7538e42f95e96b8f61c400ad9f4e39d309b23c7e4594043fd0e134c`.

## Initial release

1. Push reviewed source to `main`; the unsigned build runs automatically (revision 2).
2. Open Actions → **Bratiki unsigned arm64 build** and wait for success. Copy its run ID from the URL. Build artifacts expire after 14 days.
3. Verify the build's `head_sha`, then create a lightweight `rawline-1.1.4` tag at that exact commit using the owner's GitHub account. GitHub rejects release creation by `GITHUB_TOKEN` when it also requests creation of a tag pointing to workflow changes. The signing workflow accepts only an existing tag at the verified source SHA.
4. Dispatch **Bratiki isolated signing and release**, `run_id=<successful run>` and `revision=2`.
5. Review the selected source commit and build provenance, then approve environment `release` in GitHub.
6. Confirm Releases contains `rawline-1.1.4`, its APK, source commit links and embedded `rawline-update` metadata.
7. Install the first channel-enabled APK manually over the existing mod. Future **Check updates** requests use this repository.

GitHub CLI equivalents:

```sh
gh workflow run bratiki-build.yml -R patchedronin/Rawline-Bratiki -f revision=2
gh run list -R patchedronin/Rawline-Bratiki --workflow bratiki-build.yml
gh api -X POST repos/patchedronin/Rawline-Bratiki/git/refs -f ref=refs/tags/rawline-1.1.4 -f sha=VERIFIED_BUILD_SOURCE_SHA
gh workflow run bratiki-release.yml -R patchedronin/Rawline-Bratiki -f run_id=RUN_ID -f revision=2
```

An existing public release is never replaced; an existing source tag is accepted only if it points to the exact selected build commit. If publication is interrupted, inspect the draft, assets and tag manually. Delete an incomplete **unpublished** draft only after verifying its provenance, then explicitly retry. Do not remove a published release to hide an error: ship a new, properly numbered revision instead.

## Future upstream versions

Daily scheduled discovery only creates a deduplicated issue; it does not silently publish binaries.

To attempt source migration:

```sh
gh workflow run bratiki-upstream.yml -R patchedronin/Rawline-Bratiki -f mode=prepare-update
```

Optional: `-f tag=rawline-X.Y.Z`. The helper fetches upstream source without executing it, applies the complete custom patch using three-way merge, regenerates it against the new baseline, preserves the reviewed Bratiki workflows and opens a **draft pull request**. Conflicts, upstream signer/minSdk changes or manifest inconsistencies stop the preparation. A clean text merge is not proof of runtime compatibility.

Review/merge the PR, then dispatch an unsigned build explicitly: changes made with `GITHUB_TOKEN` may not trigger another workflow automatically. Rebuild the pinned core when upstream changes its core reference; never reuse an old AAR blindly. Changed transport count fails staging until reviewed.

Version code is upstream `VERSION_CODE * 1000 + revision`; revisions accepted are 2..998. For a later upstream base use revision 2 again; it still outranks earlier upstream bases. Revision 2 uses tag `rawline-X.Y.Z`; subsequent hotfixes use `rawline-X.Y.Z+r3`, `+r4`, etc. The updater accepts semver build metadata and selects the higher manifest versionCode. Do not reuse a revision for a different APK or overwrite an existing public release.

## Local checks

```sh
python -m unittest discover -s bratiki/scripts -p 'test_*.py'
python bratiki/scripts/upstream.py check
python bratiki/scripts/build.py stage --apk /path/to/verified-upstream.apk --apksigner /path/to/apksigner
```

To review a migration locally without pushing: use a clean `main` checkout and run `python bratiki/scripts/upstream.py prepare-update --tag rawline-X.Y.Z`. It generates a reviewable Git bundle. The tag must differ from the current pin.

## Android updates

The APK must retain application ID `app.rawline.mobile`, the existing signing certificate and a greater versionCode. UpdateVerifier retains package/version/hash/signature checks. Installation may show Android's confirmation screen or require permission to install updates from Rawline. No root or ADB is needed after installing the channel-enabled build.

## Licensing

Keep the original GPL license and notices. Each binary release links the exact modified source commit. Carrier binaries and bundled assets are staged from the checksum- and certificate-verified upstream APK with their license/source notices. These notices do not replace obligations to provide corresponding source where required.
