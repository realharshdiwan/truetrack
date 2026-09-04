#!/usr/bin/env python3
"""Download IO-VNBD files using GitHub LFS batch API."""

import json
import subprocess
import sys
from pathlib import Path

REPO = "onyekpeu/IO-VNBD"
BASE = "https://github.com"

# Files to download - start with a few small ones for testing
FILES = [
    "Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-S1.csv",
    "Synchronised V abd S datasets/Uncategorised IOVNB Dataset/V-Dataset/V-S1.csv",
    "Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-M.csv",
    "Synchronised V abd S datasets/Uncategorised IOVNB Dataset/V-Dataset/V-M.csv",
]

OUT_DIR = Path("/Users/diwan/Developer/TrueTrack/data/IO-VNBD")


def get_lfs_oid(file_path: str) -> str:
    """Get LFS OID by cloning just the pointer."""
    # Use git to get the blob SHA
    result = subprocess.run(
        ["git", "ls-tree", "HEAD", file_path],
        capture_output=True, text=True,
        cwd="/Users/diwan/Developer/TrueTrack/data/IO-VNBD"
    )
    if result.returncode == 0:
        parts = result.stdout.strip().split()
        if len(parts) >= 3:
            return parts[2]  # blob SHA
    return None


def download_via_github_api(file_path: str, out_path: Path):
    """Download file using GitHub API raw content."""
    url = f"{BASE}/{REPO}/raw/master/{file_path}"
    print(f"  Downloading: {file_path}")

    result = subprocess.run(
        ["curl", "-sL", "-o", str(out_path), url],
        capture_output=True, text=True
    )

    if out_path.exists():
        # Check if it's an LFS pointer
        with open(out_path) as f:
            first_line = f.readline()
        if "git-lfs" in first_line:
            print(f"    -> Got LFS pointer, trying batch API...")
            out_path.unlink()
            return False
        else:
            size = out_path.stat().st_size
            print(f"    -> Downloaded {size} bytes")
            return True
    return False


def download_lfs_batch(file_path: str, out_path: Path):
    """Download using LFS batch API."""
    # First get the pointer content
    pointer_url = f"{BASE}/{REPO}/raw/master/{file_path}"
    result = subprocess.run(
        ["curl", "-sL", pointer_url],
        capture_output=True, text=True
    )

    if result.returncode != 0:
        return False

    pointer_content = result.stdout
    if "git-lfs" not in pointer_content:
        return False

    # Parse pointer
    oid = None
    size = None
    for line in pointer_content.strip().split("\n"):
        if line.startswith("oid sha256:"):
            oid = line.split(":", 1)[1]
        elif line.startswith("size "):
            size = int(line.split(" ", 1)[1])

    if not oid:
        return False

    print(f"    OID: {oid[:16]}..., Size: {size}")

    # Use LFS batch API
    batch_url = f"{BASE}/{REPO}/info/lfs/objects/batch"
    batch_data = json.dumps({
        "operation": "download",
        "transfers": ["basic"],
        "ref": {"name": "refs/heads/master"},
        "objects": [{"oid": oid, "size": size}]
    })

    result = subprocess.run(
        ["curl", "-sL", "-X", "POST",
         "-H", "Content-Type: application/vnd.git-lfs+json",
         "-H", "Accept: application/vnd.git-lfs+json",
         "-d", batch_data,
         batch_url],
        capture_output=True, text=True
    )

    if result.returncode != 0:
        return False

    try:
        response = json.loads(result.stdout)
        actions = response.get("objects", [{}])[0].get("actions", {})
        download_action = actions.get("download", {})

        if not download_action:
            # Try href directly
            href = response.get("objects", [{}])[0].get("actions", {}).get("download", {}).get("href")
            if not href:
                print(f"    No download action found")
                return False
        else:
            href = download_action.get("href")

        if not href:
            print(f"    No download URL found")
            return False

        # Download the actual file
        header = download_action.get("header", {})
        curl_cmd = ["curl", "-sL", "-o", str(out_path)]

        for key, value in header.items():
            curl_cmd.extend(["-H", f"{key}: {value}"])

        curl_cmd.append(href)

        result = subprocess.run(curl_cmd, capture_output=True, text=True)

        if out_path.exists() and out_path.stat().st_size > 100:
            print(f"    -> Downloaded {out_path.stat().st_size} bytes")
            return True

    except (json.JSONDecodeError, KeyError, IndexError) as e:
        print(f"    Error parsing response: {e}")

    return False


def main():
    print("Downloading IO-VNBD files...\n")

    for file_path in FILES:
        filename = Path(file_path).name
        # Determine output directory based on path
        if "/S-Dataset/" in file_path:
            out_path = OUT_DIR / "S-Dataset" / filename
        elif "/V-Dataset/" in file_path:
            out_path = OUT_DIR / "V-Dataset" / filename
        else:
            out_path = OUT_DIR / filename

        out_path.parent.mkdir(parents=True, exist_ok=True)

        # Try direct download first
        if not download_via_github_api(file_path, out_path):
            # Try LFS batch API
            download_lfs_batch(file_path, out_path)

    print("\nDone!")


if __name__ == "__main__":
    main()
