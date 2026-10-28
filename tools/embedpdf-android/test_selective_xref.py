"""Focused C API writer tests. Runs outside Android and never in Guardar.

Uses the real patched creator, an independent parser, and old/modern PDFium
renderers. All committed/generated fixtures are synthetic, including the image.
Optional --real-inputs reads existing PDFs without changing their bytes.
"""
import argparse
import ctypes as C
import hashlib
import io
import json
import os
from pathlib import Path
import re
import urllib.request
import zipfile
import zlib

from pypdf import PdfReader
from pypdf.generic import IndirectObject

H, I, U = C.c_void_p, C.c_int, C.c_ulong
READ = C.CFUNCTYPE(I, H, U, H, U)
WRITE = C.CFUNCTYPE(I, H, H, U)


class Access(C.Structure):
    _fields_ = [('length', U), ('read', READ), ('param', H)]


class Writer(C.Structure):
    _fields_ = [('version', I), ('write', WRITE)]


class API:
    def __init__(self, path, writer=False):
        self.path = Path(path)
        self.dll = C.CDLL(str(self.path.resolve()))
        functions = {
            'FPDF_LoadMemDocument64': (H, [H, C.c_size_t, C.c_char_p]),
            'FPDF_CloseDocument': (None, [H]), 'FPDF_LoadPage': (H, [H, I]),
            'FPDF_ClosePage': (None, [H]), 'FPDF_GetPageCount': (I, [H]),
            'FPDFPage_CountObjects': (I, [H]),
            'FPDFBitmap_Create': (H, [I, I, I]), 'FPDFBitmap_Destroy': (None, [H]),
            'FPDFBitmap_GetBuffer': (H, [H]), 'FPDFBitmap_GetStride': (I, [H]),
            'FPDFBitmap_FillRect': (I, [H, I, I, I, I, U]),
            'FPDF_RenderPageBitmap': (None, [H, H, I, I, I, I, I, I]),
        }
        if writer:
            functions.update({
                'EPDF_InitThread': (None, []), 'EPDF_ShutdownThread': (None, []),
                'EPDF_LoadBaseDocument': (H, [C.POINTER(Access), C.c_char_p]),
                'EPDF_ReleaseBaseDocument': (None, [H]),
                'EPDFLayer_OpenLayer': (H, [H, C.POINTER(Access), C.c_char_p, C.POINTER(I)]),
                'EPDFLayer_BeginTransaction': (I, [H]), 'EPDFLayer_CommitTransaction': (I, [H]),
                'EPDFLayer_SaveDelta': (I, [H, C.POINTER(Writer), C.POINTER(I)]),
                'FPDFPageObj_NewImageObj': (H, [H]),
                'FPDFImageObj_SetBitmap': (I, [C.POINTER(H), I, H, H]),
                'FPDFImageObj_SetMatrix': (I, [H] + [C.c_double] * 6),
                'FPDFPage_InsertObject': (I, [H, H]), 'FPDFPage_GenerateContent': (I, [H]),
            })
        else:
            functions['FPDF_InitLibrary'] = (None, [])
            functions['FPDF_DestroyLibrary'] = (None, [])
        for name, (result, args) in functions.items():
            fn = getattr(self.dll, name)
            fn.restype, fn.argtypes = result, args
            setattr(self, name, fn)

    def render(self, data, index=0):
        buffer = C.create_string_buffer(data)
        doc = self.FPDF_LoadMemDocument64(buffer, len(data), None)
        assert doc, 'Renderer failed to open document'
        page = bitmap = None
        try:
            page = self.FPDF_LoadPage(doc, index)
            assert page
            count = self.FPDFPage_CountObjects(page)
            bitmap = self.FPDFBitmap_Create(200, 200, 1)
            assert bitmap
            self.FPDFBitmap_FillRect(bitmap, 0, 0, 200, 200, 0xffffffff)
            self.FPDF_RenderPageBitmap(bitmap, page, 0, 0, 200, 200, 0, 0)
            return C.string_at(self.FPDFBitmap_GetBuffer(bitmap), self.FPDFBitmap_GetStride(bitmap) * 200), count
        finally:
            if bitmap:
                self.FPDFBitmap_Destroy(bitmap)
            if page:
                self.FPDF_ClosePage(page)
            self.FPDF_CloseDocument(doc)

    def sign(self, original, second=False):
        """Own all callback buffers/handles through close; return delta only."""
        errors, blocks = [], []

        @READ
        def read(_param, offset, address, size):
            if offset + size > len(original):
                errors.append('out-of-range read')
                return 0
            C.memmove(address, original[offset:offset + size], size)
            return 1

        @WRITE
        def write(_self, address, size):
            blocks.append(C.string_at(address, size))
            return 1

        access, sink, status = Access(len(original), read, None), Writer(1, write), I(-1)
        base = layer = page = bitmap = None
        try:
            base = self.EPDF_LoadBaseDocument(C.byref(access), None)
            assert base
            layer = self.EPDFLayer_OpenLayer(base, None, None, C.byref(status))
            assert layer and status.value == 0, status.value
            assert self.EPDFLayer_BeginTransaction(layer)
            page = self.FPDF_LoadPage(layer, 0)
            assert page
            before_count = self.FPDFPage_CountObjects(page)
            bitmap = self.FPDFBitmap_Create(16, 8, 1)
            assert bitmap
            self.FPDFBitmap_FillRect(bitmap, 0, 0, 16, 8, 0)
            self.FPDFBitmap_FillRect(bitmap, 2, 2, 12, 2, 0xc0204080)
            self.FPDFBitmap_FillRect(bitmap, 4, 4, 2, 3, 0xffff3020)
            image = self.FPDFPageObj_NewImageObj(layer)
            assert image
            assert self.FPDFImageObj_SetBitmap(None, 0, image, bitmap)
            assert self.FPDFImageObj_SetMatrix(image, 30, 0, 0, 15, 55 if second else 15, 30)
            assert self.FPDFPage_InsertObject(page, image)
            assert self.FPDFPage_GenerateContent(page)
            self.FPDF_ClosePage(page)
            page = None
            assert self.EPDFLayer_CommitTransaction(layer)
            assert self.EPDFLayer_SaveDelta(layer, C.byref(sink), C.byref(status))
            assert status.value == 0, status.value
            assert not errors, errors
            delta = b''.join(blocks)
            assert delta and not delta.startswith(b'%PDF-'), 'SaveDelta must return only a tail'
            return delta, before_count
        finally:
            if page:
                self.FPDF_ClosePage(page)
            if bitmap:
                self.FPDFBitmap_Destroy(bitmap)
            if layer:
                self.FPDF_CloseDocument(layer)
            if base:
                self.EPDF_ReleaseBaseDocument(base)


