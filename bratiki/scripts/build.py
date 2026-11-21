#!/usr/bin/env python3
"""Stage pinned upstream APK data and package an unsigned, attributable build."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SIGNER = "b3729dc9e7538e42f95e96b8f61c400ad9f4e39d309b23c7e4594043fd0e134c"
EXPECTED_NATIVE_LIBRARIES = 19
ABIS = ("arm64-v8a", "armeabi-v7a")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def properties():
    return dict(line.strip().split("=", 1) for line in (ROOT / "nb4a.properties").read_text().splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def load_pin():
    pin = json.loads((ROOT / "bratiki/upstream.json").read_text())
    props = properties()
    require(pin["repository"] == "ttrroyy/RawlineMobile", "Unexpected upstream repository")
    require(pin["tag"] == "rawline-" + pin["versionName"], "Invalid stable upstream tag")
    require(re.fullmatch(r"\d+\.\d+\.\d+", pin["versionName"]), "Invalid stable version")
    require(props["PACKAGE_NAME"] == "app.rawline.mobile", "Package changed")
    require(props["VERSION_NAME"] == pin["versionName"], "Source version differs from pin")
    require(int(props["VERSION_CODE"]) == pin["versionIndex"], "Source version index differs from pin")
    require(props.get("THRONE_AAR_REF", props.get("THRONE_CORE_REF")) == pin["coreRef"], "Core pin differs")
    for key in ("commit", "coreRef"):
        require(re.fullmatch(r"[a-f0-9]{40}", pin[key]), "Invalid commit pin")
    require(re.fullmatch(r"[a-f0-9]{64}", pin["apk"]["sha256"]), "Invalid APK digest")
    expected = f'https://github.com/{pin["repository"]}/releases/download/{pin["tag"]}/{pin["apk"]["name"]}'
    require(pin["apk"]["url"] == expected, "APK URL does not match pin")
    return pin


def selected_apk(pin, abi):
    require(abi in ABIS, "Unsupported ABI")
    apk = pin["apk"] if abi == "arm64-v8a" else pin.get("apks", {}).get(abi)
    require(apk is not None, "ABI has no reviewed upstream pin")
    require(re.fullmatch(r"[a-f0-9]{64}", apk["sha256"]), "Invalid APK digest")
    expected = f'https://github.com/{pin["repository"]}/releases/download/{pin["tag"]}/{apk["name"]}'
    require(apk["url"] == expected, "ABI APK URL does not match pin")
    return apk


def download_apk(apk_pin, abi):
    folder = Path(os.environ.get("RUNNER_TEMP", str(ROOT / ".gradle")))
    folder.mkdir(parents=True, exist_ok=True)
    apk = folder / f"bratiki-upstream-{abi}.apk"
    request = urllib.request.Request(apk_pin["url"], headers={"User-Agent": "Rawline-Bratiki-build"})
    with urllib.request.urlopen(request, timeout=180) as response, apk.open("wb") as output:
        shutil.copyfileobj(response, output)
    return apk


def stage_abi(pin, abi, apk, apksigner, copy_shared=True):
    apk_pin = selected_apk(pin, abi)
    require(apk.stat().st_size == apk_pin["size"], f"Upstream {abi} APK size mismatch")
    require(digest(apk) == apk_pin["sha256"], f"Upstream {abi} APK SHA-256 mismatch")
    result = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                            text=True, check=True, capture_output=True).stdout
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", result)
    require(len(certs) == 1 and certs[0].lower() == pin["signerSha256"], f"Upstream {abi} signer mismatch")
    carriers = 0
    licenses = 0
    copied = []
    with zipfile.ZipFile(apk) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate APK entries")
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename)
            require(not name.is_absolute() and ".." not in name.parts and "\\" not in entry.filename,
                    "Unsafe APK path")
            if entry.is_dir():
                continue
            destination = None
            if entry.filename.startswith(f"lib/{abi}/") and name.suffix == ".so" and name.name != "librawline.so":
                require(len(name.parts) == 3, "Unexpected native library path")
                destination = ROOT / "deployment/carriers" / abi / name.name
                carriers += 1
            elif len(name.parts) > 2 and name.parts[0] == "assets" and name.parts[1] in (
                    "carrier-licenses", "routeprofiles", "sb-dashboard", "schema"):
                if name.parts[1] == "carrier-licenses":
                    if len(name.parts) > 3 and name.parts[2] != abi:
                        continue
                    licenses += 1
                elif not copy_shared:
                    continue
                destination = ROOT / "app/src/main" / str(name)
            if destination:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target)
                copied.append(str(name))
    require(carriers == EXPECTED_NATIVE_LIBRARIES - 1,
            f"Upstream {abi} carrier set changed; review native library completeness before migrating")
    require(licenses > 0, f"Upstream {abi} APK lacks carrier licenses")
    return {"carriers": carriers, "carrier-licenses": licenses, "files": len(copied)}


def stage(args):
    pin = load_pin()
    abi = getattr(args, "abi", "arm64-v8a")
    if abi in ("universal", "both"):
        require(args.apk is None, "Two-ABI staging downloads both reviewed ABI APKs; --apk is single-ABI only")
        targets = ABIS
    else:
        targets = (abi,)
    summary = {}
    for target in targets:
        apk = args.apk
        if not apk:
            apk = download_apk(selected_apk(pin, target), target)
        summary[target] = stage_abi(pin, target, apk, args.apksigner,
                                    copy_shared=(target == "arm64-v8a" or abi not in ("universal", "both")))
    require((ROOT / "app/src/main/assets/sb-dashboard/index.html").is_file(), "Dashboard index missing")
    require((ROOT / "app/src/main/assets/routeprofiles/srslist.h").is_file(), "Route catalog missing")
    require((ROOT / "app/src/main/assets/schema/sing-box.json").is_file(), "Schema missing")
    print(json.dumps(summary, sort_keys=True))


def verify_built_apk(apk, expected_abis, pin, version_code, args):
    verify = subprocess.run([str(args.apksigner), "verify", str(apk)], capture_output=True)
    require(verify.returncode != 0, "Build was unexpectedly signed")
    badging = subprocess.check_output([str(args.aapt2), "dump", "badging", str(apk)], text=True)
    for text in ("name='app.rawline.mobile'", f"versionCode='{version_code}'",
                 f"versionName='{pin['versionName']}'"):
        require(text in badging, f"APK metadata mismatch: {text}")
    native_match = re.search(r"^native-code: (.+)$", badging, re.M)
    require(native_match is not None, "APK has no native-code")
    native_abis = set(re.findall(r"'([^']+)'", native_match[1]))
    require(native_abis == set(expected_abis), f"APK ABI set mismatch: {sorted(native_abis)}")
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging, re.M)
    require(sdk is not None and int(sdk[1]) == pin["minSdk"], "APK minimum SDK mismatch")
    with zipfile.ZipFile(apk) as archive:
        native = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
        require(len(native) == EXPECTED_NATIVE_LIBRARIES * len(expected_abis)
                and len(set(native)) == len(native),
                "APK lacks the complete reviewed native carrier set")
        per_abi = {target: 0 for target in expected_abis}
        for name in native:
            parts = name.split("/")
            require(len(parts) == 3 and parts[0] == "lib" and parts[1] in expected_abis,
                    "APK contains an unexpected ABI")
            per_abi[parts[1]] += 1
            machine = 40 if parts[1] == "armeabi-v7a" else 183
            elf_class = 1 if parts[1] == "armeabi-v7a" else 2
            header = archive.read(name)[:20]
            require(header[:5] == b'\x7fELF' + bytes([elf_class]) and header[5] == 1
                    and int.from_bytes(header[18:20], 'little') == machine, "Wrong ELF architecture")
        for target in expected_abis:
            require(per_abi[target] == EXPECTED_NATIVE_LIBRARIES,
                    f"APK lacks the complete {target} library set")
            require(f"lib/{target}/librawline.so" in native, f"APK lacks freshly built {target} RawlineCore")
    return native


def package(args):
    pin = load_pin()
    abi = args.abi
    require(2 <= args.revision <= 998, "Revision must be 2..998")
    version_code = pin["versionIndex"] * 1000 + args.revision
    if abi == "both":
        builds = [(target, f"*{target}*unsigned.apk", (target,)) for target in ABIS]
    elif abi == "universal":
        builds = [("universal", "*universal*unsigned.apk", ABIS)]
    else:
        builds = [(abi, f"*{abi}*unsigned.apk", (abi,))]
    for _, _, expected in builds:
        for target in expected:
            selected_apk(pin, target)
    args.output.mkdir(parents=True, exist_ok=True)
    files = {}
    native_counts = {}
    for label, pattern, expected_abis in builds:
        candidates = list((ROOT / "app/build/outputs/apk/oss/release").glob(pattern))
        require(len(candidates) == 1, f"Expected exactly one unsigned {label} APK")
        apk = candidates[0]
        native_counts[label] = len(verify_built_apk(apk, expected_abis, pin, version_code, args))
        target = args.output / f"unsigned-{label}.apk"
        shutil.copyfile(apk, target)
        files[label] = {"name": target.name, "size": target.stat().st_size, "sha256": digest(target)}
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    require(os.environ.get("GITHUB_SHA", source) == source, "Checkout is not workflow source SHA")
    metadata = {"schema": 1, "repository": os.environ.get("GITHUB_REPOSITORY", "patchedronin/Rawline-Bratiki"),
                "sourceSha": source, "runId": os.environ.get("GITHUB_RUN_ID"),
                "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "upstream": pin,
                "packageName": "app.rawline.mobile", "versionName": pin["versionName"],
                "versionCode": version_code, "revision": args.revision, "minSdk": pin["minSdk"],
                 "signerSha256": SIGNER, "abi": abi}
    if abi == "both":
        metadata.update(abis=list(ABIS), unsigned=files, nativeLibrariesByAbi=native_counts)
    else:
        label = "universal" if abi == "universal" else abi
        entry = files[label]
        metadata.update(unsignedSize=entry["size"], unsignedSha256=entry["sha256"])
        if abi == "universal":
            metadata.update(abis=list(ABIS), nativeLibraries=native_counts[label])
    (args.output / "build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    staging = sub.add_parser("stage")
    staging.add_argument("--apk", type=Path, help="Use a local upstream APK; all pin checks still apply")
    staging.add_argument("--apksigner", required=True, type=Path)
    staging.add_argument("--abi", choices=(*ABIS, "universal", "both"), default="arm64-v8a")
    staging.set_defaults(handler=stage)
    packaging = sub.add_parser("package")
    packaging.add_argument("--revision", required=True, type=int)
    packaging.add_argument("--abi", choices=(*ABIS, "universal", "both"), default="arm64-v8a")
    packaging.add_argument("--apksigner", required=True, type=Path)
    packaging.add_argument("--aapt2", required=True, type=Path)
    packaging.add_argument("--output", type=Path, default=ROOT / "bratiki-dist")
    packaging.set_defaults(handler=package)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
