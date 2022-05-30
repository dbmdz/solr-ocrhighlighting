#!/usr/bin/python3
""" Tool to update the Solr Plugin Repository at https://dbmdz.github.io/solr.

Needs the following secrets passed via environment variables:
- GH_DEPLOY_TOKEN: Token with permissions to push to the repository at github.com:dbmdz/dbmdz.github.io.git
- CERTIFICATE: PEM-encoded private key for signing artifacts in the repository, corresponding to the
  repository's public key.
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Any, List, TypedDict
from urllib import request

VERSION_CONSTRAINTS = [
    ((0, 1, 0), ("7.5", "8.0")),
    ((0, 3, 1), ("7.5", "8.2")),
    ((0, 4, 0), ("7.5", "8.8")),
    ((0, 7, 0), ("7.5", "8.11")),
]
REPOSITORY_NAME = "ocrhighlighting"
REPOSITORY_DESCRIPTION = "Highlight various OCR formats directly in Solr."
REPOSITORY_GIT_REPO = "github.com/dbmdz/dbmdz.github.io.git"
RELEASES_URL = "https://api.github.com/repos/dbmdz/solr-ocrhighlighting/releases"
RELEASE_DOWNLOAD_URL = "https://github.com/dbmdz/solr-ocrhighlighting/releases/download"


class Artifact(TypedDict):
    url: str
    sig: str


Manifest = TypedDict("Manifest", {"version-constraint": str})


class Version(TypedDict):
    version: str
    date: str
    artifacts: List[Artifact]
    manifest: Manifest


class Plugin(TypedDict):
    name: str
    description: str
    versions: List[Version]


def fetch_releases() -> List[Any]:
    req = request.Request(RELEASES_URL)
    req.add_header("Accept", "application/vnd.github.v3+json")
    resp = request.urlopen(req)
    return json.loads(resp.read().decode("utf-8"))


def build_repository() -> List[Plugin]:
    all_releases = fetch_releases()
    return [
        {
            "name": REPOSITORY_NAME,
            "description": REPOSITORY_DESCRIPTION,
            "versions": [
                build_version(
                    r["tag_name"], datetime.fromisoformat(r["published_at"][:-1])
                )
                for r in all_releases
            ],
        }
    ]


def build_version(version_str: str, date: datetime) -> Version:
    version = tuple(int(x) for x in version_str.split("."))
    artifact_url = (
        f"{RELEASE_DOWNLOAD_URL}/{version_str}/solr-ocrhighlighting-{version_str}.jar"
    )
    if len(version) == 2:
        # Force semantic versioning
        version = version + (0,)
        version_str = ".".join(str(p) for p in version)
    constraint = next(
        constraint
        for min_vers, constraint in reversed(VERSION_CONSTRAINTS)
        if version >= min_vers
    )
    artifact_signature = sign_artifact(artifact_url)
    return dict(
        version=version_str,
        date=date.strftime("%Y-%m-%d"),
        artifacts=[dict(url=artifact_url, sig=artifact_signature)],
        manifest={"version-constraint": " - ".join(constraint)},
    )


def sign_artifact(artifact_url: str) -> str:
    artifact_data = request.urlopen(artifact_url).read()
    with tempfile.NamedTemporaryFile("wt") as key_path:
        key_path.write(os.environ["CERTIFICATE"])
        key_path.flush()
        signature = subprocess.check_output(
            ("openssl", "dgst", "-sha1", "-sign", key_path.name),
            input=artifact_data,
        )
        return base64.b64encode(signature).decode("utf-8")


def publish_repository() -> None:
    repository = build_repository()
    git_repo_path = Path(tempfile.mkdtemp())
    github_token = os.environ["GH_DEPLOY_TOKEN"]
    repo_url = f"https://{github_token}@{REPOSITORY_GIT_REPO}"
    subprocess.check_call(("git", "clone", "-q", repo_url, git_repo_path))
    solr_repo_path = git_repo_path / "solr"
    if not solr_repo_path.exists():
        solr_repo_path.mkdir()
    with (solr_repo_path / "repository.json").open("wt") as fp:
        json.dump(repository, fp, indent=2)
    was_modified = (
        len(subprocess.check_output(("git", "ls-files", "-mo"), cwd=git_repo_path)) > 0
    )
    if was_modified:
        subprocess.check_call(("git", "add", "solr/repository.json"), cwd=git_repo_path)
        subprocess.check_call(
            (
                "git",
                "commit",
                "-q",
                "solr/repository.json",
                "-m",
                "Update solr/repository.json",
            ),
            cwd=git_repo_path,
        )
        subprocess.check_call(
            ("git", "push", "-q", "-u", "origin", "main"), cwd=git_repo_path
        )
    shutil.rmtree(git_repo_path)


if __name__ == "__main__":
    if "GH_DEPLOY_TOKEN" not in os.environ:
        print(
            f"Please provide a GitHub Personal Access Token with permissions for  "
            f"{REPOSITORY_GIT_REPO} via the GH_DEPLOY_TOKEN environment variable."
        )
        sys.exit(1)
    if "CERTIFICATE" not in os.environ:
        print(
            f"Please provide the PEM-encoded private corresponding to the public key in "
            f"{REPOSITORY_GIT_REPO}/solr/publickey.der via the CERTIFICATE environment variable."
        )
        sys.exit(1)
    publish_repository()