def stream(data, fields=b''):
    return b'<< /Length ' + str(len(data)).encode() + fields + b' >>\nstream\n' + data + b'\nendstream'


class Fixture:
    def __init__(self):
        self.data = bytearray(b'%PDF-1.7\n%\xe2\xe3\xcf\xd3\n')
        self.entries = {0: (0, 0, 65535)}

    def obj(self, n, body, gen=0):
        self.entries[n] = (1, len(self.data), gen)
        self.data.extend(f'{n} {gen} obj\n'.encode() + body + b'\nendobj\n')

    def table(self, prev=None, hybrid=None):
        pos = len(self.data)
        if prev is None and hybrid is None:
            self.data.extend(b'xref\n0 10\n')
            for n in range(10):
                kind, offset, gen = self.entries.get(n, (0, 0, 0))
                self.data.extend(f'{offset:010d} {gen:05d} {"n" if kind else "f"} \n'.encode())
        else:
            self.data.extend(b'xref\n0 1\n0000000000 65535 f \n')
            for n, (kind, offset, gen) in sorted(self.entries.items()):
                if kind == 1:
                    self.data.extend(f'{n} 1\n{offset:010d} {gen:05d} n \n'.encode())
        trailer = b' /Root 1 7 R /Info 6 259 R /ID [<1234><5678>] /Size 10'
        if prev is not None:
            trailer += f' /Prev {prev}'.encode()
        if hybrid is not None:
            trailer += f' /XRefStm {hybrid}'.encode()
        self.data.extend(b'trailer\n<<' + trailer + b' >>\n' + f'startxref\n{pos}\n%%EOF\n'.encode())
        return pos

    def xref(self, finish=True, only=None):
        pos = len(self.data)
        self.entries[8] = (1, pos, 0)
        entries = ({n: self.entries.get(n, (0, 0, 0)) for n in range(10)} if only is None else
                   {n: v for n, v in self.entries.items() if n in only})
        fields = b' /Type /XRef /W [1 4 2] /Root 1 7 R /Info 6 259 R /ID [<1234><5678>] /Size 10 /Index ['
        fields += b' '.join(f'{n} 1'.encode() for n in sorted(entries)) + b']'
        payload = b''.join(bytes([kind]) + offset.to_bytes(4, 'big') + gen.to_bytes(2, 'big')
                           for n, (kind, offset, gen) in sorted(entries.items()))
        self.obj(8, stream(payload, fields))
        if finish:
            self.data.extend(f'startxref\n{pos}\n%%EOF\n'.encode())
        return pos


