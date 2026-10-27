"""Read-only structural comparison. No PDF text, names, images or paths in reports.

Requires the pinned pypdf version in requirements-pdf-diagnostics.txt. Also accepts
an isolated installation in .gradle/pdf-diagnostics/python (no global install).
Uses PDF xref entries and pypdf's object/stream parser, never object regex scans.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import logging
from pathlib import Path
import sys

try:
    import pypdf
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".gradle/pdf-diagnostics/python"))
    try:
        import pypdf
    except ImportError:
        raise SystemExit("Falta pypdf: instalar scripts/requirements-pdf-diagnostics.txt.") from None

if getattr(pypdf, "__version__", None) != "6.21.2":
    raise SystemExit("Este analizador requiere pypdf==6.21.2; use un entorno aislado.")

from pypdf import PdfReader, apply_configuration
from pypdf._utils import read_non_whitespace
from pypdf.generic import ArrayObject, DictionaryObject, IndirectObject, StreamObject, read_object

# Parser diagnostics can include dictionary values. Emit only our sanitized errors.
logging.getLogger("pypdf").disabled = True
logging.getLogger("pypdf").addHandler(logging.NullHandler())
logging.getLogger("pypdf").propagate = False

CHUNK = 1024 * 1024
STANDARD_TYPES = {"/Catalog", "/Pages", "/Page", "/Font", "/FontDescriptor", "/ExtGState",
                  "/XRef", "/ObjStm", "/Metadata", "/XObject", "/Annot"}


def ref_key(value):
    return (value.idnum, value.generation) if isinstance(value, IndirectObject) else None


def ref_json(key):
    return {"object": key[0], "generation": key[1]}


def prefix_equal(original, result):
    original.seek(0)
    result.seek(0)
    while block := original.read(CHUNK):
        if block != result.read(len(block)):
            return False
    return True


def next_byte(stream):
    value = read_non_whitespace(stream)
    if not value:
        raise ValueError("Truncated structure")
    stream.seek(-1, 1)
    return value


def integer(stream, reader):
    next_byte(stream)
    value = read_object(stream, reader)
    if not isinstance(value, int) or value < 0:
        raise ValueError("Invalid structural integer")
    return int(value)


def xref_section(reader, offset):
    """Parse a single section at its declared offset, including hybrid /XRefStm.

    Table tokens are structural numbers/flags. Dictionaries and binary streams
    are parsed by pypdf; /W and /Index decode PDF 1.5 xref entries, including ObjStm.
    """
    stream = reader.stream
    stream.seek(offset)
    first = next_byte(stream)
    entries = {}
    if first == b"x":
        if stream.read(4) != b"xref":
            raise ValueError("Invalid xref table")
        while next_byte(stream) != b"t":
            start, count = integer(stream, reader), integer(stream, reader)
            if count > 2_000_000:
                raise ValueError("Excessive xref count")
            for number in range(start, start + count):
                position, generation = integer(stream, reader), integer(stream, reader)
                next_byte(stream)
                flag = stream.read(1)
                if flag not in (b"n", b"f"):
                    raise ValueError("Invalid xref entry")
                entries[number] = {"generation": generation, "kind": "direct" if flag == b"n" else "free",
                                   "offset": position}
        if stream.read(7) != b"trailer":
            raise ValueError("Missing trailer")
        next_byte(stream)
        trailer = read_object(stream, reader)
        kind = "table"
    else:
        reader.read_object_header(stream)
        trailer = read_object(stream, reader)
        if not isinstance(trailer, StreamObject) or trailer.get("/Type") != "/XRef":
            raise ValueError("Not an xref stream")
        widths = [int(item) for item in trailer["/W"]]
        index = [int(item) for item in trailer.get("/Index", [0, trailer["/Size"]])]
        if len(widths) != 3 or any(w < 0 or w > 8 for w in widths) or len(index) % 2:
            raise ValueError("Invalid xref stream layout")
        data, cursor = trailer.get_data(), 0
        for start, count in zip(index[::2], index[1::2]):
            if start < 0 or count < 0 or count > 2_000_000:
                raise ValueError("Invalid xref subsection")
            for number in range(start, start + count):
                fields = []
                for field, width in enumerate(widths):
                    if cursor + width > len(data):
                        raise ValueError("Truncated xref stream")
                    fields.append(int.from_bytes(data[cursor:cursor + width], "big") if width else int(field == 0))
                    cursor += width
                mode, second, third = fields
                if mode in (0, 1):
                    entries[number] = {"generation": third, "kind": "direct" if mode == 1 else "free",
                                       "offset": second}
                elif mode == 2:
                    entries[number] = {"generation": 0, "kind": "compressed", "object_stream": second,
                                       "index": third}
                else:
                    raise ValueError("Unsupported xref entry type")
        kind = "stream"
    if not isinstance(trailer, DictionaryObject):
        raise ValueError("Invalid trailer")
    prev = trailer.get("/Prev")
    hybrid = trailer.get("/XRefStm")
    if hybrid is not None:
        hybrid = int(hybrid)
        if hybrid == offset or kind != "table":
            raise ValueError("Invalid hybrid reference")
        extra = xref_section(reader, hybrid)
        if extra[0]["kind"] != "stream":
            raise ValueError("Invalid hybrid stream")
        entries.update(extra[1])
        kind = "hybrid"
    summary = {"offset": offset, "kind": kind, "prev": int(prev) if prev is not None else None,
               "hybrid_xref_stream": hybrid, "entries": len(entries),
               "entry_kinds": dict(Counter(e["kind"] for e in entries.values()))}
    reader.resolved_objects.clear()
    return summary, entries


def revision_chain(reader):
    # Pinned parser API: _startxref is the actual final startxref, not the merged trailer.
    offset = reader._startxref
    seen, sections, active = set(), [], {}
    while offset is not None:
        if offset in seen or offset <= 0 or offset >= reader.stream.seek(0, 2) or len(seen) > 10000:
            raise ValueError("Invalid revision chain")
        seen.add(offset)
        section, entries = xref_section(reader, offset)
        sections.append(section)
        for number, entry in entries.items():
            active.setdefault(number, entry)  # Includes free tombstones: never resurrect deleted objects.
        offset = section["prev"]
    return sections, {n: e for n, e in active.items() if n and e["kind"] != "free"}


def child_refs(value):
    if isinstance(value, IndirectObject):
        return [ref_key(value)]
    if isinstance(value, ArrayObject):
        return [key for item in value for key in child_refs(item)]
    return []


def describe_object(obj, decoded):
    row = {"type": "dictionary" if isinstance(obj, DictionaryObject) else
           "array" if isinstance(obj, ArrayObject) else "scalar"}
    if not isinstance(obj, DictionaryObject):
        return row
    type_name = obj.get("/Type")
    if type_name in STANDARD_TYPES:
        row["type"] = str(type_name)[1:]
    if not isinstance(obj, StreamObject):
        return row
    subtype = obj.get("/Subtype")
    row["type"] = "Image" if subtype == "/Image" else "Form" if subtype == "/Form" else row["type"]
    if row["type"] == "dictionary":
        row["type"] = "other-stream"
    # _data is the encoded stream payload, excluding delimiters; pinned parser internals.
    data = obj._data
    row.update(encoded_bytes=len(data), encoded_sha256=hashlib.sha256(data).hexdigest())
    if decoded:
        filters = obj.get("/Filter", [])
        filters = list(filters) if isinstance(filters, ArrayObject) else [filters]
        # DCT/JPX/JBIG2 hashes are NOT advertised as decoded pixels. Optional decode
        # covers unfiltered/Flate only, including PDF predictors handled by pypdf.
        if any(f != "/FlateDecode" for f in filters):
            row["decoded_status"] = "unsupported-filter"
        else:
            try:
                payload = obj.get_data()
                row.update(decoded_bytes=len(payload), decoded_sha256=hashlib.sha256(payload).hexdigest(),
                           decoded_status="ok")
                del payload
            except Exception as error:
                row["decoded_status"] = type(error).__name__
            finally:
                if hasattr(obj, "decoded_self"):
                    obj.decoded_self = None
    return row


def inventory(reader, entries, decoded):
    rows, content_roots, font_files, arrays = {}, set(), set(), {}
    for number, entry in sorted(entries.items()):
        key = number, entry["generation"]
        try:
            obj = reader.get_object(IndirectObject(*key, reader))
            row = describe_object(obj, decoded)
            if isinstance(obj, DictionaryObject):
                if obj.get("/Type") == "/Page" and "/Contents" in obj:
                    content_roots.update(child_refs(obj.raw_get("/Contents")))
                if obj.get("/Type") == "/FontDescriptor":
                    for name in ("/FontFile", "/FontFile2", "/FontFile3"):
                        if name in obj:
                            font_files.update(child_refs(obj.raw_get(name)))
            elif isinstance(obj, ArrayObject):
                arrays[key] = child_refs(obj)
            del obj
        except Exception as error:
            row = {"type": "unreadable", "error": type(error).__name__}
        rows[key] = {**ref_json(key), **row}
        # Release encoded AND decoded stream caches, including ObjStm. Only small
        # metadata/hashes survive this iteration; do not retain the whole PDF.
        reader.resolved_objects.clear()
    todo, visited = list(content_roots), set()
    while todo:
        key = todo.pop()
        if key in visited:
            continue
        visited.add(key)
        todo.extend(arrays.get(key, []))
        if key in rows and "encoded_bytes" in rows[key]:
            rows[key]["type"] = "PageContent"
    for key in font_files:
        if key in rows:
            rows[key]["type"] = "FontFile"
    return rows


def physical_offset(entry, entries):
    if entry["kind"] == "direct":
        return entry["offset"]
    container = entries.get(entry["object_stream"])
    if not container or container["kind"] != "direct":
        raise ValueError("Invalid object stream location")
    return container["offset"]


def analyze(original_path, result_path, *, decoded=False, top=20, decode_limit_mib=32):
    limit = decode_limit_mib * 1024 * 1024
    with apply_configuration(zlib_maximum_output_length=limit,
                             stream_decoding_work_maximum_length=limit * 2), \
            Path(original_path).open("rb") as original, Path(result_path).open("rb") as result:
        sizes = original.seek(0, 2), result.seek(0, 2)
        prefix = prefix_equal(original, result)
        original.seek(0)
        result.seek(0)
        # Passing open files avoids PdfReader(path)'s whole-file BytesIO copy.
        old, new = PdfReader(original, strict=True), PdfReader(result, strict=True)
        if old.is_encrypted or new.is_encrypted:
            raise ValueError("Encrypted PDFs are outside this tool's scope")
        old_chain, old_entries = revision_chain(old)
        new_chain, new_entries = revision_chain(new)
        old_rows = inventory(old, old_entries, decoded)
        new_rows = inventory(new, new_entries, decoded)
        matches = {mode: defaultdict(list) for mode in ("encoded", "decoded")}
        for key, row in old_rows.items():
            for mode in matches:
                if f"{mode}_sha256" in row:
                    matches[mode][(row[f"{mode}_bytes"], row[f"{mode}_sha256"])].append(key)
        changes = []
        for key, row in new_rows.items():
            entry = new_entries[key[0]]
            position = physical_offset(entry, new_entries)
            if prefix and position < sizes[0]:
                continue
            previous = old_entries.get(key[0])
            item = {**row, "status": "redefined" if previous else "new", "storage": dict(entry),
                    "physical_offset": position}
            if previous:
                item["previous_generation"] = previous["generation"]
            for mode in matches:
                if f"{mode}_sha256" in row:
                    corresponding = old_rows.get((key[0], previous["generation"])) if previous else None
                    item[f"same_object_{mode}_equal"] = bool(corresponding and
                        corresponding.get(f"{mode}_sha256") == row[f"{mode}_sha256"])
                    repeated = matches[mode].get((row[f"{mode}_bytes"], row[f"{mode}_sha256"]), [])
                    item[f"{mode}_matches_original_count"] = len(repeated)
                    item[f"{mode}_matches_original_sample"] = [ref_json(ref) for ref in repeated[:10]]
            changes.append(item)
        streams = [r for r in changes if "encoded_bytes" in r]
        stream_bytes = sum(r["encoded_bytes"] for r in streams)
        errors = {label: [ref_json(key) for key, row in rows.items() if "error" in row]
                  for label, rows in (("original", old_rows), ("result", new_rows))}
        by_type = {}
        for row in streams:
            totals = by_type.setdefault(row["type"], {"count": 0, "encoded_bytes": 0, "repeated_encoded_bytes": 0})
            totals["count"] += 1
            totals["encoded_bytes"] += row["encoded_bytes"]
            if row.get("encoded_matches_original_count"):
                totals["repeated_encoded_bytes"] += row["encoded_bytes"]
        return {
            "schema": 1, "parser": f"pypdf {pypdf.__version__}", "complete": not any(errors.values()),
            "sizes": {"input_bytes": sizes[0], "output_bytes": sizes[1], "added_bytes": sizes[1] - sizes[0]},
            "original_is_exact_prefix": prefix,
            "original_xref_chain": old_chain, "result_xref_chain": new_chain,
            "original_final_xref_in_result_chain": old_chain[0]["offset"] in {s["offset"] for s in new_chain[1:]} if prefix else False,
            "comparison_scope": "active objects serialized after input boundary" if prefix else
                                "all active result objects; non-prefix file, object numbers may not preserve identity",
            "counts": {"original_active_objects": len(old_entries), "result_active_objects": len(new_entries),
                       "new": sum(r["status"] == "new" for r in changes),
                       "redefined": sum(r["status"] == "redefined" for r in changes),
                       "changed_object_types": dict(Counter(r["type"] for r in changes))},
            "original_stream_types": dict(Counter(r["type"] for r in old_rows.values() if "encoded_bytes" in r)),
            "appended_stream_totals_by_type": by_type if prefix else None,
            "freed_or_absent_objects": [ref_json((n, e["generation"])) for n, e in old_entries.items() if n not in new_entries],
            "decoded_comparison_complete": all(r.get("decoded_status") == "ok" for r in
                [*old_rows.values(), *new_rows.values()] if "encoded_bytes" in r) if decoded else None,
            "growth": {"active_appended_encoded_stream_bytes": stream_bytes if prefix else None,
                       "other_or_superseded_bytes": sizes[1] - sizes[0] - stream_bytes if prefix else None,
                       "repeated_encoded_payload_bytes": sum(r["encoded_bytes"] for r in streams if r.get("encoded_matches_original_count")),
                       "repeated_decoded_payload_output_encoded_bytes": sum(r["encoded_bytes"] for r in streams if r.get("decoded_matches_original_count")) if decoded else None},
            "top_streams": sorted(streams, key=lambda r: r["encoded_bytes"], reverse=True)[:top],
            "objects": changes, "unreadable_objects": errors,
            "limits": [
                "No paths, names, dictionary values, text, images or stream payloads are emitted.",
                "SHA-256 encoded hashes cover stream payload bytes only; dictionaries may differ.",
                "Optional decoded hashes: unfiltered/Flate with predictors, not rendered pixels; other filters skipped.",
                f"Decoded output cap per Flate stream: {decode_limit_mib} MiB; declared stream cap: 75000000 bytes.",
                "One parsed object/ObjStm at a time; metadata and hashes retained. Individual streams still occupy memory.",
                "Only effective active objects are itemized; superseded objects in multiple appended revisions belong to the residual.",
                "Residual includes dictionaries, syntax, xref, trailers, superseded objects and any unreadable data; not CPU time.",
                "Strict parsing; encrypted/malformed/repaired xrefs not silently treated as a valid incremental.",
                "Does not prove visual preservation or Android/PDFium performance; PDFium is not executed.",
            ],
        }


def main():
    parser = argparse.ArgumentParser(description="Diagnostico externo de estructura incremental (solo lectura).")
    parser.add_argument("original", type=Path)
    parser.add_argument("result", type=Path)
    parser.add_argument("--report", type=Path, help="JSON de salida; no puede sobrescribir entradas")
    parser.add_argument("--decoded", action="store_true", help="Comparar tambien datos Flate decodificados (sin renderizar)")
    parser.add_argument("--decode-limit-mib", type=int, default=32)
    parser.add_argument("--top", type=int, default=20)
    args = parser.parse_args()
    if args.top < 1 or args.decode_limit_mib < 1:
        parser.error("Los limites deben ser positivos.")
    if args.report and args.report.resolve() in {args.original.resolve(), args.result.resolve()}:
        parser.error("El informe no puede sobrescribir un PDF de entrada.")
    try:
        report = analyze(args.original, args.result, decoded=args.decoded, top=args.top,
                         decode_limit_mib=args.decode_limit_mib)
        data = json.dumps(report, indent=2, ensure_ascii=True) + "\n"
        if args.report:
            # Exclusive creation protects existing PDFs/reports, including hard links.
            with args.report.open("x", encoding="utf-8") as output:
                output.write(data)
            print("Informe JSON creado. " + ("Analisis completo." if report["complete"] else "Analisis parcial: revisar unreadable_objects."))
        else:
            print(data, end="")
        return 0 if report["complete"] else 2
    except Exception as error:
        print(f"No se pudo completar el analisis ({type(error).__name__}). No se muestran datos privados.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
