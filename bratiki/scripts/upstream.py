#!/usr/bin/env python3
"""Notify about stable upstream releases or prepare a clean, review-only source migration PR."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = "ttrroyy/RawlineMobile"
FORK = "patchedronin/Rawline-Bratiki"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def api(path, data=None):
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
               "User-Agent": "Rawline-Bratiki-upstream"}
    if os.environ.get("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    request = urllib.request.Request("https://api.github.com/" + path,
                                     data=json.dumps(data).encode() if data is not None else None, headers=headers)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def pin():
    return json.loads((ROOT / "bratiki/upstream.json").read_text())


def release(tag=None):
    if tag:
        require(re.fullmatch(r"rawline-\d+\.\d+\.\d+", tag), "Only rawline-X.Y.Z stable tags are accepted")
    result = api(f"repos/{UPSTREAM}/releases/" + (f"tags/{tag}" if tag else "latest"))
    require(not result["draft"] and not result["prerelease"], "Upstream release is not stable")
    require(re.fullmatch(r"rawline-\d+\.\d+\.\d+", result["tag_name"]), "Unexpected upstream tag")
    return result


def notify(item, failure=None):
    repo = os.environ.get("GITHUB_REPOSITORY", FORK)
    require(repo == FORK, "Refusing to write to another repository")
    title = f'Upstream {item["tag_name"]}: source migration review required'
    existing = []
    for page in range(1, 11):
        issues = api(f"repos/{repo}/issues?state=open&per_page=100&page={page}")
        existing.extend(issues)
        if len(issues) < 100:
            break
    else:
        raise ValueError("Too many open issues to safely deduplicate notification")
    body = (f'Upstream release: {item["html_url"]}\n\n'
            f'Currently pinned: `{pin()["tag"]}`. This notification does not publish or sign an APK.\n\n'
            'Run the Bratiki upstream watcher with `prepare-update` to attempt a three-way source migration. '
            'The resulting PR needs review and a successful unsigned build before manual isolated signing. '
            'A clean textual merge is not proof that an arbitrary future upstream release is compatible.\n')
    if failure:
        run = os.environ.get("GITHUB_RUN_ID", "")
        body += f'\nAutomatic source preparation failed; no release was published. Inspect any source branch before retrying. Details: https://github.com/{repo}/actions/runs/{run}\n'
    matching = [issue for issue in existing if issue["title"] == title and "pull_request" not in issue]
    if not matching:
        print(api(f"repos/{repo}/issues", {"title": title, "body": body})["html_url"])
    else:
        if failure:
            api(f'repos/{repo}/issues/{matching[0]["number"]}/comments', {"body": body})
        print(matching[0]["html_url"])


def git(*args, cwd=ROOT, binary=False, env=None):
    return subprocess.check_output(["git", *args], cwd=cwd, text=not binary, env=env).strip() if not binary else subprocess.check_output(["git", *args], cwd=cwd, env=env)


def protected(name):
    return (name in ("README.md", ".gitignore") or name.startswith("bratiki/")
            or name.startswith(".github/workflows/") or name.startswith(".github/actions/"))


def metadata(item, tree, commit):
    matches = re.findall(r"<!-- rawline-update\s*\n(.*?)\n-->", item.get("body", ""), re.S)
    require(len(matches) == 1, "Upstream release lacks one unambiguous update manifest")
    manifest = json.loads(matches[0])
    props = dict(line.strip().split("=", 1) for line in (tree / "nb4a.properties").read_text().splitlines()
                 if "=" in line and not line.lstrip().startswith("#"))
    require(manifest["schema"] == 1 and manifest["packageName"] == "app.rawline.mobile", "Invalid upstream manifest")
    require(manifest["versionName"] == props["VERSION_NAME"] == item["tag_name"].removeprefix("rawline-"), "Upstream version differs")
    require(manifest["signerSha256"] == pin()["signerSha256"], "Upstream signing identity changed; manual review required")
    require(manifest["minSdk"] == 24, "Upstream minimum Android version changed; manual review required")
    require(manifest["versionCode"] == int(props["VERSION_CODE"]) * 1000 + 999, "Unexpected upstream version code")
    core = props.get("THRONE_AAR_REF", props.get("THRONE_CORE_REF"))
    require(core == manifest["coreRef"] and re.fullmatch(r"[a-f0-9]{40}", core), "Upstream core pin differs")
    def checked_asset(abi):
        asset = manifest["assets"][abi]
        require(re.fullmatch(r"[a-zA-Z0-9._-]+\.apk", asset["name"]), "Unsafe upstream APK name")
        candidates = [a for a in item["assets"] if a["name"] == asset["name"]]
        require(len(candidates) == 1, "Upstream APK missing or ambiguous")
        apk = candidates[0]
        require(apk["size"] == asset["size"] and apk.get("digest") == "sha256:" + asset["sha256"], "Upstream digest differs")
        require(re.fullmatch(r"[a-f0-9]{64}", asset["sha256"]), "Invalid upstream APK SHA-256")
        url = f'https://github.com/{UPSTREAM}/releases/download/{item["tag_name"]}/{asset["name"]}'
        require(apk["browser_download_url"] == url, "Unexpected upstream APK URL")
        return {**asset, "url": url}
    asset = checked_asset("arm64-v8a")
    extra = {}
    # Once reviewed ABI support exists, upstream updates must keep it pinned too.
    for abi in pin().get("apks", {}):
        extra[abi] = checked_asset(abi)
    return {"schema": 1, "repository": UPSTREAM, "tag": item["tag_name"], "commit": commit,
            "versionName": props["VERSION_NAME"], "versionIndex": int(props["VERSION_CODE"]),
            "coreRef": core, "minSdk": manifest["minSdk"], "signerSha256": manifest["signerSha256"],
            "apk": asset, **({"apks": extra} if extra else {})}


def prepare(item, publish):
    previous = pin()
    require(item["tag_name"] != previous["tag"], "This upstream release is already pinned")
    require((ROOT / "bratiki/custom.patch").is_file(), "Missing reviewed bratiki/custom.patch")
    require(not git("status", "--porcelain"), "Source checkout must be clean")
    require(git("branch", "--show-current") == "main", "Prepare updates from a clean main checkout")
    if publish:
        require(os.environ.get("GITHUB_REPOSITORY") == FORK and os.environ.get("GITHUB_REF") == "refs/heads/main",
                "Publishing update PRs requires the trusted main workflow")
        require(os.environ.get("GH_TOKEN"), "GH_TOKEN required to publish PR")
        require(shutil.which("gh"), "GitHub CLI is required to create the source-sync PR")
    main_sha = git("rev-parse", "HEAD")
    preserved = [name for name in git("ls-files", "-z").split("\0") if name and protected(name)
                 and (not name.startswith(".github/workflows/") or Path(name).name.startswith("bratiki"))]
    branch = "bratiki/update-" + item["tag_name"]
    if publish:
        existing = json.loads(subprocess.check_output(
            ["gh", "pr", "list", "--repo", FORK, "--head", branch, "--state", "open", "--json", "number"],
            text=True))
        if existing:
            print(f"Open review PR already exists for {branch}; nothing to do")
            return
    with tempfile.TemporaryDirectory(prefix="bratiki-sync-", dir=os.environ.get("RUNNER_TEMP")) as folder:
        tree = Path(folder) / "source"
        subprocess.run(["git", "clone", "--quiet", "--no-hardlinks", "--no-checkout", str(ROOT), str(tree)], check=True)
        git("remote", "add", "upstream", f"https://github.com/{UPSTREAM}.git", cwd=tree)
        # Fetch the patch baseline too: three-way apply needs its original blob objects.
        git("fetch", "--no-tags", "upstream", previous["commit"], cwd=tree)
        git("fetch", "--no-tags", "upstream", "refs/tags/" + item["tag_name"], cwd=tree)
        upstream_sha = git("rev-parse", "FETCH_HEAD^{commit}", cwd=tree)
        git("checkout", "--quiet", "--detach", upstream_sha, cwd=tree)
        require(not any(entry.startswith("120000 ") for entry in git("ls-tree", "-r", upstream_sha, cwd=tree).splitlines()),
                "Upstream contains symlinks; manual source migration review required")
        updated = metadata(item, tree, upstream_sha)
        git("config", "user.name", "github-actions[bot]", cwd=tree)
        git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com", cwd=tree)
        git("apply", "--3way", str(ROOT / "bratiki/custom.patch"), cwd=tree)
        require(not git("ls-files", "-u", cwd=tree), "Three-way patch left conflicts")
        changed = git("diff", "--name-only", upstream_sha, cwd=tree).splitlines()
        require(all(not protected(name) for name in changed), "Custom patch touches protected override paths")
        # Pin source metadata from pristine upstream, never from a patch-modified properties file.
        require(metadata(item, tree, upstream_sha) == updated, "Custom patch altered upstream version/core metadata")
        patch = git("diff", "--binary", "--full-index", upstream_sha, "--", ".", ":(exclude)bratiki/**",
                    ":(exclude).github/workflows/**", ":(exclude).github/actions/**",
                    ":(exclude)README.md", ":(exclude).gitignore", cwd=tree, binary=True)
        require(patch, "Custom patch disappeared during migration")
        # Remove every upstream workflow, then restore only the reviewed Bratiki workflow set.
        shutil.rmtree(tree / ".github/workflows", ignore_errors=True)
        shutil.rmtree(tree / "bratiki", ignore_errors=True)
        for name in preserved:
            destination = tree / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, destination)
        (tree / "bratiki/custom.patch").write_bytes(patch)
        (tree / "bratiki/upstream.json").write_text(json.dumps(updated, indent=2) + "\n", encoding="utf-8")
        git("add", "-A", cwd=tree)
        require(not git("ls-files", "-u", cwd=tree), "Unmerged files remain")
        tree_sha = git("write-tree", cwd=tree)
        message = f'Prepare reviewed source migration to {item["tag_name"]}'
        commit = git("commit-tree", tree_sha, "-p", main_sha, "-p", upstream_sha, "-m", message, cwd=tree)
        git("update-ref", "refs/heads/" + branch, commit, cwd=tree)
        if not publish:
            # A bundle is reviewable/applicable locally; no local branch or checkout is changed.
            output = ROOT / (branch.replace("/", "-") + ".bundle")
            git("bundle", "create", str(output), "refs/heads/" + branch, cwd=tree)
            print(f'Prepared review bundle: {output}')
            return
        env = dict(os.environ)
        token = base64.b64encode(("x-access-token:" + env["GH_TOKEN"]).encode()).decode()
        env.update({"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
                    "GIT_CONFIG_VALUE_0": "AUTHORIZATION: basic " + token})
        # No force push: an existing branch must be inspected, never silently replaced.
        git("push", f"https://github.com/{FORK}.git", f"refs/heads/{branch}:refs/heads/{branch}", cwd=tree, env=env)
        body = (f'Upstream release: {item["html_url"]}\n\n'
                f'Upstream source commit: `{upstream_sha}`. The complete custom patch applied with `git apply --3way`; '
                'the patch has been regenerated against this upstream source. Bratiki workflows, scripts, metadata, '
                'README and .gitignore overrides are preserved. No upstream code was executed during preparation.\n\n'
                'Review the full source delta, including protocol/core dependencies and updater identity, then run an '
                'unsigned build. Signing and publication remain a separate manually approved workflow; a textual merge '
                'alone does not establish compatibility. No APK has been published by this PR.')
        subprocess.run(["gh", "pr", "create", "--repo", FORK, "--head", branch, "--base", "main",
                        "--title", message, "--body", body, "--draft"], cwd=tree, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "prepare-update"))
    parser.add_argument("--tag", help="Stable rawline-X.Y.Z tag (default: latest stable)")
    parser.add_argument("--notify", action="store_true", help="Create a deduplicated issue when needed")
    parser.add_argument("--publish", action="store_true", help="Push source-sync branch and create review PR")
    args = parser.parse_args()
    item = release(args.tag)
    if args.command == "check":
        different = item["tag_name"] != pin()["tag"]
        print(json.dumps({"pinned": pin()["tag"], "latest": item["tag_name"], "updateAvailable": different}))
        if different and args.notify:
            notify(item)
    else:
        try:
            prepare(item, args.publish)
        except Exception:
            if args.notify:
                notify(item, failure=True)
            raise


if __name__ == "__main__":
    main()