def fixture(kind):
    f = Fixture()
    compressed = kind in ('objstm', 'hybrid')
    page_gen = 0 if compressed else 513
    page = b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources 5 0 R /Contents 4 0 R >>'
    resources = b'<< /ExtGState << /OriginalState 9 0 R >> >>'
    f.obj(1, b'<< /Type /Catalog /Pages 2 0 R >>', 7)
    f.obj(2, f'<< /Type /Pages /Kids [3 {page_gen} R] /Count 1 >>'.encode())
    f.obj(3, page, page_gen)
    f.obj(4, stream(b'q 0.2 0.6 0.2 rg 5 5 8 8 re f Q\n'))
    f.obj(5, resources)
    f.obj(6, b'<< /Producer (Prueba sintetica de referencias) >>', 259)
    f.obj(9, b'<< /Type /ExtGState /CA 1 >>')
    if kind == 'classic':
        f.table()
    elif kind == 'stream':
        f.xref()
    else:
        prev = f.table() if kind == 'hybrid' else None
        header = f'3 0 5 {len(page) + 1} 9 {len(page) + len(resources) + 2} '.encode()
        payload = header + page + b' ' + resources + b' << /Type /ExtGState /CA 1 >>'
        f.obj(7, stream(zlib.compress(payload), f' /Type /ObjStm /N 3 /First {len(header)} /Filter /FlateDecode'.encode()))
        f.entries[3], f.entries[5] = (2, 7, 0), (2, 7, 1)
        f.entries[9] = (2, 7, 2)
        pos = f.xref(finish=kind != 'hybrid', only={3, 5, 7, 8, 9} if kind == 'hybrid' else None)
        if kind == 'hybrid':
            # A true hybrid update, with compressed entries only in /XRefStm.
            f.table(prev=prev, hybrid=pos)
    return bytes(f.data), page_gen


def startxref(data):
    return int(re.search(rb'startxref\s+(\d+)\s+%%EOF\s*$', data).group(1))


