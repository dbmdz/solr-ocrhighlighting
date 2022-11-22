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
from typing import (
    BinaryIO,
    Callable,
    Dict,
    Union,
    Iterable,
    MutableMapping,
    NamedTuple,
)


def _get_utf8_size(entry_data: bytes) -> int:
    """Get the size in bytes of a Utf8 constant pool entry."""
    length = struct.unpack_from(">H", entry_data, 1)[0]
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
    18: 5,
    # invokedynamic(u1 tag, u2 bootstrap_method_attr_idx, u2 name_and_type_idx)
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


def patch_package_paths(classfile: bytes) -> bytes:
    """With Solr and Lucene 9, package paths have changed, modify
    the class file, so they point to the old locations instead.
    """
    for src, target in PACKAGE_SUBS:
        if src not in classfile:
            continue
        idx = classfile.index(src)

        # Check if we're in the constant pool, i.e. our value is
        # preceded by a big-endian short with the length of our value
        length_idx = idx - 2
        length = struct.unpack_from(">H", classfile, length_idx)[0]
        if length != len(src):
            continue

        # Now we update the name of the package as well as the length
        # of the value in the constant pool info structure
        buf = bytearray(classfile.replace(src, target))
        struct.pack_into(">H", buf, length_idx, len(target))
        classfile = bytes(buf)
    return classfile


class ConstantPoolEntry(NamedTuple):
    #: Index of the entry in the constant-pool (1-based!!)
    pool_idx: int
    #: Tag of the entry, see table 4.4-A at
    #: https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.4
    tag: int
    #: The data associated with the entry, for the interpretation see links to
    #: subsections in the above table
    value: bytes
    #: Byte offset of the entry from the beginning of the class file
    offset: int


def _check_valid_classfile(classfile: bytearray) -> None:
    if classfile[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Not a class file, corrupt JAR?")


def _walk_constant_pool(classfile: bytearray) -> Iterable[ConstantPoolEntry]:
    """Traverse the constant pool, yielding each entry in the pool."""
    _check_valid_classfile(classfile)
    constant_pool_count = struct.unpack_from(">H", classfile, 8)[0]
    constant_pool_size = 0
    constant_pool_start = 10
    for idx in range(1, constant_pool_count):
        offset = constant_pool_start + constant_pool_size
        tag = classfile[offset]
        if isinstance(CONSTANT_POOL_SIZES[tag], Callable):
            entry_size = CONSTANT_POOL_SIZES[tag](classfile[offset:])
        else:
            entry_size = CONSTANT_POOL_SIZES[tag]
        yield ConstantPoolEntry(
            idx, tag, classfile[offset + 1 : offset + entry_size], offset
        )
        constant_pool_size += entry_size


def patch_close_hook(classfile: bytes) -> bytes:
    """With Solr 9, `org.apache.solr.core.CloseHook` became an interface,
    while it is an abstract class in Solr 7 and 8. To make the bytecode work
    for Solr 7 and 8, this function patches the classfile to replace the
    reference to the non-existing `CloseHook` interface to the `CloseHook`
    abstract class.

    This will apply the following changes to the class file:
    - Change the super class to `org.apache.solr.core.CloseHook`
    - Change superclass constructor from `java.lang.Object<init>` to
      `org.apache.solr.core.CloseHook<init>` by changing the class associated
      with the constructor reference in the constant pool
    - Remove all associated interfaces from the class file
    """
    buf = bytearray(classfile)
    _check_valid_classfile(buf)

    # Traverse constant pool once to find all string constants
    strings: MutableMapping[int, str] = {}
    for (idx, tag, entry, offset) in _walk_constant_pool(buf):
        if tag != 1:  # utf8 string
            continue
        strings[idx] = entry[2:].decode("utf-8")

    # Traverse constant pool again to find indices of java.lang.Object and
    # org.apache.solr.core.CloseHook class entries in the constant pool
    object_idx = None
    close_hook_idx = None
    for (idx, tag, entry, offset) in _walk_constant_pool(buf):
        if tag != 7:  # class info
            continue
        name_idx = struct.unpack(">H", entry)[0]
        if name_idx not in strings:
            continue
        if strings[name_idx] == "java/lang/Object":
            object_idx = idx
        elif strings[name_idx] == "org/apache/solr/core/CloseHook":
            close_hook_idx = idx

    if object_idx is None or close_hook_idx is None:
        print(
            "Could not find java.lang.Object or org.apache.solr.core.CloseHook "
            "in constant pool, not patching",
            file=sys.stderr,
        )
        return classfile

    # And lastly, traverse constant pool to change the superclass constructor
    # reference to point to org.apache.solr.core.CloseHook instead of
    # java.lang.Object
    constructor_call_patched = False
    after_pool_idx = 0
    for (idx, tag, entry, offset) in _walk_constant_pool(buf):
        after_pool_idx = offset + len(entry) + 1
        if tag != 10:  # method reference
            continue
        class_idx, name_and_type_idx = struct.unpack(">HH", entry)
        if class_idx != object_idx:
            continue
        # Point class index to org.apache.solr.core.CloseHook instead
        buf[offset + 1 : offset + 3] = struct.pack(">H", close_hook_idx)
        constructor_call_patched = True

    if not constructor_call_patched:
        print(
            "Could not find constructor reference to java.lang.Object in "
            "constant pool, not patching",
            file=sys.stderr,
        )
        return classfile

    iface_count = struct.unpack_from(">H", buf, after_pool_idx + 6)[0]
    if iface_count != 1:
        print(
            "OcrHighlightComponent$1 does not implement exactly one interface, "
            "did the API change?",
            file=sys.stderr,
        )
        return classfile

    # Start of interface pointers
    ifaces_idx = after_pool_idx + 8
    # Set `CloseHook` as the super class
    buf[after_pool_idx + 4 : after_pool_idx + 6] = struct.pack(">H", close_hook_idx)
    # Clear all interfaces for the class
    buf[after_pool_idx + 6 : after_pool_idx + 8] = b"\x00\x00"
    # Strip interfaces list from class file
    del buf[ifaces_idx : ifaces_idx + 2]
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
