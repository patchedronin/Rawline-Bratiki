# Build and release operation

## Mobile and TV APKs (default)

`abi=both` is the default in both workflows and on every push to `main`.
Staging requires both reviewed donors (`apk` and `apks.armeabi-v7a` in
`upstream.json`) and copies 19 native libraries for each ABI. The core action
builds one pinned AAR with both JNI directories; Gradle emits two split APKs
(19 libraries each). The manifest keeps leanback optional, and `BratikiDefaults`
adds the TV YouTube package only when `SagerNet.isTv`.

One release contains two signed assets:
- `RawlineBratiki-X.Y.Z-rN-mobile-arm64-v8a.apk`
- `RawlineBratiki-X.Y.Z-rN-tv-armeabi-v7a.apk`

Its update manifest maps `arm64-v8a` to the mobile asset and `armeabi-v7a` to
the TV asset, so each device downloads only its ~160 MiB APK. A single
304 MiB universal APK remains available with `abi=universal` for manual installs,
but it is not the default and is not referenced by the update manifest unless
built as `abi=universal`.

`abi=arm64-v8a` and `abi=armeabi-v7a` still produce one signed APK of that ABI.
A merged upstream migration triggers the `both` build automatically on push to
`main`; signing and publication remain manual and owner-approved.

## ARM32 TV support (revision 5)

Per-ABI builds remain available with reviewed `abi` values `arm64-v8a` (phone)
and `armeabi-v7a` (32-bit TV); `both` is the default. ARM32 donor is
separately size/SHA/signature pinned in `upstream.json`. The build produces a
fresh pinned multi-ABI core, packages only the requested split and verifies all
19 ELF headers. Artifact ABI is checked again against the explicit expected
target before signing. No local unsigned APK upload can replace a trusted main
workflow artifact.

For TV use a new unused revision (5), pass `abi=armeabi-v7a` to both workflows,
create the matching exact-source tag after the unsigned run succeeds, then have
the owner approve environment `release`. Existing phone releases/assets are never
overwritten. An ARM32-only release (`abi=armeabi-v7a`) lists only that ABI and stays a
prerelease; a `both` release lists both light assets. The updater keeps earlier
releases for the other ABI. Test the TV APK before deploying it.

The first ARM32 release uses tag `rawline-1.1.4+r5-tv` and is a **prerelease**, not
GitHub latest: the existing stable phone r4 remains unchanged and is not offered
an incompatible TV-only update. Promote a later reviewed revision only after
real TV tests; never edit an already published artifact to change its role.

The new-install preset includes `com.google.android.youtube.tv` only on Android TV
(SagerNet.isTv). It does not migrate existing installations or embed credentials.

## Trust boundaries

- `bratiki-build.yml`: runs only `main`, no signing secrets, contents read-only. Revision `2` produces 1.1.4 versionCode `110002`, above the existing local mod `110001`.
- `bratiki-release.yml`: manual dispatch with successful unsigned build `run_id` and expected `revision`. The validation job checks run provenance and artifact hashes without project checkout. Publishing requires approval in environment `release`, restricted to `main`.
- The publishing job does not execute source code or Gradle from the unsigned build. It signs with `apksigner`, independently inspects package/version/ABI/certificate and publishes a draft only after checking the uploaded APK digest.
- Environment Secrets: `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS`. Never put these in repository variables, source files, artifacts or update manifests.
- Current signer SHA-256: `b3729dc9e7538e42f95e96b8f61c400ad9f4e39d309b23c7e4594043fd0e134c`.

## Initial release

1. Push reviewed source to `main`; the unsigned mobile+TV build runs automatically (revision 2).
2. Open Actions → **Bratiki unsigned mobile+TV build** and wait for success. Copy its run ID from the URL. Build artifacts expire after 14 days.
3. Verify the build's `head_sha`, then create a lightweight `rawline-1.1.4` tag at that exact commit using the owner's GitHub account. GitHub rejects release creation by `GITHUB_TOKEN` when it also requests creation of a tag pointing to workflow changes. The signing workflow accepts only an existing tag at the verified source SHA.
4. Dispatch **Bratiki isolated signing and release**, `run_id=<successful run>`, `revision=2`, `abi=both`.
5. Review the selected source commit and build provenance, then approve environment `release` in GitHub.
6. Confirm Releases contains `rawline-1.1.4`, both mobile and TV APKs, source commit links and embedded `rawline-update` metadata with per-ABI asset entries.
7. Install the first channel-enabled APK manually over the existing mod. Future **Check updates** requests use this repository.

GitHub CLI equivalents:

```sh
gh workflow run bratiki-build.yml -R patchedronin/Rawline-Bratiki -f revision=2 -f abi=both
gh run list -R patchedronin/Rawline-Bratiki --workflow bratiki-build.yml
gh api -X POST repos/patchedronin/Rawline-Bratiki/git/refs -f ref=refs/tags/rawline-1.1.4 -f sha=VERIFIED_BUILD_SOURCE_SHA
gh workflow run bratiki-release.yml -R patchedronin/Rawline-Bratiki -f run_id=RUN_ID -f revision=2 -f abi=both
```

