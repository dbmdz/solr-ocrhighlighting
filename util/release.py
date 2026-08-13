#!/usr/bin/env python3
"""Release automation helpers for CI and manual maintenance."""

from __future__ import annotations

import argparse
import base64
import itertools
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, List, TypedDict
from urllib import request


SEMVER_RE = re.compile(r"^(\d+)\.(\d+)(?:\.(\d+))?$")
PROJECT_VERSION_RE = re.compile(
    r"(<artifactId>solr-ocrhighlighting</artifactId>\s*<version>)([^<]+)(</version>)",
    re.MULTILINE,
)

VERSION_CONSTRAINTS_78 = [
    ((0, 1, 0), ("7.5", "8.0")),
    ((0, 3, 1), ("7.5", "8.2")),
    ((0, 4, 0), ("7.5", "8.8")),
    ((0, 7, 0), ("7.5", "8.11")),
]
VERSION_CONSTRAINTS_9 = [
    ((0, 8, 0), ("9.0", "9.3")),
    ((0, 8, 4), ("9.0", "9.4")),
    ((0, 8, 5), ("9.0", "9.6")),
    ((0, 9, 1), ("9.0", "9.8")),
]

REPOSITORY_NAME = "ocrhighlighting"
REPOSITORY_DESCRIPTION = "Highlight various OCR formats directly in Solr."
REPOSITORY_GIT_REPO = "github.com/dbmdz/dbmdz.github.io.git"
RELEASES_URL = "https://api.github.com/repos/dbmdz/solr-ocrhighlighting/releases"


class Artifact(TypedDict, total=False):
    url: str
    sig: str


Manifest = TypedDict(
    "Manifest",
    {
        "version-constraint": str,
    },
)


class Version(TypedDict):
    version: str
    date: str
    artifacts: List[Artifact]
    manifest: Manifest


class Plugin(TypedDict):
    name: str
    description: str
    versions: List[Version]


class Asset(TypedDict):
    browser_download_url: str
    name: str


def parse_semver(version: str, require_patch: bool = False) -> tuple[int, int, int]:
    """Parse a semantic version string into integer parts.

    When ``require_patch`` is false, versions like ``0.3`` are normalized to
    ``(0, 3, 0)``.
    """
    match = SEMVER_RE.match(version)
    if match is None:
        raise ValueError(f"Unsupported release version: {version}")

    major = int(match.group(1))
    minor = int(match.group(2))
    patch = match.group(3)
    if patch is None:
        if require_patch:
            raise ValueError(f"Unsupported release version: {version}")
        return (major, minor, 0)
    return (major, minor, int(patch))


def format_semver(version: tuple[int, int, int]) -> str:
    """Format a semantic version tuple as ``major.minor.patch``."""
    return ".".join(str(part) for part in version)


def version_map(repository: Plugin | None) -> dict[str, Version]:
    """Index repository entries by version string."""
    if repository is None:
        return {}
    return {entry["version"]: entry for entry in repository["versions"]}


def extract_entry(changelog_path: Path, version: str) -> str:
    """Extract the changelog body for one released version."""
    content = changelog_path.read_text(encoding="utf-8")
    pattern = re.compile(
        rf"^## {re.escape(version)}(?: \([^)]+\))?\n(?P<body>.*?)(?=^## |\Z)",
        re.MULTILINE | re.DOTALL,
    )
    match = pattern.search(content)
    if match is None:
        raise ValueError(f"Could not find changelog entry for version {version}.")

    lines = match.group("body").strip().splitlines()
    if lines and re.match(r"^\[GitHub Release\]\(", lines[0]):
        lines = lines[1:]
    return "\n".join(lines).strip() + "\n"


def read_project_version(pom_path: Path) -> str:
    """Read the top-level project version from ``pom.xml``."""
    content = pom_path.read_text(encoding="utf-8")
    match = PROJECT_VERSION_RE.search(content)
    if match is None:
        raise ValueError("Could not find project version in pom.xml.")
    return match.group(2).strip()


def validate_release(tag: str) -> None:
    """Validate that the tagged commit is publishable as a release."""
    version = read_project_version(Path("pom.xml"))
    if version.endswith("-SNAPSHOT"):
        raise ValueError(f"pom.xml has SNAPSHOT version {version}, refusing release tag {tag}.")
    if version != tag:
        raise ValueError(f"pom.xml version {version} does not match release tag {tag}.")
    extract_entry(Path("docs/changes.md"), tag)


def next_minor_snapshot(version: str) -> str:
    """Compute the next minor snapshot version for a release version."""
    major, minor, _patch = parse_semver(version, require_patch=True)
    return f"{major}.{minor + 1}.0-SNAPSHOT"


