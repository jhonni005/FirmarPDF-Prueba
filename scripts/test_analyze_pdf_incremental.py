"""Controlled synthetic PDFs for the external analyzer; never runs Android/PDFium."""

import hashlib
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zlib

from analyze_pdf_incremental import analyze, main


def stream(payload, fields=b"", *, flate=False, level=6):
    data = zlib.compress(payload, level) if flate else payload
    return (b"<< /Length " + str(len(data)).encode() + fields +
            (b" /Filter /FlateDecode" if flate else b"") + b" >>\nstream\n" + data + b"\nendstream")


class Fixture:
    def __init__(self, prefix=b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n"):
        self.data = bytearray(prefix)
        self.entries = {}

    def obj(self, number, body, generation=0):
        self.entries[number] = (1, len(self.data), generation)
        self.data.extend(f"{number} {generation} obj\n".encode() + body + b"\nendobj\n")

    def compressed(self, number, container, body):
        header = f"{number} 0 ".encode()
        self.obj(container, stream(header + body, f" /Type /ObjStm /N 1 /First {len(header)}".encode(), flate=True))
        self.entries[number] = (2, container, 0)

    def table(self, size, *, prev=None, hybrid=None):
        offset = len(self.data)
        self.data.extend(b"xref\n")
        entries = {0: (0, 0, 65535), **self.entries}
        for number, (kind, position, generation) in sorted(entries.items()):
            if kind == 2:
                continue
            self.data.extend(f"{number} 1\n{position:010d} {generation:05d} {'n' if kind else 'f'} \n".encode())
        trailer = f"trailer\n<< /Size {size} /Root 1 0 R".encode()
        if prev is not None:
            trailer += f" /Prev {prev}".encode()
        if hybrid is not None:
            trailer += f" /XRefStm {hybrid}".encode()
        self.data.extend(trailer + b" >>\n" + f"startxref\n{offset}\n%%EOF\n".encode())
        return offset

    def xref_stream(self, number, size, *, prev=None, finish=True, only=None):
        offset = len(self.data)
        entries = {0: (0, 0, 65535), **self.entries, number: (1, offset, 0)}
        if only is not None:
            entries = {key: value for key, value in entries.items() if key in only or key == number}
        indexes = b" ".join(f"{n} 1".encode() for n in sorted(entries))
        data = b"".join(bytes([kind]) + second.to_bytes(8, "big") + third.to_bytes(2, "big")
                        for _, (kind, second, third) in sorted(entries.items()))
        fields = f" /Type /XRef /Root 1 0 R /Size {size} /W [1 8 2]".encode() + b" /Index [" + indexes + b"]"
        if prev is not None:
            fields += f" /Prev {prev}".encode()
        self.obj(number, stream(data, fields, flate=True))
        if finish:
            self.data.extend(f"startxref\n{offset}\n%%EOF\n".encode())
        return offset


CONTENT = b"q 16 0 0 1 0 0 cm /Im0 Do Q\n"
IMAGE = b"\x00\xff\x01endstream\n999 0 obj\n" * 3  # Binary payload must not be scanned for objects.
IMAGE_FIELDS = f" /Type /XObject /Subtype /Image /Width {len(IMAGE)//3} /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8".encode()


def base_fixture(*, compressed=False, hybrid=False):
    f = Fixture()
    f.obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    f.obj(2, b"<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>")
    f.obj(3, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>")
    f.obj(4, stream(CONTENT, flate=True, level=0))
    f.obj(5, stream(IMAGE, IMAGE_FIELDS))
    f.obj(6, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 7 0 R >>")
    f.obj(7, stream(b"q Q\n"))
    if compressed:
        f.compressed(8, 9, b"<< /Type /ExtGState /CA 1 >>")
        xref = f.xref_stream(10, 11, finish=not hybrid, only={8} if hybrid else None)
        if hybrid:
            xref = f.table(11, hybrid=xref)
    else:
        f.obj(8, b"<< /Type /ExtGState /CA 1 >>", generation=2)
        xref = f.table(9)
    return f, xref


class AnalyzerTests(unittest.TestCase):
    def setUp(self):
        parent = Path(__file__).resolve().parents[1] / ".gradle/pdf-diagnostics"
        parent.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="controlled-", dir=parent)
        self.root = Path(self.directory.name).resolve()
        assert self.root.parent == parent.resolve()  # Cleanup stays within our generated workspace directory.
        self.addCleanup(self.directory.cleanup)

    def compare(self, original, result, **options):
        a, b = self.root / "original.pdf", self.root / "incremental.pdf"
        a.write_bytes(original)
        b.write_bytes(result)
        before = [hashlib.sha256(p.read_bytes()).digest() for p in (a, b)]
        report = analyze(a, b, **options)
        self.assertEqual(before, [hashlib.sha256(p.read_bytes()).digest() for p in (a, b)])
        return report

    def test_table_repeated_image_recompression_new_signature_and_generation(self):
        original, prev = base_fixture()
        output = Fixture(original.data)
        output.obj(3, b"<< /Type /Page /Parent 2 0 R /Contents [4 0 R 10 0 R] >>")
        output.obj(4, stream(CONTENT, flate=True, level=9))
        output.obj(5, stream(IMAGE, IMAGE_FIELDS))
        output.obj(8, b"<< /Type /ExtGState /CA 0.5 >>", generation=3)
        output.obj(9, stream(b"\xff\x00\x00", b" /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8"))
        output.obj(10, stream(b"q Q\n"))
        output.table(11, prev=prev)
        r = self.compare(original.data, output.data, decoded=True)
        self.assertTrue(r["complete"])
        self.assertTrue(r["original_is_exact_prefix"])
        self.assertTrue(r["original_final_xref_in_result_chain"])
        self.assertEqual(r["result_xref_chain"][0]["prev"], prev)
        self.assertEqual(r["counts"]["new"], 2)
        self.assertEqual(r["counts"]["redefined"], 4)
        rows = {x["object"]: x for x in r["objects"]}
        self.assertNotIn(7, rows)  # Untouched stream not counted as rewritten.
        self.assertEqual(rows[8]["generation"], 3)
        self.assertEqual(rows[8]["previous_generation"], 2)
        self.assertTrue(rows[5]["same_object_encoded_equal"])
        self.assertFalse(rows[4]["same_object_encoded_equal"])
        self.assertTrue(rows[4]["same_object_decoded_equal"])
        self.assertEqual(rows[4]["type"], "PageContent")
        self.assertEqual(rows[9]["encoded_matches_original_count"], 0)
        self.assertGreaterEqual(r["growth"]["repeated_encoded_payload_bytes"], len(IMAGE))

    def test_xref_stream_and_object_stream(self):
        original, prev = base_fixture(compressed=True)
        output = Fixture(original.data)
        output.obj(5, stream(IMAGE, IMAGE_FIELDS))
        output.compressed(8, 11, b"<< /Type /ExtGState /CA 0.5 >>")
        output.xref_stream(12, 13, prev=prev)
        r = self.compare(original.data, output.data)
        self.assertTrue(r["complete"])
        self.assertEqual([s["kind"] for s in r["result_xref_chain"]], ["stream", "stream"])
        row = next(x for x in r["objects"] if x["object"] == 8)
        self.assertEqual(row["type"], "ExtGState")
        self.assertEqual(row["storage"]["kind"], "compressed")
        self.assertEqual(row["status"], "redefined")

    def test_hybrid_xref(self):
        original, prev = base_fixture(compressed=True, hybrid=True)
        output = Fixture(original.data)
        output.obj(5, stream(IMAGE, IMAGE_FIELDS))
        output.table(11, prev=prev)
        r = self.compare(original.data, output.data)
        self.assertTrue(r["complete"])
        self.assertEqual(r["original_xref_chain"][0]["kind"], "hybrid")
        self.assertEqual(r["counts"]["original_active_objects"], 10)

    def test_multiple_revisions_and_freed_entry(self):
        original, prev = base_fixture()
        first = Fixture(original.data)
        first.obj(5, stream(IMAGE, IMAGE_FIELDS))
        prev = first.table(9, prev=prev)
        last = Fixture(first.data)
        last.obj(5, stream(b"\x00\x01\x02", IMAGE_FIELDS))
        last.entries[8] = (0, 0, 3)
        last.table(9, prev=prev)
        r = self.compare(original.data, last.data)
        self.assertEqual(len(r["result_xref_chain"]), 3)
        self.assertEqual(r["counts"]["result_active_objects"], 7)
        self.assertEqual(r["counts"]["redefined"], 1)
        self.assertGreater(r["growth"]["other_or_superseded_bytes"], len(IMAGE))

    def test_non_prefix_and_decode_limit(self):
        original, _ = base_fixture()
        different, _ = base_fixture()
        # Valid stand-alone PDF with different payload; no shared-prefix assumption.
        different.data = different.data.replace(b"/CA 1", b"/CA 2")
        r = self.compare(original.data, different.data)
        self.assertFalse(r["original_is_exact_prefix"])
        self.assertIsNone(r["growth"]["active_appended_encoded_stream_bytes"])
        original, prev = base_fixture()
        output = Fixture(original.data)
        output.obj(11, stream(b"a" * (2 * 1024 * 1024), flate=True))
        output.table(12, prev=prev)
        r = self.compare(original.data, output.data, decoded=True, decode_limit_mib=1)
        row = next(x for x in r["objects"] if x["object"] == 11)
        self.assertEqual(row["decoded_status"], "LimitReachedError")
        self.assertNotIn("decoded_sha256", row)
        self.assertFalse(r["decoded_comparison_complete"])

    def test_command_line_privacy_report_and_refusal_to_overwrite(self):
        original, prev = base_fixture()
        output = Fixture(original.data)
        output.obj(5, stream(IMAGE, IMAGE_FIELDS))
        output.table(9, prev=prev)
        a, b, report = self.root / "private-original.pdf", self.root / "private-result.pdf", self.root / "report.json"
        a.write_bytes(original.data)
        b.write_bytes(output.data)
        arguments = ["analyze_pdf_incremental.py", str(a), str(b), "--decoded", "--report", str(report)]
        with patch.object(sys, "argv", arguments), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(), 0)
        text = report.read_text()
        self.assertTrue(json.loads(text)["complete"])
        self.assertNotIn("private-original", text)
        self.assertNotIn("private-result", text)
        self.assertNotIn("endstream", text)
        with patch.object(sys, "argv", arguments), contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(main(), 2)  # Existing reports/inputs never overwritten.
        self.assertNotIn(str(self.root), errors.getvalue())
        self.assertEqual(report.read_text(), text)
        invalid = self.root / "malformed.pdf"
        invalid.write_bytes(b"%PDF-1.7\ninvalid")
        with patch.object(sys, "argv", ["analyzer", str(a), str(invalid)]), contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(main(), 2)
        self.assertNotIn("malformed.pdf", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
