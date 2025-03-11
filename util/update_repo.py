#!/usr/bin/python3
""" Tool to update the Solr Plugin Repository at https://dbmdz.github.io/solr.

Needs the following secrets passed via environment variables:
- GH_DEPLOY_TOKEN: Token with permissions to push to the repository at github.com:dbmdz/dbmdz.github.io.git
- CERTIFICATE: PEM-encoded private key for signing artifacts in the repository, corresponding to the
  repository's public key.
"""
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
from typing import Any, Iterable, List, Tuple, TypedDict
from urllib import request

# Standard SemVer with an optional patch component
VERSION_PAT = re.compile(r"^\d+\.\d+(?:\.\d+)?$")

# These constraints define the minimum and maximum version of Solr
# that is compatible with a certain plugin version.
# Due to our use of internal Solr and Lucene APIs, every plugin version
# is backwards-compatible as much as possible, but not forward-compatible
# to new Solr releases.
# Each constraint is a tuple of
# (<plugin-version>, (<min-solr-version>, <max-solr-version>))
# Min/Max are both inclusive, so ("9.0", "9.6") is compatible with all
# Solr versions >= 9.0 && <= 9.6.
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

# The release from which our two release trains diverge
# Solr 9.0 introduced changes that make the bytecode between our
# Solr 9.x and < 9.x releases incompatible, which is why we have
# a split release train from 0.8.0 on, the plugin release that
# introduced Solr 9.0 compatibility.
SPLIT_START_VERSION = (0, 8, 0)

REPOSITORY_NAME = "ocrhighlighting"
REPOSITORY_DESCRIPTION = "Highlight various OCR formats directly in Solr."
REPOSITORY_GIT_REPO = "github.com/dbmdz/dbmdz.github.io.git"
RELEASES_URL = "https://api.github.com/repos/dbmdz/solr-ocrhighlighting/releases"
RELEASE_DOWNLOAD_URL = "https://github.com/dbmdz/solr-ocrhighlighting/releases/download"


class Artifact(TypedDict):
    #: Where the release artifact is hosted
    url: str
    #: Signature for the release artifact
    sig: str



Manifest = TypedDict('Manifest', {
    #: A SemVer version constraint, see this for examples of valid syntax:
    #: https://github.com/semver4j/semver4j/blob/1bfc2b975/src/test/java/org/semver4j/SemverTest.java#L904-L1174
    'version-constraint': str
})


class Version(TypedDict):
    """
    Specific release version for a Solr plugin package
    """
    #: SemVer version
    version: str
    #: Release date in YYYY-MM-dd format
    date: str
    artifacts: List[Artifact]
    manifest: Manifest


class Plugin(TypedDict):
    """
    Solr plugin package
    """
    name: str
    description: str
    versions: List[Version]


class Asset(TypedDict):
    """
    GitHub Release asset
    """
    browser_download_url: str
    name: str


def fetch_releases() -> List[Any]:
    """ Fetch releases from GitHub """
    req = request.Request(RELEASES_URL)
    req.add_header("Accept", "application/vnd.github.v3+json")
    with request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))


def build_repository(build_v78: bool = False) -> List[Plugin]:
    """
    Build the repository data structure based on the available
    GitHub releases.

    :param build_v78: Whether to build a repository for the Solr 7.x/8.x
                      release train
    """
    all_releases = fetch_releases()
    return [
        {
            "name": REPOSITORY_NAME,
            "description": REPOSITORY_DESCRIPTION,
            "versions": list(
                itertools.chain.from_iterable(
                    build_versions(
                        r["tag_name"],
                        datetime.fromisoformat(r["published_at"][:-1]),
                        r["assets"],
                        build_v78,
                    )
                    for r in all_releases
                )
            ),
        }
    ]