def bump_pom(pom_path: Path, release_version: str) -> bool:
    """Bump ``pom.xml`` to the next minor snapshot if it matches the release."""
    pom = pom_path.read_text(encoding="utf-8")
    match = PROJECT_VERSION_RE.search(pom)
    if match is None:
        raise ValueError("Could not find project version in pom.xml")

    current_version = match.group(2).strip()
    if current_version != release_version:
        print(
            f"Skipping bump: pom.xml is at {current_version}, expected {release_version}.",
            file=sys.stderr,
        )
        return False

    updated = PROJECT_VERSION_RE.sub(
        rf"\g<1>{next_minor_snapshot(release_version)}\g<3>",
        pom,
        count=1,
    )
    pom_path.write_text(updated, encoding="utf-8")
    return True


def fetch_releases() -> List[Any]:
    """Fetch non-draft GitHub releases for this repository."""
    req = request.Request(RELEASES_URL)
    req.add_header("Accept", "application/vnd.github.v3+json")
    with request.urlopen(req) as resp:
        releases = json.loads(resp.read().decode("utf-8"))
    return [r for r in releases if not r.get("draft")]


def build_repository(
    build_v78: bool = False,
    sign_artifacts: bool = True,
    existing_repository: Plugin | None = None,
) -> List[Plugin]:
    """Build Solr plugin repository metadata from published GitHub releases.

    When ``existing_repository`` is given, matching versions are reused as-is so
    unchanged historical entries are not re-signed.
    """
    all_releases = fetch_releases()
    existing_versions = version_map(existing_repository)
    built_versions = list(
        itertools.chain.from_iterable(
            build_versions(
                r["tag_name"],
                datetime.fromisoformat(r["published_at"][:-1]),
                r["assets"],
                build_v78,
                sign_artifacts,
                existing_versions,
            )
            for r in all_releases
        )
    )
    built_names = {entry["version"] for entry in built_versions}
    if existing_repository is not None:
        built_versions.extend(
            entry for entry in existing_repository["versions"] if entry["version"] not in built_names
        )
    return [
        {
            "name": REPOSITORY_NAME,
            "description": REPOSITORY_DESCRIPTION,
            "versions": built_versions,
        }
    ]


def build_versions(
    tag_name: str,
    publish_date: datetime,
    assets: List[Asset],
    build_v78: bool = False,
    sign_artifacts: bool = True,
    existing_versions: dict[str, Version] | None = None,
) -> Iterable[Version]:
    """Build repository version entries for one GitHub release payload."""
    relevant_assets = [
        a
        for a in assets
        if a["name"].endswith(".jar")
        and not any(a["name"].endswith(x) for x in ("-sources.jar", "-javadoc.jar"))
    ]
    for asset in relevant_assets:
        is_v78 = "-solr78" in asset["name"]
        if build_v78 != is_v78:
            continue
        version_str = next(
            p
            for p in asset["name"].replace(".jar", "").split("-")
            if SEMVER_RE.match(p)
        )
        version = parse_semver(version_str)
        all_constraints = VERSION_CONSTRAINTS_9 if version >= (0, 8, 0) else VERSION_CONSTRAINTS_78
        version_str = format_semver(version)
        if tag_name == "wip":
            version_str = f'{version_str}-pre{publish_date.strftime("%Y%m%d%H%M%S")}'
        if is_v78:
            version_str = f"{version_str}-solr78"
            all_constraints = VERSION_CONSTRAINTS_78
        if existing_versions is not None and version_str in existing_versions:
            yield existing_versions[version_str]
            continue
        constraint = next(c for min_vers, c in reversed(all_constraints) if version >= min_vers)

        asset_url = asset["browser_download_url"]
        try:
            artifact: Artifact = {"url": asset_url}
            if sign_artifacts:
                artifact["sig"] = sign_artifact(asset_url)
            yield {
                "version": version_str,
                "date": publish_date.strftime("%Y-%m-%d"),
                "artifacts": [artifact],
                "manifest": {"version-constraint": " - ".join(constraint)},
            }
        except Exception as exc:
            print(
                f"Failed to build version {version_str} from asset {asset_url}, skipping.",
                file=sys.stderr,
            )
            print(f"Reason was: {exc}", file=sys.stderr)


def sign_artifact(artifact_url: str) -> str:
    """Download and sign one release artifact URL."""
    with request.urlopen(artifact_url) as resp:
        artifact_data = resp.read()
    with tempfile.NamedTemporaryFile("wt") as key_path:
        key_path.write(os.environ["CERTIFICATE"])
        key_path.flush()
        signature = subprocess.check_output(
            ("openssl", "dgst", "-sha1", "-sign", key_path.name),
            input=artifact_data,
        )
        return base64.b64encode(signature).decode("utf-8")


def add_solr_repository(solr_repo_path: Path, repository: List[Plugin]) -> None:
    """Write one repository payload to ``repository.json``."""
    solr_repo_path.mkdir(parents=True, exist_ok=True)
    with (solr_repo_path / "repository.json").open("wt") as fp:
        json.dump(repository, fp, indent=2)


def load_solr_repository(solr_repo_path: Path) -> Plugin | None:
    """Load one existing repository payload if present."""
    repo_file = solr_repo_path / "repository.json"
    if not repo_file.exists():
        return None
    with repo_file.open("rt", encoding="utf-8") as fp:
        repository = json.load(fp)
    if not repository:
        return None
    return repository[0]


