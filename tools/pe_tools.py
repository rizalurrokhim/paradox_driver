"""Helper kecil untuk membedah PE 32-bit (dipakai hanya saat reverse engineering).

Menyediakan pemetaan RVA<->offset file, disassembly dengan Capstone, dan
pencarian xref ke sebuah alamat (IAT / konstanta).
"""
import struct

import capstone
import pefile


class PE32:
    def __init__(self, path):
        self.path = path
        self.pe = pefile.PE(path, fast_load=False)
        self.image_base = self.pe.OPTIONAL_HEADER.ImageBase
        self.sections = []
        for s in self.pe.sections:
            self.sections.append(
                (s.VirtualAddress, s.Misc_VirtualSize, s.PointerToRawData, s.SizeOfRawData, s.Name.rstrip(b"\0").decode(errors="replace"))
            )
        self.md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
        self.md.detail = True

    def read_rva(self, rva, size):
        for va, vsize, raw, rawsize, _ in self.sections:
            if va <= rva < va + max(vsize, rawsize):
                off = raw + (rva - va)
                with open(self.path, "rb") as f:
                    f.seek(off)
                    return f.read(size)
        return b""

    def disasm(self, rva, count=60):
        data = self.read_rva(rva, count * 8)
        base = self.image_base + rva
        return list(self.md.disasm(data, base))[:count]

    def print_disasm(self, rva, count=60, label=""):
        print(f"--- disasm RVA {rva:#x} {label} ---")
        for ins in self.disasm(rva, count):
            print(f"{ins.address - self.image_base:#08x}  {ins.mnemonic:8s} {ins.op_str}")

    def imports(self):
        out = []
        for entry in getattr(self.pe, "DIRECTORY_ENTRY_IMPORT", []):
            dll = entry.dll.decode(errors="replace")
            for imp in entry.imports:
                name = imp.name.decode(errors="replace") if imp.name else f"ord{imp.ordinal}"
                out.append((dll, name, imp.address))
        return out

    def exports(self):
        out = []
        for exp in getattr(self.pe, "DIRECTORY_ENTRY_EXPORT", None).symbols if getattr(self.pe, "DIRECTORY_ENTRY_EXPORT", None) else []:
            out.append((exp.name.decode(errors="replace") if exp.name else f"ord{exp.ordinal}", exp.address))
        return out

    def all_code_sections(self):
        for va, vsize, raw, rawsize, name in self.sections:
            if name.startswith(".text"):
                yield va, rawsize

    def find_call_imm(self, target_va, max_hits=40):
        """Cari instruksi 'call rel32' yang menuju target_va."""
        hits = []
        for va, size in self.all_code_sections():
            code = self.read_rva(va, size)
            for i in range(len(code) - 5):
                if code[i] == 0xE8:
                    rel = struct.unpack_from("<i", code, i + 1)[0]
                    src = self.image_base + va + i
                    if (src + 5 + rel) == target_va:
                        hits.append((src - self.image_base, i))
                        if len(hits) >= max_hits:
                            return hits
        return hits

    def find_va_refs(self, target_va, max_hits=40):
        """Cari referensi absolut (push imm32 / mov reg, imm32) ke target_va."""
        pat = struct.pack("<I", target_va)
        hits = []
        for va, size in self.all_code_sections():
            code = self.read_rva(va, size)
            idx = code.find(pat)
            while idx != -1:
                hits.append(va + idx - 1)  # kira-kira offset opcode
                if len(hits) >= max_hits:
                    return hits
                idx = code.find(pat, idx + 1)
        return hits


def hexdump(b):
    return " ".join(f"{x:02x}" for x in b)


def func_start(pe, rva, lower=0x1000):
    """Cari awal fungsi dengan memindai mundur padding int3 (0xCC)."""
    data = pe.read_rva(lower, rva - lower)
    idx = len(data) - 1
    while idx >= 0:
        if data[idx] == 0xCC:
            # lewati deretan int3
            j = idx
            while j >= 0 and data[j] == 0xCC:
                j -= 1
            if idx - j >= 2:
                return lower + idx + 1
            idx = j
        else:
            idx -= 1
    return lower
