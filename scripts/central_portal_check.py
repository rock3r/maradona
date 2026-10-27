#!/usr/bin/env python3
"""Validate and promote Maradona Maven Central Portal deployments.

Follows the Spectre Central Portal workflow:
- Reads credentials from `MAVEN_CENTRAL_BEARER_TOKEN`, `MAVEN_CENTRAL_USERNAME` /
  `MAVEN_CENTRAL_PASSWORD`, `ORG_GRADLE_PROJECT_mavenCentralUsername` /
  `ORG_GRADLE_PROJECT_mavenCentralPassword`, or the 1Password item
  `"Maradona Maven Central Portal"` (falling back to `"Spectre Maven Central Portal"`).
- Never prints secrets.
- Supports `status`, `files`, `validate`, and `publish` (with interactive confirmation).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

DEFAULT_ITEMS = ("Maradona Maven Central Portal", "Spectre Maven Central Portal")
DEFAULT_BASE_URL = "https://central.sonatype.com/api/v1/publisher"
GROUP_ID = "dev.sebastiano.maradona"
GROUP_PATH = "dev/sebastiano/maradona"

# Each entry maps component artifactId -> tuple of artifact suffixes published for that component.
COMPONENT_SUFFIXES: dict[str, tuple[str, ...]] = {
    "maradona": (
        ".jar",
        "-sources.jar",
        "-javadoc.jar",
        "-kotlin-tooling-metadata.json",
        ".pom",
        ".module",
    ),
    "maradona-desktop": (
        ".jar",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
    "maradona-android": (
        ".aar",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
    "maradona-iosarm64": (
        ".klib",
        "-metadata.jar",
        "-kotlin_resources.kotlin_resources.zip",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
    "maradona-iossimulatorarm64": (
        ".klib",
        "-metadata.jar",
        "-kotlin_resources.kotlin_resources.zip",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
    "maradona-material3": (
        ".jar",
        "-sources.jar",
        "-javadoc.jar",
        "-kotlin-tooling-metadata.json",
        ".pom",
        ".module",
    ),
    "maradona-material3-desktop": (
        ".jar",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
    "maradona-material3-android": (
        ".aar",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
    "maradona-material3-iosarm64": (
        ".klib",
        "-metadata.jar",
        "-kotlin_resources.kotlin_resources.zip",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
    "maradona-material3-iossimulatorarm64": (
        ".klib",
        "-metadata.jar",
        "-kotlin_resources.kotlin_resources.zip",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
    "maradona-jewel": (
        ".jar",
        "-sources.jar",
        "-javadoc.jar",
        "-kotlin-tooling-metadata.json",
        ".pom",
        ".module",
    ),
    "maradona-jewel-desktop": (
        ".jar",
        "-sources.jar",
        "-javadoc.jar",
        ".pom",
        ".module",
    ),
}

REQUIRED_CHECKSUM_ALGORITHMS = ("md5", "sha1")
OPTIONAL_CHECKSUM_ALGORITHMS = ("sha256", "sha512")
ALL_CHECKSUM_ALGORITHMS = REQUIRED_CHECKSUM_ALGORITHMS + OPTIONAL_CHECKSUM_ALGORITHMS

REQUIRED_MAIN_JAR_ENTRIES: dict[str, tuple[str, ...]] = {
    "maradona-desktop": (
        "dev/maradona/render/MaradonaDiagramKt.class",
        "dev/maradona/parse/MaradonaParser.class",
        "dev/maradona/export/MaradonaSvgExporter.class",
        "META-INF/LICENSE",
        "META-INF/NOTICE",
    ),
    "maradona-material3-desktop": (
        "dev/maradona/theme/material3/Material3MaradonaThemeKt.class",
        "META-INF/LICENSE",
        "META-INF/NOTICE",
    ),
    "maradona-jewel-desktop": (
        "dev/maradona/theme/jewel/JewelMaradonaThemeKt.class",
        "META-INF/LICENSE",
        "META-INF/NOTICE",
    ),
}


@dataclass(frozen=True)
class FileCheck:
    path: str
    size: int


class CentralPortalError(RuntimeError):
    """Raised when Central Portal validation cannot continue."""


def expected_payload_files_for_version(version: str) -> list[str]:
    files: list[str] = []
    for component, suffixes in COMPONENT_SUFFIXES.items():
        base = f"{component}-{version}"
        prefix = f"{GROUP_PATH}/{component}/{version}/"
        for suffix in suffixes:
            files.append(f"{prefix}{base}{suffix}")
    return files


def expected_required_files_for_version(version: str) -> list[str]:
    """Files always uploaded by com.vanniktech.maven.publish 0.37+ (payload, .asc, and payload .md5/.sha1)."""
    files: list[str] = []
    for relative_path in expected_payload_files_for_version(version):
        files.append(relative_path)
        files.append(f"{relative_path}.asc")
        for algorithm in REQUIRED_CHECKSUM_ALGORITHMS:
            files.append(f"{relative_path}.{algorithm}")
    return files


def expected_optional_files_for_version(version: str) -> list[str]:
    """Additional checksums (.sha256/.sha512 and .asc.* checksums) that Central Portal may add server-side."""
    files: list[str] = []
    for relative_path in expected_payload_files_for_version(version):
        for algorithm in OPTIONAL_CHECKSUM_ALGORITHMS:
            files.append(f"{relative_path}.{algorithm}")
        for algorithm in ALL_CHECKSUM_ALGORITHMS:
            files.append(f"{relative_path}.asc.{algorithm}")
    return files


def expected_files_for_version(version: str) -> list[str]:
    return expected_required_files_for_version(version)


def field_map(item: dict) -> dict[str, str]:
    fields: dict[str, str] = {}
    for field in item.get("fields", []):
        label = str(field.get("label") or field.get("id") or "").strip().lower()
        value = field.get("value")
        if label and value:
            fields[label] = str(value).strip()
    return fields


def bearer_token_from_item(item: dict) -> str:
    fields = field_map(item)
    username = fields.get("username")
    password = fields.get("password")
    if username and password:
        raw = f"{username}:{password}".encode("utf-8")
        return base64.b64encode(raw).decode("ascii")

    for key in (
        "username:password (base64)",
        "username:password base64",
        "base64",
        "bearer",
        "token",
    ):
        value = fields.get(key)
        if value:
            return value
    raise CentralPortalError(
        "1Password item must contain either username/password fields or a "
        "'username:password (base64)' field."
    )


def read_one_password_item(item_names: tuple[str, ...]) -> dict:
    if shutil.which("op") is None:
        raise CentralPortalError("1Password CLI `op` is not installed or not on PATH.")
    last_err = ""
    for name in item_names:
        result = subprocess.run(
            ["op", "item", "get", name, "--format", "json"],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        if result.returncode == 0:
            return json.loads(result.stdout)
        last_err = result.stderr.strip()
    raise CentralPortalError(
        f"Could not read 1Password item {item_names!r}. Is `op` signed in?\n{last_err}"
    )


def bearer_token_from_environment() -> str | None:
    for key in ("MAVEN_CENTRAL_BEARER_TOKEN", "CENTRAL_PORTAL_BEARER_TOKEN"):
        value = os.environ.get(key)
        if value:
            return value.strip()

    username = os.environ.get("MAVEN_CENTRAL_USERNAME") or os.environ.get(
        "ORG_GRADLE_PROJECT_mavenCentralUsername"
    )
    password = os.environ.get("MAVEN_CENTRAL_PASSWORD") or os.environ.get(
        "ORG_GRADLE_PROJECT_mavenCentralPassword"
    )
    if username and password:
        raw = f"{username}:{password}".encode("utf-8")
        return base64.b64encode(raw).decode("ascii")
    return None


def load_bearer_token(item_name: str | None) -> str:
    env_token = bearer_token_from_environment()
    if env_token:
        return env_token
    candidates = (item_name,) if item_name else DEFAULT_ITEMS
    return bearer_token_from_item(read_one_password_item(candidates))


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def request(
    url: str,
    token: str,
    *,
    method: str = "GET",
    body: bytes | None = None,
    content_type: str | None = None,
    output: Path | None = None,
) -> tuple[int, bytes]:
    headers = auth_headers(token)
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, method=method, headers=headers, data=body)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            size_header = response.headers.get("Content-Length")
            if output is None:
                resp_body = response.read()
            else:
                with output.open("wb") as sink:
                    shutil.copyfileobj(response, sink)
                resp_body = b""
            size = int(size_header) if size_header and size_header.isdigit() else len(resp_body)
            if output is not None and not size_header:
                size = output.stat().st_size
            return size, resp_body
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise CentralPortalError(f"{method} {url} failed with HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise CentralPortalError(f"{method} {url} failed: {error.reason}") from error


def status(base_url: str, token: str, deployment_id: str) -> dict:
    url = f"{base_url}/status?id={deployment_id}"
    _, body = request(url, token, method="POST")
    return json.loads(body.decode("utf-8"))


def browse_deployment_files(base_url: str, token: str, deployment_id: str) -> dict[str, int]:
    files: dict[str, int] = {}
    page = 0
    page_count = 1
    while page < page_count:
        payload = {
            "page": page,
            "size": 1000,
            "sortField": "createdTimestamp",
            "sortDirection": "asc",
            "deploymentIds": [deployment_id],
            "pathStarting": f"{GROUP_PATH}/",
        }
        _, body = request(
            f"{base_url}/deployments/files",
            token,
            method="POST",
            body=json.dumps(payload).encode("utf-8"),
            content_type="application/json",
        )
        response = json.loads(body.decode("utf-8"))
        page_count = int(response.get("pageCount") or 1)
        for deployment in response.get("deployments") or []:
            for file_entry in deployment.get("deploymentFiles") or []:
                relative_path = file_entry.get("relativePath")
                size = file_entry.get("fileSize")
                if relative_path and size is not None:
                    files[str(relative_path)] = int(size)
        page += 1
    return files


def deployment_file_url(base_url: str, deployment_id: str, relative_path: str) -> str:
    return f"{base_url}/deployment/{deployment_id}/download/{relative_path}"


def download_to_path(
    base_url: str,
    token: str,
    deployment_id: str,
    relative_path: str,
    root: Path,
) -> FileCheck:
    destination = root / relative_path
    destination.parent.mkdir(parents=True, exist_ok=True)
    url = deployment_file_url(base_url, deployment_id, relative_path)
    size, _ = request(url, token, method="GET", output=destination)
    return FileCheck(relative_path, size)


def digest_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_checksum(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip().split()[0].lower()


def validate_jar(component: str, jar_path: Path) -> list[str]:
    errors: list[str] = []
    with zipfile.ZipFile(jar_path) as jar:
        entries = {entry.filename: entry.file_size for entry in jar.infolist()}
        for required_entry in REQUIRED_MAIN_JAR_ENTRIES.get(component, ()):
            if required_entry not in entries:
                errors.append(f"{component} is missing required entry {required_entry}")
            elif entries[required_entry] <= 0:
                errors.append(f"{component} required entry {required_entry} is empty")
        if component.endswith("-desktop"):
            majors: set[int] = set()
            for name in jar.namelist():
                if name.endswith(".class"):
                    data = jar.read(name)
                    if len(data) >= 8 and data[:4] == b"\xca\xfe\xba\xbe":
                        _, major = struct.unpack(">HH", data[4:8])
                        majors.add(major)
            if majors != {69}:
                errors.append(
                    f"{component} expected Java 25 classfile major version {{69}}, got {sorted(majors)}"
                )
    return errors


def validate_aar(component: str, aar_path: Path) -> list[str]:
    errors: list[str] = []
    with zipfile.ZipFile(aar_path) as aar:
        names = set(aar.namelist())
        if "AndroidManifest.xml" not in names:
            errors.append(f"{component} is missing AndroidManifest.xml")
        else:
            manifest_xml = aar.read("AndroidManifest.xml").decode("utf-8", errors="replace")
            if 'android:minSdkVersion="24"' not in manifest_xml:
                errors.append(
                    f'{component} AndroidManifest.xml must declare android:minSdkVersion="24"'
                )
        if "classes.jar" not in names:
            errors.append(f"{component} is missing classes.jar")
        else:
            majors: set[int] = set()
            with zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as inner:
                for name in inner.namelist():
                    if name.endswith(".class"):
                        data = inner.read(name)
                        if len(data) >= 8 and data[:4] == b"\xca\xfe\xba\xbe":
                            _, major = struct.unpack(">HH", data[4:8])
                            majors.add(major)
            if majors != {69}:
                errors.append(
                    f"{component}!classes.jar expected Java 25 classfile major version {{69}}, got {sorted(majors)}"
                )
    return errors


def validate_deployment(base_url: str, token: str, deployment_id: str, version: str) -> int:
    deployment = status(base_url, token, deployment_id)
    errors: list[str] = []
    state = deployment.get("deploymentState")
    if state not in {"VALIDATED", "PUBLISHING", "PUBLISHED"}:
        errors.append(f"deployment state is {state!r}, expected VALIDATED/PUBLISHING/PUBLISHED")

    purls = set(deployment.get("purls") or [])
    expected_purls = {f"pkg:maven/{GROUP_ID}/{comp}@{version}" for comp in COMPONENT_SUFFIXES}
    missing_purls = sorted(expected_purls - purls)
    if missing_purls:
        errors.append("missing expected components: " + ", ".join(missing_purls))

    print(f"Deployment {deployment_id}: {deployment.get('deploymentName')} [{state}]")
    print()

    remote_files = browse_deployment_files(base_url, token, deployment_id)
    required_file_set = set(expected_required_files_for_version(version))
    optional_file_set = set(expected_optional_files_for_version(version))
    allowed_file_set = required_file_set | optional_file_set
    remote_file_set = set(remote_files)
    missing_files = sorted(required_file_set - remote_file_set)
    unexpected_files = sorted(remote_file_set - allowed_file_set)
    if missing_files:
        errors.append("missing expected files: " + ", ".join(missing_files))
    if unexpected_files:
        errors.append("unexpected files in deployment: " + ", ".join(unexpected_files))

    checked: list[FileCheck] = []
    files_to_download = [
        f
        for f in (expected_required_files_for_version(version) + expected_optional_files_for_version(version))
        if f in remote_file_set or f in required_file_set
    ]
    total_files = len(files_to_download)
    with tempfile.TemporaryDirectory(prefix="maradona-central-") as tmp:
        tmp_path = Path(tmp)
        for index, relative_path in enumerate(files_to_download, start=1):
            print(f"[{index:03d}/{total_files}] {relative_path}", flush=True)
            file_check = download_to_path(base_url, token, deployment_id, relative_path, tmp_path)
            listed_size = remote_files.get(relative_path)
            if listed_size is not None and listed_size != file_check.size:
                errors.append(
                    f"{relative_path} downloaded as {file_check.size} bytes, "
                    f"but Central file listing reports {listed_size} bytes"
                )
            checked.append(file_check)

        for relative_path in expected_payload_files_for_version(version):
            payload = tmp_path / relative_path
            signature = tmp_path / f"{relative_path}.asc"
            for algorithm in REQUIRED_CHECKSUM_ALGORITHMS:
                checksum_path = tmp_path / f"{relative_path}.{algorithm}"
                actual = digest_file(payload, algorithm)
                expected = read_checksum(checksum_path)
                if actual != expected:
                    errors.append(
                        f"{checksum_path.relative_to(tmp_path)} is {expected}, "
                        f"but {payload.relative_to(tmp_path)} hashes to {actual}"
                    )
            for signed_path in (payload, signature):
                for algorithm in ALL_CHECKSUM_ALGORITHMS:
                    rel_chk = f"{signed_path.relative_to(tmp_path)}.{algorithm}"
                    checksum_path = tmp_path / rel_chk
                    if not checksum_path.is_file():
                        continue
                    actual = digest_file(signed_path, algorithm)
                    expected = read_checksum(checksum_path)
                    if actual != expected:
                        errors.append(
                            f"{rel_chk} is {expected}, "
                            f"but {signed_path.relative_to(tmp_path)} hashes to {actual}"
                        )

            component = Path(relative_path).parent.parent.name
            if relative_path.endswith(".pom") and not component.startswith("maradona-jewel"):
                pom_text = payload.read_text(encoding="utf-8", errors="replace")
                if "org.jetbrains.jewel" in pom_text:
                    errors.append(f"{component} POM must not depend on org.jetbrains.jewel")

            if relative_path.endswith(".jar") and not relative_path.endswith(
                ("-sources.jar", "-javadoc.jar", "-metadata.jar")
            ):
                errors.extend(validate_jar(component, payload))
            elif relative_path.endswith(".aar"):
                errors.extend(validate_aar(component, payload))

    for file_check in checked:
        print(f"{file_check.size:>10}  {file_check.path}")

    if errors:
        print("\nValidation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(f"\nValidated {len(checked)} deployment files for Maradona {version}.")
    return 0


def print_status(base_url: str, token: str, deployment_id: str) -> int:
    deployment = status(base_url, token, deployment_id)
    print(json.dumps(deployment, indent=2, sort_keys=True))
    return 0


def print_files(base_url: str, token: str, deployment_id: str, version: str) -> int:
    remote_files = browse_deployment_files(base_url, token, deployment_id)
    for relative_path, size in sorted(remote_files.items()):
        print(f"{size:>10}  {relative_path}")

    required_file_set = set(expected_required_files_for_version(version))
    optional_file_set = set(expected_optional_files_for_version(version))
    remote_file_set = set(remote_files)
    missing_files = sorted(required_file_set - remote_file_set)
    unexpected_files = sorted(remote_file_set - (required_file_set | optional_file_set))
    if missing_files or unexpected_files:
        print("\nFile listing differs from expected Maradona release shape:", file=sys.stderr)
        for relative_path in missing_files:
            print(f"  - missing: {relative_path}", file=sys.stderr)
        for relative_path in unexpected_files:
            print(f"  - unexpected: {relative_path}", file=sys.stderr)
        return 1

    print(f"\n{len(remote_files)} files listed by Central.")
    return 0


def publish_deployment(base_url: str, token: str, deployment_id: str, version: str, yes: bool) -> int:
    expected = f"publish {deployment_id} {version}"
    if not yes:
        print("This permanently publishes the validated Central Portal deployment.")
        print(f"Type exactly: {expected}")
        typed = input("> ").strip()
        if typed != expected:
            print("Confirmation did not match; not publishing.")
            return 2
    url = f"{base_url}/deployment/{deployment_id}"
    request(url, token, method="POST")
    print(f"Publish requested for deployment {deployment_id}.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--item", default=None, help="1Password item name override")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="Central Publisher API base URL")
    subparsers = parser.add_subparsers(dest="command", required=True)

    for name in ("status", "files", "validate", "publish"):
        command = subparsers.add_parser(name)
        command.add_argument("--deployment-id", required=True)
        if name in {"files", "validate", "publish"}:
            command.add_argument("--version", required=True)
        if name == "publish":
            command.add_argument(
                "--yes",
                action="store_true",
                help="Skip typed confirmation. Intended for carefully reviewed automation only.",
            )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        token = load_bearer_token(args.item)
        if args.command == "status":
            return print_status(args.base_url, token, args.deployment_id)
        if args.command == "files":
            return print_files(args.base_url, token, args.deployment_id, args.version)
        if args.command == "validate":
            return validate_deployment(args.base_url, token, args.deployment_id, args.version)
        if args.command == "publish":
            return publish_deployment(
                args.base_url,
                token,
                args.deployment_id,
                args.version,
                args.yes,
            )
    except CentralPortalError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 1


if __name__ == "__main__":
    sys.exit(main())