def verify_structure(original, delta, expect_stream, page_gen):
    final = original + delta
    old, result = PdfReader(io.BytesIO(original), strict=True), PdfReader(io.BytesIO(final), strict=True)
    xref = startxref(final)
    assert xref >= len(original)
    assert result.trailer['/Prev'] == startxref(original)
    assert result.trailer.raw_get('/Root') == old.trailer.raw_get('/Root') or (
        result.trailer.raw_get('/Root').idnum, result.trailer.raw_get('/Root').generation) == (1, 7)
    assert result.trailer.raw_get('/Info').generation == old.trailer.raw_get('/Info').generation
    assert result.trailer['/ID'][0] == old.trailer['/ID'][0]
    assert result.trailer['/Size'] >= old.trailer['/Size']
    assert len(result.pages) == len(old.pages)
    assert result.pages[0].indirect_reference.generation == page_gen
    assert len(result.pages[0].images) > len(old.pages[0].images)
    # Resolve every indexed original object, including untouched ObjStm members.
    for gen, entries in old.xref.items():
        for number in entries:
            if number:
                assert result.get_object(IndirectObject(number, gen, result)) is not None
    for number in old.xref_objStm:
        assert result.get_object(IndirectObject(number, 0, result)) is not None
    if 9 in old.xref_objStm:
        assert result.xref_objStm[9] == old.xref_objStm[9], 'Untouched compressed resource must still resolve from the original ObjStm'
        assert b'9 0 obj' not in delta
    assert original[:startxref(original)] in final  # Bytes copied without rewriting.
    if expect_stream:
        header = re.match(rb'(\d+) 0 obj\s*<<', final[xref:])
        assert header, 'startxref must point exactly to the new stream object'
        n = int(header.group(1))
        assert n == result.trailer['/Size'] - 1
        obj = result.get_object(IndirectObject(n, 0, result))
        assert obj['/Type'] == '/XRef' and list(obj['/W']) == [0, 4, 2]
        index = list(obj['/Index'])
        assert len(index) % 2 == 0
        numbers = [number for begin, count in zip(index[::2], index[1::2]) for number in range(begin, begin + count)]
        payload = obj.get_data()
        assert len(payload) == len(numbers) * 6
        # Validate raw /Length too, independently of parser stream decoding.
        raw_header = final[xref:final.index(b'stream\r\n', xref)]
        assert int(re.search(rb'/Length\s+(\d+)', raw_header).group(1)) == len(payload)
        own = False
        for i, number in enumerate(numbers):
            offset = int.from_bytes(payload[6*i:6*i+4], 'big')
            gen = int.from_bytes(payload[6*i+4:6*i+6], 'big')
            assert len(original) <= offset < len(final)
            assert final[offset:].startswith(f'{number} {gen} obj'.encode())
            if number == n:
                assert offset == xref and gen == 0
                own = True
        assert own
    else:
        assert final[xref:].startswith(b'xref\r\n')
        assert b'/XRefStm' not in delta, 'New table must not replay the old hybrid stream'
    # Selection stays small and never repeats the original content stream.
    assert b'q 0.2 0.6 0.2 rg 5 5 8 8 re f Q' not in delta
    return {'xref_format': 'stream' if expect_stream else 'table', 'startxref': xref,
            'prev': startxref(original), 'delta_bytes': len(delta), 'size': result.trailer['/Size']}


