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


def stage(args):
    pin = load_pin()
    abi = getattr(args, "abi", "arm64-v8a")
    apk_pin = selected_apk(pin, abi)
    apk = args.apk
    if not apk:
        apk = Path(os.environ.get("RUNNER_TEMP", str(ROOT / ".gradle"))) / "bratiki-upstream.apk"
        apk.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(apk_pin["url"], headers={"User-Agent": "Rawline-Bratiki-build"})
        with urllib.request.urlopen(request, timeout=180) as response, apk.open("wb") as output:
            shutil.copyfileobj(response, output)
    require(apk.stat().st_size == apk_pin["size"], "Upstream APK size mismatch")
    require(digest(apk) == apk_pin["sha256"], "Upstream APK SHA-256 mismatch")
    result = subprocess.run([str(args.apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                            text=True, check=True, capture_output=True).stdout
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", result)
    require(len(certs) == 1 and certs[0].lower() == pin["signerSha256"], "Upstream signer mismatch")
    counts = {key: 0 for key in ("carriers", "carrier-licenses", "routeprofiles", "sb-dashboard", "schema")}
    with zipfile.ZipFile(apk) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate APK entries")
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename)
            require(not name.is_absolute() and ".." not in name.parts and "\\" not in entry.filename,
                    "Unsafe APK path")
            if entry.is_dir():
                continue
            destination = None
            group = None
            if entry.filename.startswith(f"lib/{abi}/") and name.suffix == ".so" and name.name != "librawline.so":
                require(len(name.parts) == 3, "Unexpected native library path")
                destination = ROOT / "deployment/carriers" / abi / name.name
                group = "carriers"
            elif len(name.parts) > 2 and name.parts[0] == "assets" and name.parts[1] in counts:
                group = name.parts[1]
                if group == "carrier-licenses" and len(name.parts) > 3 and name.parts[2] != abi:
                    continue
                destination = ROOT / "app/src/main" / str(name)
            if destination:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target)
                counts[group] += 1
    require(all(counts.values()), f"Upstream APK lacks required runtime data: {counts}")
    require(counts["carriers"] == EXPECTED_NATIVE_LIBRARIES - 1,
            "Upstream carrier set changed; review native library completeness before migrating")
    require((ROOT / "app/src/main/assets/sb-dashboard/index.html").is_file(), "Dashboard index missing")
    require((ROOT / "app/src/main/assets/routeprofiles/srslist.h").is_file(), "Route catalog missing")
    require((ROOT / "app/src/main/assets/schema/sing-box.json").is_file(), "Schema missing")
    print(json.dumps(counts, sort_keys=True))


def package(args):
    pin = load_pin()
    abi = args.abi
    selected_apk(pin, abi)
    require(2 <= args.revision <= 998, "Revision must be 2..998")
    candidates = list((ROOT / "app/build/outputs/apk/oss/release").glob(f"*{abi}*unsigned.apk"))
    require(len(candidates) == 1, "Expected exactly one unsigned selected-ABI APK")
    apk = candidates[0]
    verify = subprocess.run([str(args.apksigner), "verify", str(apk)], capture_output=True)
    require(verify.returncode != 0, "Build was unexpectedly signed")
    badging = subprocess.check_output([str(args.aapt2), "dump", "badging", str(apk)], text=True)
    version_code = pin["versionIndex"] * 1000 + args.revision
    for text in ("name='app.rawline.mobile'", f"versionCode='{version_code}'", f"versionName='{pin['versionName']}'",
                  f"native-code: '{abi}'"):
        require(text in badging, f"APK metadata mismatch: {text}")
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging, re.M)
    require(sdk is not None and int(sdk[1]) == pin["minSdk"], "APK minimum SDK mismatch")
    with zipfile.ZipFile(apk) as archive:
        native = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
        require(len(native) == EXPECTED_NATIVE_LIBRARIES and len(set(native)) == len(native),
                "APK lacks the complete reviewed native carrier set")
        require(all(name.startswith(f"lib/{abi}/") for name in native), "APK contains another ABI")
        require(f"lib/{abi}/librawline.so" in native, "APK lacks freshly built RawlineCore")
        machine = 40 if abi == "armeabi-v7a" else 183
        elf_class = 1 if abi == "armeabi-v7a" else 2
        for name in native:
            header = archive.read(name)[:20]
            require(header[:5] == b'\x7fELF' + bytes([elf_class]) and header[5] == 1
                    and int.from_bytes(header[18:20], 'little') == machine, "Wrong ELF architecture")
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    require(os.environ.get("GITHUB_SHA", source) == source, "Checkout is not workflow source SHA")
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / f"unsigned-{abi}.apk"
    shutil.copyfile(apk, target)
    metadata = {"schema": 1, "repository": os.environ.get("GITHUB_REPOSITORY", "patchedronin/Rawline-Bratiki"),
                "sourceSha": source, "runId": os.environ.get("GITHUB_RUN_ID"),
                "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "upstream": pin,
                "packageName": "app.rawline.mobile", "versionName": pin["versionName"],
                "versionCode": version_code, "revision": args.revision, "minSdk": pin["minSdk"],
                 "signerSha256": SIGNER, "abi": abi,
                 "unsignedSize": target.stat().st_size, "unsignedSha256": digest(target)}
    (args.output / "build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    staging = sub.add_parser("stage")
    staging.add_argument("--apk", type=Path, help="Use a local upstream APK; all pin checks still apply")
    staging.add_argument("--apksigner", required=True, type=Path)
    staging.add_argument("--abi", choices=ABIS, default="arm64-v8a")
    staging.set_defaults(handler=stage)
    packaging = sub.add_parser("package")
    packaging.add_argument("--revision", required=True, type=int)
    packaging.add_argument("--abi", choices=ABIS, default="arm64-v8a")
    packaging.add_argument("--apksigner", required=True, type=Path)
    packaging.add_argument("--aapt2", required=True, type=Path)
    packaging.add_argument("--output", type=Path, default=ROOT / "bratiki-dist")
    packaging.set_defaults(handler=package)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
