"""Rebuild the released IPS from the supported ROM and component patch."""

from hashlib import sha256
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).parents[1]
SOURCE_SHA256 = "a0c1e04808c7a3bf8eb306a74af333bf7a2258bae511b385ab103a5a2699bb30"
FRONTIER_SHA256 = "5633d0c01f57f46c0f4ef4ba8ed53ad4d96b63d00216f28ce5d3665be697e0d4"
RELEASE_SHA256 = "d6b7403475f3e8c7d322e3d3e8301215addbbe08e9ed829e22447d67d3ba8ab5"


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {Path(sys.argv[0]).name} PATH_TO_GBWARS.GB")

    rom = Path(sys.argv[1])
    frontier = ROOT / "patches" / "gbwars-frontier-blocks.ips"
    output = ROOT / "patches" / "gbwars-speed-patch.ips"

    if sha256(rom.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise SystemExit("ROM SHA-256 does not match the supported Japanese GB Wars ROM")
    if sha256(frontier.read_bytes()).hexdigest() != FRONTIER_SHA256:
        raise SystemExit("Frontier component SHA-256 did not match")

    subprocess.run([
        sys.executable,
        str(ROOT / "scripts" / "build_production_table_patch.py"),
        str(rom),
        str(frontier),
        str(output),
    ], check=True)

    patch = output.read_bytes()
    if not patch.endswith(b"EOF"):
        raise SystemExit("Generated IPS was malformed")
    cadence_record = bytes.fromhex("0047f8000103")
    output.write_bytes(patch[:-3] + cadence_record + b"EOF")
    if sha256(output.read_bytes()).hexdigest() != RELEASE_SHA256:
        raise SystemExit("Generated IPS SHA-256 did not match")

    print(f"Rebuilt {output}")


if __name__ == "__main__":
    main()