def download_readers(output):
    readers, evidence = [], []
    for version in ('1.0.0', '4.30.0'):
        metadata = json.load(urllib.request.urlopen('https://pypi.org/pypi/pypdfium2/' + version + '/json', timeout=60))
        wheels = [x for x in metadata['urls'] if x['filename'].endswith('.whl')
                  and 'manylinux' in x['filename'] and 'x86_64' in x['filename']]
        assert len(wheels) == 1, [w['filename'] for w in wheels]
        wheel = wheels[0]
        data = urllib.request.urlopen(wheel['url'], timeout=60).read()
        assert hashlib.sha256(data).hexdigest() == wheel['digests']['sha256']
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            # The 1.0.0 Linux wheel calls its ELF simply "pdfium".
            names = [n for n in archive.namelist() if Path(n).name in ('libpdfium.so', 'pdfium')]
            assert len(names) == 1
            native = archive.read(names[0])
            assert native.startswith(b'\x7fELF')
            versions = {n: archive.read(n).decode() for n in archive.namelist()
                        if n.endswith(('version.json', 'version.py'))}
        if version == '1.0.0':
            assert any('V_LIBPDFIUM = 4943' in v for v in versions.values())
        path = output / ('reader-' + version + '.so')
        path.write_bytes(native)
        readers.append((version, API(path)))
        evidence.append({'pypdfium2': version, 'wheel': wheel['filename'], 'url': wheel['url'],
                         'wheel_sha256': wheel['digests']['sha256'],
                         'native_sha256': hashlib.sha256(native).hexdigest(), 'version_metadata': versions})
    return readers, evidence


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--engine', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--download-readers', action='store_true')
    p.add_argument('--real-inputs', type=Path)
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    api = API(a.engine, writer=True)
    readers, evidence = download_readers(a.output) if a.download_readers else ([], [])
    api.EPDF_InitThread()
    for _, reader in readers:
        reader.FPDF_InitLibrary()
    report = {'engine_sha256': hashlib.sha256(a.engine.read_bytes()).hexdigest(),
              'readers': evidence, 'cases': []}
    try:
        for kind in ('classic', 'stream', 'hybrid', 'objstm'):
            original, generation = fixture(kind)
            source_reader = PdfReader(io.BytesIO(original), strict=True)
            assert bool(source_reader.xref_objStm) == (kind in ('hybrid', 'objstm'))
            for revision in (1, 2):
                delta, previous_count = api.sign(original, second=revision == 2)
                row = verify_structure(original, delta, kind in ('stream', 'objstm'), generation)
                row.update(case=kind, revision=revision, renderers=[])
                final = original + delta
                for label, reader in [('patched', api), *readers]:
                    before, count_before = reader.render(original)
                    after, count_after = reader.render(final)
                    assert count_after == count_before + 1, (kind, revision, label, count_before, count_after)
                    assert before != after, (kind, revision, label, 'Signature invisible')
                    # Original pixels outside the newly placed image stay exact.
                    left = 110 if revision == 2 else 30
                    changed = [i // 4 for i in range(0, len(before), 4) if before[i:i+4] != after[i:i+4]]
                    assert all(left - 2 <= pixel % 200 < left + 62 and 108 <= pixel // 200 < 142 for pixel in changed)
                    row['renderers'].append({'reader': label, 'objects_before': count_before,
                                             'objects_after': count_after,
                                             'changed_pixels': sum(before[i:i+4] != after[i:i+4] for i in range(0, len(before), 4))})
                (a.output / f'{kind}-revision-{revision}.pdf').write_bytes(final)
                report['cases'].append(row)
                print(json.dumps(row), flush=True)
                original = final
        if a.real_inputs:
            for name in ('Galaxia.pdf', 'Estrellas.pdf', 'Cadena Alimenticia.pdf'):
                path = a.real_inputs / name
                original = path.read_bytes()
                delta, _ = api.sign(original)
                final = original + delta
                row = {'case': name, 'source_sha256': hashlib.sha256(original).hexdigest(),
                       'delta_bytes': len(delta), 'renderers': []}
                for label, reader in [('patched', api), *readers]:
                    before, n0 = reader.render(original)
                    after, n1 = reader.render(final)
                    assert n1 == n0 + 1 and before != after, (name, label, n0, n1)
                    row['renderers'].append({'reader': label, 'objects_before': n0, 'objects_after': n1})
                assert hashlib.sha256(path.read_bytes()).hexdigest() == row['source_sha256']
                (a.output / (path.stem + '-writer-test.pdf')).write_bytes(final)
                report['cases'].append(row)
        report['passed'] = True
    finally:
        for _, reader in readers:
            reader.FPDF_DestroyLibrary()
        api.EPDF_ShutdownThread()
        (a.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