An existing public release is never replaced; an existing source tag is accepted only if it points to the exact selected build commit. If publication is interrupted, inspect the draft, assets and tag manually. Delete an incomplete **unpublished** draft only after verifying its provenance, then explicitly retry. Do not remove a published release to hide an error: ship a new, properly numbered revision instead.

## Future upstream versions

Daily scheduled discovery compares the latest stable upstream tag with `upstream.json`. When it differs, the watcher automatically applies the reviewed custom patch with a three-way merge, pushes a `bratiki/update-<tag>` branch and opens a **draft** source-sync PR; no upstream code is executed and no binary is published. Existing open PRs are left untouched. A failed migration creates or comments on a deduplicated issue.

To retry or prepare a specific tag manually:

```sh
gh workflow run bratiki-upstream.yml -R patchedronin/Rawline-Bratiki -f mode=prepare-update
```

Optional: `-f tag=rawline-X.Y.Z`. The helper fetches upstream source without executing it, applies the complete custom patch using three-way merge, regenerates it against the new baseline, preserves the reviewed Bratiki workflows and opens a **draft pull request**. Conflicts, upstream signer/minSdk changes or manifest inconsistencies stop the preparation. A clean text merge is not proof of runtime compatibility.

Review/merge the PR. A normal merge to `main` triggers `bratiki-build.yml`, whose default `abi=both` produces `unsigned-arm64-v8a.apk` and `unsigned-armeabi-v7a.apk` (revision 2). To verify the migration branch before merging, dispatch the same workflow with `--ref bratiki/update-rawline-X.Y.Z -f revision=2 -f abi=both`; that unsigned artifact cannot be released because the release validator requires a `main` build. If the merge was made with `GITHUB_TOKEN` and no run appeared, dispatch the build explicitly. Rebuild the pinned core when upstream changes its core reference; never reuse an old AAR blindly. Changed transport count or a missing ARM32 donor fails staging until reviewed.

Version code is upstream `VERSION_CODE * 1000 + revision`; revisions accepted are 2..998. For a later upstream base use revision 2 again; it still outranks earlier upstream bases. Revision 2 uses tag `rawline-X.Y.Z`; subsequent hotfixes use `rawline-X.Y.Z+r3`, `+r4`, etc. The updater accepts semver build metadata and selects the higher manifest versionCode. Do not reuse a revision for a different APK or overwrite an existing public release.

## Local checks

```sh
python -m unittest discover -s bratiki/scripts -p 'test_*.py'
python bratiki/scripts/upstream.py check
python bratiki/scripts/build.py stage --apk /path/to/verified-upstream.apk --apksigner /path/to/apksigner
python bratiki/scripts/build.py stage --abi both --apksigner /path/to/apksigner  # downloads both pinned donors
```

To review a migration locally without pushing: use a clean `main` checkout and run `python bratiki/scripts/upstream.py prepare-update --tag rawline-X.Y.Z`. It generates a reviewable Git bundle. The tag must differ from the current pin.

## First-install preset (1.1.4 r3)

New profile databases start in TUN / **only selected apps** mode: official Telegram
(`org.telegram.messenger`, `org.telegram.messenger.web`) and YouTube
(`com.google.android.youtube`); on Android TV the TV APK also selects
`com.google.android.youtube.tv` via `SagerNet.isTv`. Other apps, including
browsers and YouTube Music, stay outside the VPN. Missing packages remain selected by name for later installation;
if none is installed, VPN startup fails instead of capturing every app.

MTU 1200, tun0 guard and proxy QUIC blocking are enabled. LAN sharing and app VPN bypass
are disabled; listeners remain loopback-only. DNS: Cloudflare DoH for remote, system
resolver for direct, DNS routing on, FakeDNS off. Logs: warn/warning; connection stats,
exit-country lookups and both APIs are off. The existing minimal courtyard UI remains.
No subscription, server credential, UID or device identifier is bundled. Inbound/API
secrets retain their per-device generation. The existing Default route sends captured
traffic through the selected proxy; user/subscription routing can override it.

Seeding runs only inside Room's database-creation transaction, not on upgrades,
restarts or restores. Existing users retain all their settings. Android VPN permission,
notifications and battery restrictions still need confirmation on each device. On
firmware without socket-owner reporting, disable the tun0 guard if connectivity fails.

## Subscription toolbar fix (1.1.4 r4)

Switching the home subscription replaces its refresh/menu controls instead of
appending another pair. Toolbar actions are rebuilt independently of cached server
rows, including changes to other groups. Local groups do not show refresh. The
subscription picker keeps its remaining row width. No subscription or VPN setting
is migrated by this change.

## Android updates

The APK must retain application ID `app.rawline.mobile`, the existing signing certificate and a greater versionCode. UpdateVerifier retains package/version/hash/signature checks. Installation may show Android's confirmation screen or require permission to install updates from Rawline. No root or ADB is needed after installing the channel-enabled build.

## Licensing

Keep the original GPL license and notices. Each binary release links the exact modified source commit. Carrier binaries and bundled assets are staged from the checksum- and certificate-verified upstream APK with their license/source notices. These notices do not replace obligations to provide corresponding source where required.