def build_versions(
    tag_name: str, publish_date: datetime, assets: List[Asset], build_v78: bool = False
) -> Iterable[Version]:
    """ Build the Version data structures for a specific release.

    :param tag_name:        GitHub release tag
    :param publish_date:    Date the release was published
    :param assets:          Assets associated with the release
    :param build_v78:       Whether the release is for the 7.x/8.x release
                            train
    """
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
            if VERSION_PAT.match(p)
        )
        version = tuple(int(x) for x in version_str.split("."))
        all_constraints = (
            VERSION_CONSTRAINTS_9 if version >= (0, 8, 0) else VERSION_CONSTRAINTS_78
        )
        if len(version) == 2:
            # Force semantic versioning
            version = version + (0,)
            version_str = ".".join(str(p) for p in version)
        if tag_name == "wip":
            version_str = f'{version_str}-pre{publish_date.strftime("%Y%m%d%H%M%S")}'
        if is_v78:
            version_str = f"{version_str}-solr78"
            all_constraints = VERSION_CONSTRAINTS_78
        constraint = next(
            c for min_vers, c in reversed(all_constraints) if version >= min_vers
        )

        asset_url = asset["browser_download_url"]
        try:
            yield dict(
                version=version_str,
                date=publish_date.strftime("%Y-%m-%d"),
                artifacts=[dict(url=asset_url, sig=sign_artifact(asset_url))],
                manifest={"version-constraint": " - ".join(constraint)},
            )
        except Exception as e:
            print(f"Failed to build  version {version_str} from asset {asset_url}, skipping.", file=sys.stderr)
            print(f"Reason was: {e}", file=sys.stderr)


def sign_artifact(artifact_url: str) -> str:
    """ Calculate the signature for a given release artifact. """
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
    """ Dump a repository to a JSON file in a specific directory. """
    solr_repo_path.mkdir(parents=True, exist_ok=True)
    with (solr_repo_path / "repository.json").open("wt") as fp:
        json.dump(repository, fp, indent=2)

def git(cmd, *args, cwd: Path):
    """ Wrapper around the git CLI. """
    return subprocess.check_call(("git", cmd) + args, cwd=cwd)

def publish_repository(dry_run=False) -> None:
    """ Build 7.x/8.x and 9.x repositories and publish them to GitHub pages. 
    :param dry_run: Do not publish to GitHub, only print repository JSON to stdout
    """
    repository = build_repository()
    repository_v78 = build_repository(build_v78=True)

    if dry_run:
        print(json.dumps(repository, indent=2))
        print(json.dumps(repository_v78, indent=2))
        return

    git_repo_path = Path(tempfile.mkdtemp())
    github_token = os.environ["GH_DEPLOY_TOKEN"]
    repo_url = f"https://{github_token}@{REPOSITORY_GIT_REPO}"
    subprocess.check_call(("git", "clone", "-q", repo_url, git_repo_path))

    add_solr_repository(git_repo_path / "solr", repository)
    add_solr_repository(git_repo_path / "solr78", repository_v78)

    was_modified = (
        len(subprocess.check_output(("git", "ls-files", "-mo"), cwd=git_repo_path)) > 0
    )
    if was_modified:
        git("add", "solr/repository.json", cwd=git_repo_path)
        git("add", "solr78/repository.json", cwd=git_repo_path)
        git("commit", "-q", "-m", "Update Solr repositories", cwd=git_repo_path)
        git("push", "-q", "-u", "origin", "main", cwd=git_repo_path)

    shutil.rmtree(git_repo_path)




if __name__ == "__main__":
    if "--help" in sys.argv:
        print(
            f"update_repo.py [--dry-run]\n\n"
            f"Required environment variables if not using --dry-run:\n"
            f"- GH_DEPLOY_TOKEN: GitHub Personal Access Token with permissions for {REPOSITORY_GIT_REPO}\n"
            f"- CERTIFICATE: PEM-encoded private key  corresponding to public key in {REPOSITORY_GIT_REPO}/solr/publickey.der\n"
        )
        sys.exit(0)
    dry_run = "--dry-run" in sys.argv
    if not dry_run and "GH_DEPLOY_TOKEN" not in os.environ:
        print(
            f"Please provide a GitHub Personal Access Token with permissions for "
            f"{REPOSITORY_GIT_REPO} via the GH_DEPLOY_TOKEN environment variable."
        )
        sys.exit(1)
    if "CERTIFICATE" not in os.environ:
        print(
            f"Please provide the PEM-encoded private corresponding to the public key in "
            f"{REPOSITORY_GIT_REPO}/solr/publickey.der via the CERTIFICATE environment variable."
        )
        sys.exit(1)
    publish_repository(dry_run)