def git(cmd: str, *args: str, cwd: Path) -> int:
    """Run a git subcommand in a specific working tree."""
    return subprocess.check_call(("git", cmd) + args, cwd=cwd)


def publish_repository(dry_run: bool = False, rebuild_all: bool = False) -> None:
    """Build and optionally publish both plugin repository manifests.

    By default, existing repository entries are reused and only missing versions
    are built and signed. ``rebuild_all`` forces a full regeneration.
    """
    if dry_run:
        repository = build_repository(sign_artifacts=False)
        repository_v78 = build_repository(build_v78=True, sign_artifacts=False)
        print(json.dumps(repository, indent=2))
        print(json.dumps(repository_v78, indent=2))
        return

    git_repo_path = Path(tempfile.mkdtemp())
    github_token = os.environ["GH_DEPLOY_TOKEN"]
    repo_url = f"https://x-access-token:{github_token}@{REPOSITORY_GIT_REPO}"
    subprocess.check_call(("git", "clone", "-q", repo_url, git_repo_path))

    existing_repository = None if rebuild_all else load_solr_repository(git_repo_path / "solr")
    existing_repository_v78 = None if rebuild_all else load_solr_repository(git_repo_path / "solr78")
    repository = build_repository(sign_artifacts=True, existing_repository=existing_repository)
    repository_v78 = build_repository(
        build_v78=True,
        sign_artifacts=True,
        existing_repository=existing_repository_v78,
    )

    add_solr_repository(git_repo_path / "solr", repository)
    add_solr_repository(git_repo_path / "solr78", repository_v78)

    was_modified = len(subprocess.check_output(("git", "ls-files", "-mo"), cwd=git_repo_path)) > 0
    if was_modified:
        git("add", "solr/repository.json", cwd=git_repo_path)
        git("add", "solr78/repository.json", cwd=git_repo_path)
        git("commit", "-q", "-m", "Update Solr repositories", cwd=git_repo_path)
        git("push", "-q", "-u", "origin", "main", cwd=git_repo_path)

    shutil.rmtree(git_repo_path)


def update_repo_main(dry_run: bool, rebuild_all: bool) -> int:
    """CLI entrypoint for plugin repository publication."""
    if not dry_run and "GH_DEPLOY_TOKEN" not in os.environ:
        print(
            f"Please provide a GitHub Personal Access Token with permissions for "
            f"{REPOSITORY_GIT_REPO} via the GH_DEPLOY_TOKEN environment variable."
        )
        return 1
    if not dry_run and "CERTIFICATE" not in os.environ:
        print(
            f"Please provide the PEM-encoded private corresponding to the public key in "
            f"{REPOSITORY_GIT_REPO}/solr/publickey.der via the CERTIFICATE environment variable."
        )
        return 1

    publish_repository(dry_run, rebuild_all)
    return 0


def build_parser() -> argparse.ArgumentParser:
    """Build the command line parser for release automation subcommands."""
    parser = argparse.ArgumentParser(
        description="Release automation helpers for CI and manual maintenance.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    extract_parser = subparsers.add_parser(
        "extract-changelog",
        help="Extract the changelog body for a released version.",
        description="Extract the matching entry from docs/changes.md for one release version.",
    )
    extract_parser.add_argument("version", help="Released version to extract, for example 0.9.5.")

    validate_parser = subparsers.add_parser(
        "validate-release",
        help="Validate that a tag points at a publishable release commit.",
        description="Ensure pom.xml matches the release tag and docs/changes.md contains a matching entry.",
    )
    validate_parser.add_argument("tag", help="Release tag to validate, for example 0.9.5.")

    bump_parser = subparsers.add_parser(
        "bump-snapshot",
        help="Bump pom.xml to the next minor SNAPSHOT version.",
        description="Rewrite pom.xml from the released version to the next minor SNAPSHOT if it still matches.",
    )
    bump_parser.add_argument("release_version", help="Released version to bump from, for example 0.9.5.")

    repo_parser = subparsers.add_parser(
        "update-repo",
        help="Build or publish the Solr plugin repository metadata.",
        description=(
            "Build the repository metadata from GitHub releases. "
            "Without --dry-run this requires GH_DEPLOY_TOKEN and CERTIFICATE."
        ),
    )
    repo_parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the generated repository metadata instead of cloning and pushing the website repository.",
    )
    repo_parser.add_argument(
        "--rebuild-all",
        action="store_true",
        help="Rebuild and re-sign all repository entries instead of only adding missing versions.",
    )
    return parser


def main(argv: list[str]) -> int:
    """Dispatch subcommands for release automation tasks."""
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "extract-changelog":
            sys.stdout.write(extract_entry(Path("docs/changes.md"), args.version))
            return 0
        if args.command == "validate-release":
            validate_release(args.tag)
            return 0
        if args.command == "bump-snapshot":
            bump_pom(Path("pom.xml"), args.release_version)
            return 0
        if args.command == "update-repo":
            return update_repo_main(args.dry_run, args.rebuild_all)
        parser.error(f"Unknown command: {args.command}")
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
