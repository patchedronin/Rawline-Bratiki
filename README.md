# Rawline Bratiki

Personal Android UI customization of [ttrroyy/RawlineMobile](https://github.com/ttrroyy/RawlineMobile), based on its stable release tags. This is an independent modified build, not an official Rawline release.

## Changes

- Minimal home: connection button above a pinned subscription/server block; at most five rows visible, with scrolling for additional servers.
- Subscription selector, add, refresh and menu in one row.
- Importable JSON color palettes.
- Native Android Canvas button styles: **Hatch** and **Courtyard with power plant**, with lightning, furnace glow and snow controls.
- Existing VPN protocols, routing and profile management remain upstream functionality.
- In-app update checks use this repository's Releases rather than the upstream APK channel.

Current upstream baseline: **rawline-1.1.4**, commit `12295381f4a749c1555c56e0e4dbf9b44f4330be`. Build metadata and source migration tooling live under `bratiki/`.

## Updates

New upstream releases must be applied to the source and built; switching a JSON palette does not update the app. Automated upstream discovery is separate from build and publication. Source conflicts stop migration instead of publishing a partly modified application.

Unsigned builds run without signing secrets. Publishing is a separate manual workflow that takes a successful trusted build artifact, signs it in the protected **release** environment, and publishes an APK and update metadata. The environment requires the owner's approval and accepts only the `main` branch. It never runs upstream Gradle code with the signing key available.

Android may require confirmation of APK installation and permission to install updates from this app. Silent installation is not promised. Keep the existing signing key: a different certificate cannot update an already installed build without uninstalling it.

## Security and private data

This repository must not contain device backups, subscription URLs, VPN credentials, signing keys, `.env` files, or phone logs. The application binary contains no personal subscription; import your own. Public release files are not encrypted.

The signing certificate fingerprint is public and is pinned by the updater:

```
b3729dc9e7538e42f95e96b8f61c400ad9f4e39d309b23c7e4594043fd0e134c
```

The private signing key is not in Git. GitHub Environment Secrets are a trust boundary, not protection against an owner-account or release-workflow compromise. Restrict account access and enable two-factor authentication.

## Building

This source tree retains upstream's Gradle wrapper, core build action and protocol source pins. Android application ID remains `app.rawline.mobile`. For local builds use JDK 17, Android SDK 35/build-tools 35.0.1 and the core AAR built for the pinned core reference. The repository workflows describe the complete staging process for native transports and generated assets. Do not ship a build missing native transports or the rule-set catalog.

## License and attribution

Licensed under **GPL-3.0**; see [LICENSE](LICENSE). Original RawlineMobile and its ThroneForAndroid/SagerNet ancestry retain their authorship and notices. Original project: [ttrroyy/RawlineMobile](https://github.com/ttrroyy/RawlineMobile). Modified UI and custom update-channel integration are maintained here. Binary dependencies carry their own notices; publishing a modified APK does not remove source-distribution obligations under the applicable licenses.
