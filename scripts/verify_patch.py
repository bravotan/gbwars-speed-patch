"""Verify that the distributed IPS applies to the intended GB Wars ROM."""

from hashlib import sha256
from pathlib import Path
import sys

SOURCE_SHA256 = "a0c1e04808c7a3bf8eb306a74af333bf7a2258bae511b385ab103a5a2699bb30"
PATCH_SHA256 = "d6b7403475f3e8c7d322e3d3e8301215addbbe08e9ed829e22447d67d3ba8ab5"
OUTPUT_SHA256 = "c68702e791b9019c4070f8187f7b2f44fa9bccdfc3ccc8c30ca4328d6f9ec77f"


def apply_ips(rom: bytearray, patch: bytes) -> None:
    if not patch.startswith(b"PATCH") or not patch.endswith(b"EOF"):
        raise ValueError("Not an IPS patch")

    position = 5
    while patch[position:position + 3] != b"EOF":
        offset = int.from_bytes(patch[position:position + 3], "big")
        length = int.from_bytes(patch[position + 3:position + 5], "big")
        position += 5
        if length:
            rom[offset:offset + length] = patch[position:position + length]
            position += length
            continue

        length = int.from_bytes(patch[position:position + 2], "big")
        value = patch[position + 2]
        rom[offset:offset + length] = bytes([value]) * length
        position += 3


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {Path(sys.argv[0]).name} PATH_TO_GBWARS.GB")

    rom = bytearray(Path(sys.argv[1]).read_bytes())
    patch_path = Path(__file__).parents[1] / "patches" / "gbwars-speed-patch.ips"
    patch = patch_path.read_bytes()

    if sha256(rom).hexdigest() != SOURCE_SHA256:
        raise SystemExit("ROM SHA-256 does not match the supported Japanese GB Wars ROM")
    if sha256(patch).hexdigest() != PATCH_SHA256:
        raise SystemExit("IPS SHA-256 does not match the distributed patch")

    apply_ips(rom, patch)
    if sha256(rom).hexdigest() != OUTPUT_SHA256:
        raise SystemExit("Patched ROM SHA-256 did not match")
    print("Verified supported ROM and IPS output")


if __name__ == "__main__":
    main()

