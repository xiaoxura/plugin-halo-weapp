#!/usr/bin/env python3
"""Create a reproducible OSV inventory for Halo's plugin API and runtime JARs.

The plugin is intentionally a thin JAR. Its compile classpath and the actual Halo
runtime therefore have to be audited separately: Gradle proves what the plugin was
compiled against, while an extracted Halo runtime directory proves what the host
will load. Only Maven coordinates and public advisory metadata are sent to OSV.dev.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from typing import Any, Iterable
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
INIT_SCRIPT = Path(__file__).with_name("resolve-coordinates.gradle")
DEFAULT_API_VERSIONS = ("2.23.0", "2.25.0")
DEFAULT_OSV_URL = "https://api.osv.dev"
USER_AGENT = "plugin-halo-weapp-platform-audit/1"
SAFE_LABEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--api-version",
        action="append",
        dest="api_versions",
        help="Halo plugin API platform to resolve; repeatable (default: 2.23.0, 2.25.0)",
    )
    parser.add_argument(
        "--runtime-dir",
        action="append",
        default=[],
        metavar="LABEL=PATH",
        help="directory containing an extracted Halo runtime's JAR files; repeatable",
    )
    parser.add_argument(
        "--runtime-image",
        action="append",
        default=[],
        metavar="LABEL=IMAGE",
        help="Docker image whose /application runtime JARs should be inventoried; repeatable",
    )
    parser.add_argument(
        "--coordinate-hint",
        action="append",
        default=[],
        metavar="GROUP:ARTIFACT",
        help="group for a runtime JAR without Maven metadata; repeatable",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "build/reports/security/halo-platform-osv.json",
        help="JSON report path",
    )
    parser.add_argument("--osv-url", default=DEFAULT_OSV_URL)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--retries", type=int, default=4)
    parser.add_argument(
        "--fail-on-findings",
        action="store_true",
        help="write the report, then exit 1 when any audited inventory has a finding",
    )
    return parser.parse_args()


def split_assignment(value: str, option: str) -> tuple[str, str]:
    label, separator, raw = value.partition("=")
    if not separator or not label.strip() or not raw.strip():
        raise ValueError(f"{option} expects LABEL=VALUE, got {value!r}")
    label = label.strip()
    if not SAFE_LABEL.fullmatch(label):
        raise ValueError(
            f"{option} label may contain only letters, digits, dot, underscore and dash: "
            f"{label!r}"
        )
    return label, raw.strip()


def split_hint(value: str) -> tuple[str, str]:
    group, separator, artifact = value.partition(":")
    if not separator or not group.strip() or not artifact.strip() or ":" in artifact:
        raise ValueError(
            f"--coordinate-hint expects GROUP:ARTIFACT, got {value!r}")
    return group.strip(), artifact.strip()


def request_bytes(
    url: str,
    *,
    timeout: float,
    retries: int,
    body: bytes | None = None,
    content_type: str | None = None,
) -> bytes:
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=body, headers=headers)
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read()
        except (OSError, urllib.error.HTTPError) as error:
            if attempt >= retries:
                raise RuntimeError(f"request failed after {attempt + 1} attempts: {url}") from error
            time.sleep(min(2**attempt, 8))
    raise AssertionError("unreachable")


def request_json(
    url: str,
    *,
    timeout: float,
    retries: int,
    body: bytes | None = None,
) -> dict[str, Any]:
    return json.loads(
        request_bytes(
            url,
            timeout=timeout,
            retries=retries,
            body=body,
            content_type="application/json" if body is not None else None,
        )
    )


def resolve_platform_coordinates(version: str) -> list[dict[str, str]]:
    command = [
        str(ROOT / "gradlew"),
        "--quiet",
        "--no-configuration-cache",
        "-I",
        str(INIT_SCRIPT),
        "printSecurityCoordinates",
        f"-PhaloApiVersion={version}",
        # testRuntimeClasspath is resolved only as a group/artifact dictionary for
        # runtime JARs that omit pom.properties. It is never included in the
        # platform vulnerability result below.
        "-PsecurityConfigurations=compileClasspath,testRuntimeClasspath",
    ]
    completed = subprocess.run(
        command,
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    coordinates: set[tuple[str, str, str, str]] = set()
    for line in completed.stdout.splitlines():
        if not line.startswith("SECURITY_COORD|"):
            continue
        parts = line.split("|", 4)
        if len(parts) != 5:
            raise RuntimeError(f"malformed Gradle coordinate row: {line!r}")
        _, configuration, group, artifact, resolved_version = parts
        coordinates.add((configuration, group, artifact, resolved_version))
    if not any(row[0] == "compileClasspath" for row in coordinates):
        raise RuntimeError(f"Gradle returned no compile coordinates for Halo API {version}")
    return [
        {
            "configuration": configuration,
            "group": group,
            "artifact": artifact,
            "version": resolved_version,
        }
        for configuration, group, artifact, resolved_version in sorted(coordinates)
    ]


def parse_java_properties(raw: bytes) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in raw.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" in line:
            key, value = line.split("=", 1)
        elif ":" in line:
            key, value = line.split(":", 1)
        else:
            continue
        values[key.strip()] = value.strip()
    return values


def group_and_version_indexes(
    platform_coordinates: Iterable[dict[str, str]],
    hints: Iterable[tuple[str, str]],
) -> tuple[dict[str, set[str]], dict[tuple[str, str], set[str]]]:
    groups: dict[str, set[str]] = {}
    versions: dict[tuple[str, str], set[str]] = {}
    for coordinate in platform_coordinates:
        group = coordinate["group"]
        artifact = coordinate["artifact"]
        groups.setdefault(artifact, set()).add(group)
        versions.setdefault((group, artifact), set()).add(coordinate["version"])
    for group, artifact in hints:
        groups.setdefault(artifact, set()).add(group)
    return groups, versions


def infer_coordinate_from_filename(
    filename: str,
    groups: dict[str, set[str]],
    known_versions: dict[tuple[str, str], set[str]],
) -> tuple[str, str, str] | None:
    if not filename.endswith(".jar"):
        return None
    stem = filename[:-4]
    candidates: list[tuple[int, str, str, str]] = []
    for artifact, artifact_groups in groups.items():
        prefix = f"{artifact}-"
        if not stem.startswith(prefix):
            continue
        remainder = stem[len(prefix) :]
        if not remainder[:1].isdigit() or len(artifact_groups) != 1:
            continue
        group = next(iter(artifact_groups))
        version = remainder
        for known in sorted(known_versions.get((group, artifact), ()), key=len, reverse=True):
            if remainder == known or remainder.startswith(f"{known}-"):
                version = known
                break
        candidates.append((len(artifact), group, artifact, version))
    if not candidates:
        return None
    _, group, artifact, version = max(candidates)
    return group, artifact, version


def inventory_runtime(
    label: str,
    directory: Path,
    groups: dict[str, set[str]],
    known_versions: dict[tuple[str, str], set[str]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if not directory.is_dir():
        raise ValueError(f"runtime directory for {label!r} does not exist: {directory}")
    jar_paths = sorted(directory.rglob("*.jar"))
    if not jar_paths:
        raise ValueError(f"runtime directory for {label!r} contains no JAR files: {directory}")

    detected: dict[tuple[str, str, str], dict[str, Any]] = {}
    unresolved: list[str] = []
    for jar_path in jar_paths:
        relative = jar_path.relative_to(directory).as_posix()
        jar_coordinates: set[tuple[str, str, str]] = set()
        try:
            with zipfile.ZipFile(jar_path) as archive:
                property_files = sorted(
                    name
                    for name in archive.namelist()
                    if name.startswith("META-INF/maven/")
                    and name.endswith("/pom.properties")
                )
                for property_file in property_files:
                    values = parse_java_properties(archive.read(property_file))
                    group = values.get("groupId", "")
                    artifact = values.get("artifactId", "")
                    version = values.get("version", "")
                    if group and artifact and version:
                        jar_coordinates.add((group, artifact, version))
        except zipfile.BadZipFile as error:
            raise RuntimeError(f"invalid runtime JAR: {jar_path}") from error

        detection = "pom.properties"
        if not jar_coordinates:
            inferred = infer_coordinate_from_filename(
                jar_path.name, groups, known_versions
            )
            if inferred is None:
                unresolved.append(relative)
                continue
            jar_coordinates.add(inferred)
            detection = "filename+platform-group"

        for group, artifact, version in sorted(jar_coordinates):
            key = (group, artifact, version)
            row = detected.setdefault(
                key,
                {
                    "group": group,
                    "artifact": artifact,
                    "version": version,
                    "files": [],
                    "detections": [],
                },
            )
            row["files"].append(relative)
            if detection not in row["detections"]:
                row["detections"].append(detection)

    if unresolved:
        rendered = "\n  - ".join(unresolved)
        raise RuntimeError(
            f"{label}: unable to identify {len(unresolved)} runtime JAR(s); "
            "add --coordinate-hint GROUP:ARTIFACT:\n  - "
            f"{rendered}"
        )

    coordinates = [detected[key] for key in sorted(detected)]
    metadata = {
        "jarCount": len(jar_paths),
        "coordinateCount": len(coordinates),
        "allJarsIdentified": True,
    }
    return coordinates, metadata


def inventory_runtime_image(
    label: str,
    image: str,
    groups: dict[str, set[str]],
    known_versions: dict[tuple[str, str], set[str]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    try:
        inspected = json.loads(
            subprocess.run(
                ["docker", "image", "inspect", image],
                check=True,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            ).stdout
        )
    except FileNotFoundError as error:
        raise RuntimeError("docker is required by --runtime-image") from error
    if not isinstance(inspected, list) or len(inspected) != 1:
        raise RuntimeError(f"docker returned unexpected image metadata for {image!r}")
    image_metadata = inspected[0]

    container_id = ""
    with tempfile.TemporaryDirectory(prefix="halo-platform-audit-") as raw_directory:
        destination = Path(raw_directory) / label
        try:
            container_id = subprocess.run(
                ["docker", "create", image],
                check=True,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            ).stdout.strip()
            if not container_id:
                raise RuntimeError(f"docker create returned no container ID for {image!r}")

            library_path = ""
            copy_errors: list[str] = []
            for candidate in ("/application/lib", "/application/BOOT-INF/lib"):
                completed = subprocess.run(
                    ["docker", "cp", f"{container_id}:{candidate}", str(destination)],
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )
                if completed.returncode == 0:
                    library_path = candidate
                    break
                copy_errors.append(completed.stderr.strip())
            if not library_path:
                details = "; ".join(error for error in copy_errors if error)
                raise RuntimeError(
                    f"unable to copy Halo runtime JARs from {image!r}: {details}"
                )

            coordinates, metadata = inventory_runtime(
                label, destination, groups, known_versions
            )
            metadata.update(
                {
                    "image": image,
                    "imageId": image_metadata.get("Id"),
                    "repoDigests": sorted(image_metadata.get("RepoDigests") or []),
                    "libraryPath": library_path,
                }
            )
            return coordinates, metadata
        finally:
            if container_id:
                subprocess.run(
                    ["docker", "rm", "--force", container_id],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    check=False,
                )


def query_osv(
    coordinates: list[dict[str, Any]],
    *,
    base_url: str,
    timeout: float,
    retries: int,
) -> dict[str, Any]:
    findings: list[dict[str, Any]] = []
    chunk_size = 500
    for offset in range(0, len(coordinates), chunk_size):
        chunk = coordinates[offset : offset + chunk_size]
        payload = {
            "queries": [
                {
                    "package": {
                        "ecosystem": "Maven",
                        "name": f"{row['group']}:{row['artifact']}",
                    },
                    "version": row["version"],
                }
                for row in chunk
            ]
        }
        response = request_json(
            f"{base_url.rstrip('/')}/v1/querybatch",
            timeout=timeout,
            retries=retries,
            body=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        )
        results = response.get("results")
        if not isinstance(results, list) or len(results) != len(chunk):
            raise RuntimeError("OSV querybatch returned an unexpected result count")
        for coordinate, result in zip(chunk, results, strict=True):
            for advisory in result.get("vulns", []):
                finding = {
                    "group": coordinate["group"],
                    "artifact": coordinate["artifact"],
                    "version": coordinate["version"],
                    "advisoryId": advisory["id"],
                    "modified": advisory.get("modified"),
                }
                if "files" in coordinate:
                    finding["files"] = coordinate["files"]
                findings.append(finding)

    findings.sort(
        key=lambda row: (
            row["group"],
            row["artifact"],
            row["version"],
            row["advisoryId"],
        )
    )
    affected_coordinates = {
        (row["group"], row["artifact"], row["version"]) for row in findings
    }
    advisories = {row["advisoryId"] for row in findings}
    return {
        "coordinateCount": len(coordinates),
        "affectedCoordinateCount": len(affected_coordinates),
        "findingRowCount": len(findings),
        "advisoryCount": len(advisories),
        "findings": findings,
    }


def upstream_snapshot(timeout: float, retries: int) -> dict[str, Any]:
    release_url = "https://api.github.com/repos/halo-dev/halo/releases/latest"
    release = request_json(release_url, timeout=timeout, retries=retries)
    jar_assets = [
        {
            "name": asset.get("name"),
            "size": asset.get("size"),
            "digest": asset.get("digest"),
            "url": asset.get("browser_download_url"),
        }
        for asset in release.get("assets", [])
        if str(asset.get("name", "")).endswith(".jar")
    ]

    metadata_url = (
        "https://repo1.maven.org/maven2/run/halo/tools/platform/plugin/"
        "maven-metadata.xml"
    )
    metadata_xml = request_bytes(metadata_url, timeout=timeout, retries=retries)
    root = ET.fromstring(metadata_xml)
    versioning = root.find("versioning")
    if versioning is None:
        raise RuntimeError("Halo plugin platform Maven metadata has no versioning node")
    return {
        "haloRelease": {
            "source": release_url,
            "tag": release.get("tag_name"),
            "publishedAt": release.get("published_at"),
            "url": release.get("html_url"),
            "jarAssets": jar_assets,
        },
        "pluginApiPlatform": {
            "source": metadata_url,
            "latest": versioning.findtext("latest"),
            "release": versioning.findtext("release"),
            "lastUpdated": versioning.findtext("lastUpdated"),
        },
    }


def git_commit() -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def git_is_dirty() -> bool:
    status = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout
    return bool(status.strip())


def write_json_atomic(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=path.parent, delete=False
    ) as temporary:
        temporary.write(serialized)
        temporary_path = Path(temporary.name)
    os.replace(temporary_path, path)


def main() -> int:
    args = parse_args()
    if args.retries < 0:
        raise ValueError("--retries must be non-negative")
    if args.timeout <= 0:
        raise ValueError("--timeout must be positive")

    api_versions = tuple(dict.fromkeys(args.api_versions or DEFAULT_API_VERSIONS))
    runtime_dirs = [
        (label, Path(raw_path).expanduser().resolve())
        for label, raw_path in (
            split_assignment(value, "--runtime-dir") for value in args.runtime_dir
        )
    ]
    runtime_images = [
        (label, image)
        for label, image in (
            split_assignment(value, "--runtime-image") for value in args.runtime_image
        )
    ]
    runtime_labels = [label for label, _ in runtime_dirs + runtime_images]
    if len(set(runtime_labels)) != len(runtime_labels):
        raise ValueError("runtime labels must be unique across --runtime-dir/--runtime-image")
    hints = [split_hint(value) for value in args.coordinate_hint]

    platforms: dict[str, Any] = {}
    all_platform_coordinates: list[dict[str, str]] = []
    for version in api_versions:
        resolved_coordinates = resolve_platform_coordinates(version)
        all_platform_coordinates.extend(resolved_coordinates)
        coordinates = [
            row
            for row in resolved_coordinates
            if row["configuration"] == "compileClasspath"
        ]
        result = query_osv(
            coordinates,
            base_url=args.osv_url,
            timeout=args.timeout,
            retries=args.retries,
        )
        result["resolvedConfiguration"] = "compileClasspath"
        platforms[version] = result
        print(
            f"Halo API {version}: {result['coordinateCount']} coordinates, "
            f"{result['advisoryCount']} advisories, "
            f"{result['affectedCoordinateCount']} affected coordinates"
        )

    groups, known_versions = group_and_version_indexes(
        all_platform_coordinates, hints
    )
    runtimes: dict[str, Any] = {}
    for label, directory in runtime_dirs:
        coordinates, metadata = inventory_runtime(
            label, directory, groups, known_versions
        )
        result = query_osv(
            coordinates,
            base_url=args.osv_url,
            timeout=args.timeout,
            retries=args.retries,
        )
        result.update(metadata)
        runtimes[label] = result
        print(
            f"Halo runtime {label}: {result['jarCount']} JARs, "
            f"{result['coordinateCount']} coordinates, "
            f"{result['advisoryCount']} advisories, "
            f"{result['affectedCoordinateCount']} affected coordinates"
        )
    for label, image in runtime_images:
        coordinates, metadata = inventory_runtime_image(
            label, image, groups, known_versions
        )
        result = query_osv(
            coordinates,
            base_url=args.osv_url,
            timeout=args.timeout,
            retries=args.retries,
        )
        result.update(metadata)
        runtimes[label] = result
        print(
            f"Halo runtime {label}: {result['jarCount']} JARs, "
            f"{result['coordinateCount']} coordinates, "
            f"{result['advisoryCount']} advisories, "
            f"{result['affectedCoordinateCount']} affected coordinates"
        )

    generated_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    document = {
        "schemaVersion": 1,
        "generatedAt": generated_at,
        "source": {
            "repositoryCommit": git_commit(),
            "repositoryDirty": git_is_dirty(),
            "script": INIT_SCRIPT.parent.joinpath("audit-halo-platform.py")
            .relative_to(ROOT)
            .as_posix(),
            "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        },
        "osv": {
            "queryEndpoint": f"{args.osv_url.rstrip('/')}/v1/querybatch",
            "dataSent": "Maven package names and versions only",
        },
        "upstream": upstream_snapshot(args.timeout, args.retries),
        "platformCompileClasspaths": platforms,
        "haloRuntimeInventories": runtimes,
    }
    output = args.output.expanduser().resolve()
    write_json_atomic(output, document)
    print(f"Report: {output}")
    if args.fail_on_findings and any(
        result["advisoryCount"]
        for result in [*platforms.values(), *runtimes.values()]
    ):
        print("Gate: FAIL (OSV findings present)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
