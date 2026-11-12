"""Build the initial GB Wars CPU production table experiment."""
from hashlib import sha256
from pathlib import Path
import sys

rom = Path(sys.argv[1]).read_bytes()
base = Path(sys.argv[2]).read_bytes()
assert sha256(rom).hexdigest() == "a0c1e04808c7a3bf8eb306a74af333bf7a2258bae511b385ab103a5a2699bb30"
assert sha256(base).hexdigest() == "5633d0c01f57f46c0f4ef4ba8ed53ad4d96b63d00216f28ce5d3665be697e0d4"

start = 0x7F93
code = bytearray()
labels = {}
calls = []


def emit(hex_bytes):
    code.extend(bytes.fromhex(hex_bytes))


def label(name):
    labels[name] = start + len(code)


def call(name):
    calls.append((len(code) + 1, name))
    emit("cd 00 00")


# Use the original routine on the last comparison in each row so the
# materialized table at the end of the row remains byte-for-byte identical.
emit("fe 2e d2 bf 20")
# Index into the 34-byte table: 10 + (opponent type / 2).
emit("cb 3f c6 0a 4f fa de c4")
call("read_value")
emit("f5 fa dd c4")
call("read_value")
emit("47 f1 4f")
# Reproduce the zero rules and the returned maximum from $20BF.
emit("16 00 78 b1 c8 14")
emit("fa dd c4 e6 3f 20 02 06 00")
emit("fa de c4 e6 3f 20 02 0e 00")
emit("78 b9 d0 79 c9")

label("read_value")
# Select the same table as $2178. Absent selectors and zero IDs read as zero.
emit("07 07 e6 03 c8 3d 21 ee c4 cd a4 0e 7e a7 c8")
# Resolve the ROM pointer with the existing routine. Keep C as the index.
emit("c5 cd c0 21 c1 79 cd a4 0e 7e c9")

for offset, name in calls:
    code[offset:offset + 2] = labels[name].to_bytes(2, "little")

hook = 0xA445
code_offset = 0x4000 + start
assert rom[hook:hook + 3] == bytes.fromhex("cd bf 20")
assert code_offset + len(code) <= 0xBFFE
assert not any(rom[code_offset:code_offset + len(code)])
records = {hook: b"\xcd" + start.to_bytes(2, "little"), code_offset: bytes(code)}
index = 5
while base[index:index + 3] != b"EOF":
    offset = int.from_bytes(base[index:index + 3], "big")
    size = int.from_bytes(base[index + 3:index + 5], "big")
    assert size and all(offset + size <= pos or pos + len(data) <= offset for pos, data in records.items())
    records[offset] = base[index + 5:index + 5 + size]
    index += 5 + size

patch = b"PATCH" + b"".join(
    offset.to_bytes(3, "big") + len(data).to_bytes(2, "big") + data
    for offset, data in sorted(records.items())
) + b"EOF"
Path(sys.argv[3]).write_bytes(patch)
