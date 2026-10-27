"""Small Windows-x64 ctypes adapter for the exact packaged C headers.

Exports use __stdcall (WinDLL); file callbacks are plain C function pointers
(CFUNCTYPE). Windows x64 unifies calling conventions. unsigned long is 32 bits,
handles/pointers are 64 bits. No private engine structures or native compilation.
"""

import ctypes as C
from pathlib import Path
import threading

HANDLE = C.c_void_p
INT, ULONG, DOUBLE = C.c_int, C.c_ulong, C.c_double
READ = C.CFUNCTYPE(INT, HANDLE, ULONG, HANDLE, ULONG)
WRITE = C.CFUNCTYPE(INT, HANDLE, HANDLE, ULONG)


class FileAccess(C.Structure):
    _fields_ = [("length", ULONG), ("read", READ), ("param", HANDLE)]


class FileWrite(C.Structure):
    _fields_ = [("version", INT), ("write", WRITE)]


class Rect(C.Structure):
    _fields_ = [("left", C.c_float), ("top", C.c_float), ("right", C.c_float), ("bottom", C.c_float)]


class Source:
    """Dedicated read-only descriptor per attempt, absolute offsets, full reads.

    Windows Python has no os.pread. Seek/readinto is serialized on this OWN
    descriptor; no descriptor is shared with another document, copy, or caller.
    The callback, FileAccess and file stay alive until base/document destruction.
    """
    def __init__(self, path):
        self.file = Path(path).open("rb", buffering=0)
        self.size = self.file.seek(0, 2)
        if not 0 < self.size <= 0xffffffff:
            self.file.close()
            raise ValueError("FPDF_FILEACCESS unsigned-long length must fit 32 bits on Windows")
        self.lock = threading.Lock()
        self.error = None
        self.calls = self.bytes = 0

        def read(_param, offset, address, size):
            try:
                if offset + size > self.size:
                    raise EOFError("Read exceeds source")
                target = memoryview((C.c_ubyte * size).from_address(address)).cast("B")
                with self.lock:
                    self.file.seek(offset)
                    done = 0
                    while done < size:
                        count = self.file.readinto(target[done:])
                        if not count:
                            raise EOFError("Short source read")
                        done += count
                    self.calls += 1
                    self.bytes += done
                return 1
            except BaseException as error:
                self.error = type(error).__name__
                return 0

        self.callback = READ(read)
        self.access = FileAccess(self.size, self.callback, None)

    def close(self):
        self.file.close()


class Sink:
    def __init__(self, output):
        self.error = None
        self.calls = self.bytes = 0

        def write(_self, address, size):
            try:
                if self.error:
                    return 0
                # Only the current native block, never an accumulated PDF buffer.
                data = C.string_at(address, size)
                if output.write(data) != size:
                    raise OSError("Short output write")
                self.calls += 1
                self.bytes += size
                return 1
            except BaseException as error:
                self.error = type(error).__name__
                return 0

        self.callback = WRITE(write)
        self.access = FileWrite(1, self.callback)


class Runtime:
    def __init__(self, path):
        if C.sizeof(HANDLE) != 8 or C.sizeof(ULONG) != 4:
            raise RuntimeError("Requires Windows x64 ABI")
        assert C.sizeof(FileAccess) == 24 and FileAccess.read.offset == 8 and FileAccess.param.offset == 16
        assert C.sizeof(FileWrite) == 16 and FileWrite.write.offset == 8 and C.sizeof(Rect) == 16
        self.dll = C.WinDLL(str(Path(path).resolve()))
        self.symbols = []
        pointer = C.POINTER
        signatures = {
            "EPDF_InitThread": (None, []), "EPDF_ShutdownThread": (None, []),
            "EPDF_LoadBaseDocument": (HANDLE, [pointer(FileAccess), C.c_char_p]),
            "EPDF_ReleaseBaseDocument": (None, [HANDLE]),
            "EPDFLayer_OpenLayer": (HANDLE, [HANDLE, pointer(FileAccess), C.c_char_p, pointer(INT)]),
            "EPDFLayer_BeginTransaction": (INT, [HANDLE]),
            "EPDFLayer_CommitTransaction": (INT, [HANDLE]),
            "EPDFLayer_AbortTransaction": (INT, [HANDLE]),
            "EPDFLayer_GetPromotedObjectCount": (ULONG, [HANDLE]),
            "EPDFLayer_SaveDelta": (INT, [HANDLE, pointer(FileWrite), pointer(INT)]),
            "EPDF_SaveAsCopyEx": (INT, [HANDLE, pointer(FileWrite), ULONG, pointer(INT)]),
            "FPDF_LoadCustomDocument": (HANDLE, [pointer(FileAccess), C.c_char_p]),
            "FPDF_CloseDocument": (None, [HANDLE]),
            "FPDF_GetLastError": (ULONG, []), "FPDF_GetPageCount": (INT, [HANDLE]),
            "FPDF_GetSignatureCount": (INT, [HANDLE]), "FPDF_GetSecurityHandlerRevision": (INT, [HANDLE]),
            "FPDF_GetDocPermissions": (ULONG, [HANDLE]),
            "FPDF_LoadPage": (HANDLE, [HANDLE, INT]), "FPDF_ClosePage": (None, [HANDLE]),
            "FPDF_GetPageBoundingBox": (INT, [HANDLE, pointer(Rect)]),
            "FPDFPage_GetRotation": (INT, [HANDLE]),
            "FPDF_GetPageWidthF": (C.c_float, [HANDLE]), "FPDF_GetPageHeightF": (C.c_float, [HANDLE]),
            "FPDFPageObj_NewImageObj": (HANDLE, [HANDLE]),
            "FPDFImageObj_SetBitmap": (INT, [pointer(HANDLE), INT, HANDLE, HANDLE]),
            "FPDFImageObj_SetMatrix": (INT, [HANDLE] + [DOUBLE] * 6),
            "FPDFPage_InsertObject": (INT, [HANDLE, HANDLE]),
            "FPDFPageObj_Destroy": (None, [HANDLE]),
            "FPDFPage_GenerateContent": (INT, [HANDLE]),
            "FPDFBitmap_CreateEx": (HANDLE, [INT, INT, INT, HANDLE, INT]),
            "FPDFBitmap_Destroy": (None, [HANDLE]),
            "FPDFBitmap_GetBuffer": (HANDLE, [HANDLE]), "FPDFBitmap_GetStride": (INT, [HANDLE]),
            "FPDFBitmap_FillRect": (INT, [HANDLE, INT, INT, INT, INT, ULONG]),
            "FPDF_RenderPageBitmap": (None, [HANDLE, HANDLE, INT, INT, INT, INT, INT, INT]),
            "FPDF_PageToDevice": (INT, [HANDLE, INT, INT, INT, INT, INT, DOUBLE, DOUBLE, pointer(INT), pointer(INT)]),
        }
        for name, (result, arguments) in signatures.items():
            function = getattr(self.dll, name)
            function.restype, function.argtypes = result, arguments
            setattr(self, name, function)
            self.symbols.append(name)


def check(value, operation):
    if not value:
        raise RuntimeError(operation)
    return value
