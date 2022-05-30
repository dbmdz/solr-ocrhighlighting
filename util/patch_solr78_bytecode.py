#!/usr/bin/env python3
""" Patch a JAR file with classes built against Solr 9 to work with Solr 7 and 8.

Why didn't we use ByteBuddy or at least ASM for this? Well, blame Maven.
Building two JARs from one pom without a ton of ceremony seems to be
out of scope for it, so this manual approach was chosen instead.
"""
import os
import struct
import sys
import zipfile
from pathlib import Path
from typing import BinaryIO, Callable, Dict, Union


def _get_utf8_size(entry: bytes) -> int:
    """Get the size in bytes of a Utf8 constant pool entry."""
    length = struct.unpack_from(">H", entry, 1)[0]
    return 3 + length


#: Mapping from tags used in the constant pool to the size their
#: associated information takes up in the pool
CONSTANT_POOL_SIZES: Dict[int, Union[int, Callable[[bytes], int]]] = {
    7: 3,  # classinfo(u1 tag, u2 name_idx),
    9: 5,  # fieldref(u1 tag, u2 class_idx, u2 name_and_type_idx)
    10: 5,  # methodref(u1 tag, u2 class_idx, u2 name_and_type_idx)
    11: 5,  # interfacemethodref(u1 tag, u2 class_idx, u2 name_and_type_idx)
    8: 3,  # string(u1 tag, u2 string_idx)
    3: 5,  # integer(u1 tag, u4 bytes)
    4: 5,  # float(u1 tag, u4 bytes)
    5: 9,  # long(u1 tag, u4 hi_bytes, u4 lo_bytes)
    6: 9,  # double(u1 tag, u4 hi_bytes, u4 lo_bytes)
    12: 5,  # nameandbytes(u1 tag, u2 name_idx, u2 descriptor_idx)
    1: _get_utf8_size,
    15: 4,  # methodhandle(u1 tag, u2 ref_kind, u2 ref_idx)
    16: 3,  # methodtype(u1 tag, u2 descriptor_idx)
    17: 5,  # dynamic(u1 tag, u2 bootstrap_method_attr_idx, u2 name_and_type_idx)
    18: 5,  # invokedynamic(u1 tag, u2 bootstrap_method_attr_idx, u2 name_and_type_idx)
    19: 3,  # module(u1 tag, u2 name_idx)
    20: 3,  # package(u1 tag, u2 name_idx)
}

#: Package substitutions that need to be made for Solr <9 compatbility
PACKAGE_SUBS = [
    (
        b"org/apache/lucene/analysis/CharFilterFactory",
        b"org/apache/lucene/analysis/util/CharFilterFactory",
    )
]


def patch_package_paths(bytecode: bytes) -> bytes:
    """With Solr and Lucene 9, package paths have changed, modify
    the class file so they point to the old locations instead.
    """
    for src, target in PACKAGE_SUBS:
        if src not in bytecode:
            continue
        idx = bytecode.index(src)

        # Check if we're in the constant pool, i.e. our value is
        # preceded by a big-endian short with the length of our value
        length_idx = idx - 2
        length = struct.unpack_from(">H", bytecode, length_idx)[0]
        if length != len(src):
            continue

        # Now we update the name of the package as well as the length
        # of the value in the constant pool info structure
        buf = bytearray(bytecode.replace(src, target))
        struct.pack_into(">H", buf, length_idx, len(target))
        bytecode = bytes(buf)
    return bytecode


def patch_close_hook(bytecode: bytes) -> bytes:
    """With Solr 9, `org.apache.solr.core.CloseHook` became an interface,
    this function will patch the resulting byte code to use it as
    an abstract base class instead to be compatible with Solr<9.
    """
    buf = bytearray(bytecode)
    if buf[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Not a classfile, corrupt JAR?")
    constant_pool_count = struct.unpack_from(">H", buf, 8)[0]

    # Traverse constant pool
    constant_pool_size = 0
    constant_pool_start = 10
    for _ in range(constant_pool_count - 1):
        tag = buf[constant_pool_start + constant_pool_size]
        # Modify reference to super class constructor to point to our
        # new base class instead of java.lang.Object<init>
        if tag == 10:  # method reference
            target_idx = constant_pool_start + constant_pool_size + 2
            if buf[target_idx] == 7:  # reference to java.lang.Object
                buf[target_idx] = 8  # change to CloseHook
        # Update the constant pool size
        pool_size = CONSTANT_POOL_SIZES[tag]
        if isinstance(pool_size, int):
            constant_pool_size += pool_size
        else:
            constant_pool_size += pool_size(
                buf[constant_pool_start + constant_pool_size :]
            )
    after_pool_idx = constant_pool_start + constant_pool_size
    iface_count = struct.unpack_from(">H", buf, after_pool_idx + 6)[0]
    if iface_count != 1:
        return bytes(buf)

    # Start of interface pointers
    ifaces_idx = after_pool_idx + 8
    # Object pool index of the `CloseHook` type
    closehook_idx = struct.unpack_from(">H", buf, ifaces_idx)[0]
    # Strip interfaces list from class file
    del buf[ifaces_idx : ifaces_idx + 2]
    # Set `CloseHook` as the super class
    buf[after_pool_idx + 4 : after_pool_idx + 6] = struct.pack(">H", closehook_idx)
    # Clear all interfaces for the class
    buf[after_pool_idx + 6 : after_pool_idx + 8] = b"\x00\x00"
    return bytes(buf)


def patch_jar(source_path: Path, target: BinaryIO) -> None:
    with zipfile.ZipFile(source_path) as source_jar, zipfile.ZipFile(
        target, "w", compression=zipfile.ZIP_DEFLATED
    ) as target_jar:
        for fname in source_jar.namelist():
            data = source_jar.read(fname)
            if fname.endswith(".class"):
                data = patch_package_paths(data)
            if fname.endswith("OcrHighlightComponent$1.class"):
                data = patch_close_hook(data)
            target_jar.writestr(fname, data)


if __name__ == "__main__":
    if len(sys.argv) == 1:
        build_path = Path(__file__).parent / "../target"
        source_path = next(
            p
            for p in build_path.iterdir()
            if p.name.endswith(".jar")
            and p.name.startswith("solr-ocrhighlighting")
            and not p.stem.split("-")[-1] in ("javadoc", "sources", "solr78")
        ).resolve()
    elif sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        print("Usage:")
        print(
            "$ patch_solr78_bytecode.py [<source_jar>] [<target_jar>]", file=sys.stderr
        )
        sys.exit(1)
    else:
        source_path = Path(sys.argv[1])
    if not source_path.exists():
        print(f"File at {source_path} does not exist!")
        sys.exit(1)
    if len(sys.argv) == 3:
        target_path = Path(sys.argv[2])
    else:
        target_path = (source_path.parent / f"{source_path.stem}-solr78.jar").resolve()

    print(
        f"Patching '{source_path.relative_to(os.getcwd())}', writing output to '{target_path.relative_to(os.getcwd())}'"
    )
    with target_path.open("wb") as fp:
        patch_jar(source_path, fp)
