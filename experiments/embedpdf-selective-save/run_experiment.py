"""Independent FULL/selective exports with the same pinned native desktop engine.

Every attempt loads its own base, opens a fresh layer, commits the same image
edit, and saves to a new file. Rendering/structural verification is untimed.
"""

import argparse
import ctypes as C
from collections import defaultdict
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[1]
sys.path.insert(0, str(ROOT / "_local/python"))
sys.path.insert(0, str(PROJECT / "scripts"))
from PIL import Image, ImageChops, ImageStat
from analyze_pdf_incremental import analyze
from native import Runtime, Source, Sink, Rect, check

DLL = ROOT / "_local/runtime/bin/embedpdf.dll"
DLL_SHA256 = "dd6c2e9839f0bfbf23e45dae46879ec8eff92d5719bfbe785175c1b245a616df"
BUFFER_BYTES = 64 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


@contextmanager
def measured(timings, key):
    start = time.perf_counter_ns()
    try:
        yield
    finally:
        timings[key] += time.perf_counter_ns() - start


def image_matrix(page_box, turns, placement, png_size):
    left, bottom, right, top = page_box
    width, height = right - left, top - bottom
    if width <= 0 or height <= 0 or turns not in (0, 1, 2, 3):
        raise ValueError("Invalid page geometry")
    visual_w, visual_h = (height, width) if turns % 2 else (width, height)
    x, y, w, h = placement
    h = h if h is not None else png_size[1] / png_size[0] * w * visual_w / visual_h
    if not all(math.isfinite(v) for v in (x, y, w, h)) or min(x, y) < 0 or min(w, h) <= 0 or x + w > 1 or y + h > 1:
        raise ValueError("Placement exceeds visible page")
    vx, vy, vw, vh = x * visual_w, y * visual_h, w * visual_w, h * visual_h
    # Same formulas as the Android experiment, adapted without editing it.
    matrices = {
        0: (vw, 0, 0, vh, left + vx, bottom + height - vy - vh),
        1: (0, vw, -vh, 0, left + vy + vh, bottom + vx),
        2: (-vw, 0, 0, -vh, left + width - vx, bottom + vy + vh),
        3: (0, -vw, vh, 0, left + width - vy - vh, bottom + height - vx),
    }
    return matrices[turns], (x, y, w, h)


def export(runtime, original, signature, target, mode, page_index, placement):
    timings = defaultdict(int)
    start = time.perf_counter_ns()
    source = base = document = bitmap = image = page = output = None
    transaction = False
    cleanup_errors = []
    result = {"mode": mode, "success": False, "bufferBytes": BUFFER_BYTES,
              "saveApi": "EPDF_SaveAsCopyEx(FPDF_NO_INCREMENTAL=2)" if mode == "full" else "EPDFLayer_SaveDelta",
              "outputKind": "complete-pdf" if mode == "full" else "base-plus-raw-delta"}
    try:
        with measured(timings, "openMs"):
            source = Source(original)
            base = check(runtime.EPDF_LoadBaseDocument(C.byref(source.access), None), "Load base")
        with measured(timings, "bitmapMs"):
            with Image.open(signature) as png:
                if png.format != "PNG":
                    raise ValueError("Signature must be PNG")
                rgba = png.convert("RGBA")  # Straight alpha; no R/B loss.
                rgba.load()
            if not rgba.getchannel("A").getbbox():
                raise ValueError("Empty signature alpha")
            pixels = C.create_string_buffer(rgba.tobytes("raw", "BGRA"))
            bitmap = check(runtime.FPDFBitmap_CreateEx(rgba.width, rgba.height, 4, pixels, rgba.width * 4), "Create bitmap")
        with measured(timings, "layerTransactionMs"):
            status = C.c_int(-1)
            document = check(runtime.EPDFLayer_OpenLayer(base, None, None, C.byref(status)), "Open empty layer")
            check(status.value == 0, "Layer status")
            count = runtime.FPDF_GetPageCount(document)
            check(count > 0 and 0 <= page_index < count, "Page index")
            check(runtime.FPDF_GetSignatureCount(document) == 0, "Digital signature restriction")
            check(runtime.FPDF_GetSecurityHandlerRevision(document) < 0 and runtime.FPDF_GetDocPermissions(document) & 8,
                  "Encryption/permission restriction")
            check(runtime.EPDFLayer_BeginTransaction(document), "Begin transaction")
            transaction = True
        with measured(timings, "insertContentMs"):
            page = check(runtime.FPDF_LoadPage(document, page_index), "Load edit page")
            box = Rect()
            check(runtime.FPDF_GetPageBoundingBox(page, C.byref(box)), "Page bounding box")
            page_box = (float(box.left), float(box.bottom), float(box.right), float(box.top))
            turns = runtime.FPDFPage_GetRotation(page)
            matrix, actual_placement = image_matrix(page_box, turns, placement, rgba.size)
            image = check(runtime.FPDFPageObj_NewImageObj(document), "Create image object")
            check(runtime.FPDFImageObj_SetBitmap(None, 0, image, bitmap), "Set bitmap")
            check(runtime.FPDFImageObj_SetMatrix(image, *matrix), "Set matrix")
            inserted = runtime.FPDFPage_InsertObject(page, image)
            image = None  # This revision owns the object on success AND frees it on failure.
            check(inserted, "Insert image")
            check(runtime.FPDFPage_GenerateContent(page), "Generate content")
            runtime.FPDF_ClosePage(page)
            page = None
        with measured(timings, "layerTransactionMs"):
            check(runtime.EPDFLayer_CommitTransaction(document), "Commit transaction")
            transaction = False
        result.update(pageCount=count, page=page_index + 1, pageBox=list(page_box), rotation=turns * 90,
                      matrix=list(matrix), placement=list(actual_placement), pngSize=list(rgba.size),
                      promotedObjects=int(runtime.EPDFLayer_GetPromotedObjectCount(document)))
        write_start = time.perf_counter_ns()
        output = target.open("xb", buffering=BUFFER_BYTES)
        try:
            if mode == "selective":
                # SaveDelta uses absolute offsets from the BASE length, not from the
                # current Python file position. Its contract is base bytes + raw delta.
                with measured(timings, "copyOriginalMs"):
                    with Path(original).open("rb", buffering=0) as copy_source:
                        while block := copy_source.read(1024 * 1024):
                            check(output.write(block) == len(block), "Copy original")
            sink = Sink(output)
            status = C.c_int(-1)
            with measured(timings, "saveMs"):
                if mode == "full":
                    saved = runtime.EPDF_SaveAsCopyEx(document, C.byref(sink.access), 2, C.byref(status))
                    expected_status = 1  # EPDFSaveStatus_kWritten
                else:
                    saved = runtime.EPDFLayer_SaveDelta(document, C.byref(sink.access), C.byref(status))
                    expected_status = 0  # EPDFLayerSaveStatus_kSuccess
            result.update(saveStatus=status.value, callbackCalls=sink.calls, callbackBytes=sink.bytes)
            check(saved and status.value == expected_status and not sink.error and not source.error, "Native save/callback failure")
        finally:
            with measured(timings, "flushCloseMs"):
                try:
                    output.flush()
                finally:
                    output.close()
            output = None
            timings["writeStageMs"] += time.perf_counter_ns() - write_start
        result["totalToPdfMs"] = (time.perf_counter_ns() - start) / 1_000_000
        result.update(inputBytes=source.size, outputBytes=target.stat().st_size,
                      addedBytes=target.stat().st_size - source.size, success=True)
    except BaseException as error:
        result["error"] = type(error).__name__ + ": " + str(error) if isinstance(error, (RuntimeError, ValueError)) else type(error).__name__
    finally:
        with measured(timings, "documentCloseMs"):
            if output is not None:
                try:
                    output.close()
                except BaseException as error:
                    cleanup_errors.append(type(error).__name__)
            for name, handle in (("FPDFPageObj_Destroy", image), ("FPDF_ClosePage", page)):
                if handle:
                    getattr(runtime, name)(handle)
            if transaction and document:
                if not runtime.EPDFLayer_AbortTransaction(document):
                    cleanup_errors.append("AbortTransaction")
            if bitmap:
                runtime.FPDFBitmap_Destroy(bitmap)
            if document:
                runtime.FPDF_CloseDocument(document)
            if base:
                runtime.EPDF_ReleaseBaseDocument(base)
            if source:
                source.close()
        result["timingsMs"] = {key: timings[key] / 1_000_000 for key in
                               ("openMs", "bitmapMs", "layerTransactionMs", "insertContentMs", "saveMs",
                                "copyOriginalMs", "flushCloseMs", "writeStageMs", "documentCloseMs")}
        result["totalMs"] = (time.perf_counter_ns() - start) / 1_000_000
        result["cleanupErrors"] = cleanup_errors
        if cleanup_errors:
            result["success"] = False
    return result


def render(runtime, path, page_index, matrix=None, render_width=1200):
    source, document, page, bitmap = Source(path), None, None, None
    try:
        document = check(runtime.FPDF_LoadCustomDocument(C.byref(source.access), None), "Open standalone result")
        count = runtime.FPDF_GetPageCount(document)
        page = check(runtime.FPDF_LoadPage(document, page_index), "Load render page")
        w, h = runtime.FPDF_GetPageWidthF(page), runtime.FPDF_GetPageHeightF(page)
        width, height = render_width, max(1, round(render_width * h / w))
        if height > 10000:
            raise ValueError("Render height limit")
        bitmap = check(runtime.FPDFBitmap_CreateEx(width, height, 4, None, 0), "Render bitmap")
        check(runtime.FPDFBitmap_FillRect(bitmap, 0, 0, width, height, 0xffffffff), "Fill background")
        runtime.FPDF_RenderPageBitmap(bitmap, page, 0, 0, width, height, 0, 1)
        stride = runtime.FPDFBitmap_GetStride(bitmap)
        data = C.string_at(runtime.FPDFBitmap_GetBuffer(bitmap), stride * height)
        image = Image.frombytes("RGBA", (width, height), data, "raw", "BGRA", stride, 1).convert("RGB")
        rectangle = None
        if matrix:
            a, b, c, d, e, f = matrix
            positions = []
            for u, v in ((0, 0), (1, 0), (0, 1), (1, 1)):
                px, py = C.c_int(), C.c_int()
                check(runtime.FPDF_PageToDevice(page, 0, 0, width, height, 0,
                      a * u + c * v + e, b * u + d * v + f, C.byref(px), C.byref(py)), "PageToDevice")
                positions.append((px.value, py.value))
            rectangle = (min(x for x, y in positions), min(y for x, y in positions),
                         max(x for x, y in positions), max(y for x, y in positions))
        check(not source.error, "Render source read")
        return image, count, rectangle
    finally:
        if bitmap:
            runtime.FPDFBitmap_Destroy(bitmap)
        if page:
            runtime.FPDF_ClosePage(page)
        if document:
            runtime.FPDF_CloseDocument(document)
        source.close()


def mean_difference(a, b, mask=None):
    return sum(ImageStat.Stat(ImageChops.difference(a, b), mask=mask).mean) / 3


def verify_pair(runtime, original, signature, directory, full, selective, page_index, label):
    check(full["success"] and selective["success"], "Export failed; repetitions refused")
    check(full["matrix"] == selective["matrix"] and full["placement"] == selective["placement"], "Geometry differs")
    baseline, count, rectangle = render(runtime, original, page_index, full["matrix"])
    l, t, r, b = rectangle
    check(0 <= l < r <= baseline.width and 0 <= t < b <= baseline.height, "Signature rectangle")
    with Image.open(signature) as png:
        stamp = png.convert("RGBA").resize((r - l, b - t), Image.Resampling.BILINEAR)
    before_crop = baseline.crop(rectangle)
    expected = Image.alpha_composite(before_crop.convert("RGBA"), stamp).convert("RGB")
    mask = ImageChops.difference(before_crop, expected).convert("L").point(lambda value: 255 if value > 4 else 0)
    check(mask.getbbox(), "Signature has no visible contribution on this background")
    baseline_error = mean_difference(before_crop, expected, mask)
    rendered = {}
    verification = {"pageCountOriginal": count, "signatureRectanglePixels": list(rectangle),
                    "expectedInkPixels": mask.histogram()[255], "baselineVsExpectedMae": baseline_error}
    for mode, result in (("full", full), ("selective", selective)):
        path = directory / result["file"]
        after, pages, _ = render(runtime, path, page_index)
        check(pages == count == result["pageCount"], "Page count changed")
        check(after.size == baseline.size, "Page size changed")
        crop = after.crop(rectangle)
        error = mean_difference(crop, expected, mask)
        changed = mean_difference(crop, before_crop, mask)
        # Rendered filtering can differ from Pillow at antialiased edges. Require
        # substantial agreement with the EXPECTED colored/alpha stamp, not just
        # any change or a native save success code.
        visible = changed > 3 and error < baseline_error * 0.35 + 3
        difference = ImageChops.difference(after, baseline).convert("L")
        margin = (max(0, l - 2), max(0, t - 2), min(baseline.width, r + 2), min(baseline.height, b + 2))
        difference.paste(0, margin)
        outside = sum(difference.histogram()[3:])
        outside_ok = outside <= max(16, baseline.width * baseline.height // 10000)
        after.save(directory / f"{label}-{mode}-page.png")
        crop.save(directory / f"{label}-{mode}-signature-crop.png")
        report = analyze(original, path, decoded=False)
        (directory / f"{label}-{mode}-structure.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        large_repeats = [row for row in report["objects"] if row.get("encoded_matches_original_count", 0)
                         and row.get("encoded_bytes", 0) >= 65536]
        structure = {"complete": report["complete"], "exactOriginalPrefix": report["original_is_exact_prefix"],
                     "originalXrefLinked": report["original_final_xref_in_result_chain"],
                     "newObjects": report["counts"]["new"], "redefinedObjects": report["counts"]["redefined"],
                     "repeatedEncodedPayloadBytes": report["growth"]["repeated_encoded_payload_bytes"],
                     "largeOriginalStreamCopies": len(large_repeats),
                     "largeOriginalStreamBytes": sum(row["encoded_bytes"] for row in large_repeats),
                     "finalXref": report["result_xref_chain"][0]}
        incremental_ok = mode != "selective" or (structure["exactOriginalPrefix"] and structure["originalXrefLinked"]
                         and not large_repeats)
        result["verification"] = {"standaloneOpen": True, "pageCount": pages, "signatureVisible": visible,
                                  "stampExpectedMae": error, "stampActualChangeMae": changed,
                                  "outsideChangedPixels": outside, "outsidePreserved": outside_ok, **structure}
        result["verified"] = bool(visible and outside_ok and report["complete"] and incremental_ok)
        rendered[mode] = after
    equality = ImageChops.difference(rendered["full"], rendered["selective"]).getbbox() is None
    verification["fullSelectiveRenderEqual"] = equality
    verification["passed"] = bool(equality and full["verified"] and selective["verified"])
    before_crop.save(directory / f"{label}-original-crop.png")
    return verification


def main():
    parser = argparse.ArgumentParser(description="Experimento nativo Windows de FULL vs capa selectiva; sin Android.")
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--signature", type=Path, required=True)
    parser.add_argument("--synthetic-signature", action="store_true", help="Etiqueta explícita: PNG sintético autorizado")
    parser.add_argument("--expected-input-bytes", type=int)
    parser.add_argument("--page", type=int, default=1, help="Página empezando en 1")
    parser.add_argument("--x", type=float, default=0.1)
    parser.add_argument("--y", type=float, default=0.1)
    parser.add_argument("--width", type=float, default=0.3)
    parser.add_argument("--height", type=float, help="Normalizada; por defecto conserva proporción del PNG")
    parser.add_argument("--label", default="comparison")
    parser.add_argument("--repeat-after-verified", type=int, choices=(0, 3), default=3)
    args = parser.parse_args()
    if args.page < 1 or not args.label.replace("-", "").replace("_", "").isalnum():
        parser.error("Página o etiqueta inválida")
    if args.expected_input_bytes and args.original.stat().st_size != args.expected_input_bytes:
        parser.error("El tamaño del original no coincide con el esperado")
    if sha256(DLL) != DLL_SHA256:
        raise SystemExit("DLL distinta de la publicación fijada")
    directory = ROOT / "_local/results" / (args.label + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True, exist_ok=False)
    original_hash, signature_hash = sha256(args.original), sha256(args.signature)
    summary = {"engineRevision": "5f7608ba4a634c94cd718067d6b678a45ba89088", "dllSha256": DLL_SHA256,
               "platform": "Windows x64", "signatureKind": "synthetic-authorized" if args.synthetic_signature else "provided-png",
               "originalSha256": original_hash, "signatureSha256": signature_hash,
               "inputBytes": args.original.stat().st_size, "runs": [], "pairs": [],
               "timingNotes": ["perf_counter_ns accumulated, converted to ms at end",
                               "saveMs includes callbacks and buffered writes; it excludes final flush/close",
                               "writeStageMs contains copyOriginalMs, saveMs, flushCloseMs and output setup",
                               "layerTransactionMs accumulates open/begin/security checks plus later commit",
                               "totalToPdfMs ends at final buffer drain and output close; totalMs also closes native resources",
                               "No fsync/FlushFileBuffers: timings end at OS-visible closed file, not guaranteed durable storage",
                               "Hashing, rendering, parsing and validation excluded; runtime DLL/thread startup excluded",
                               "Fresh base/layer/descriptor per attempt; OS disk caches are not purged",
                               "Windows results do not demonstrate Android performance"]}
    runtime = Runtime(DLL)
    runtime.EPDF_InitThread()
    exit_code = 2
    try:
        for index in range(args.repeat_after_verified + 1):
            label = "initial" if index == 0 else f"repeat-{index}"
            pair = {}
            order = ("full", "selective") if index % 2 == 0 else ("selective", "full")
            for mode in order:
                filename = f"{label}-{mode}.pdf"
                result = export(runtime, args.original, args.signature, directory / filename, mode, args.page - 1,
                                (args.x, args.y, args.width, args.height))
                result.update(file=filename, repetition=index)
                pair[mode] = result
                summary["runs"].append(result)
                print(json.dumps({k: result.get(k) for k in ("mode", "repetition", "success", "inputBytes", "outputBytes", "addedBytes", "totalMs", "timingsMs", "error")}), flush=True)
                if not result["success"]:
                    raise RuntimeError("Export failed; evidence retained, no fallback")
            verification = verify_pair(runtime, args.original, args.signature, directory, pair["full"], pair["selective"], args.page - 1, label)
            summary["pairs"].append({"repetition": index, **verification})
            print(json.dumps({"verification": label, **verification}), flush=True)
            if not verification["passed"]:
                raise RuntimeError("Verification failed; further repetitions refused")
        summary["mediansMs"] = {}
        for mode in ("full", "selective"):
            repeats = [r for r in summary["runs"] if r["mode"] == mode and r["repetition"] > 0]
            if repeats:
                summary["mediansMs"][mode] = {"totalMs": statistics.median(r["totalMs"] for r in repeats),
                    "totalToPdfMs": statistics.median(r["totalToPdfMs"] for r in repeats),
                    **{key: statistics.median(r["timingsMs"][key] for r in repeats) for key in repeats[0]["timingsMs"]}}
        exit_code = 0
    except BaseException as error:
        summary["failure"] = type(error).__name__ + ": " + str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print("Experiment stopped; evidence retained: " + summary["failure"], flush=True)
    finally:
        runtime.EPDF_ShutdownThread()
        summary["originalUnchanged"] = sha256(args.original) == original_hash
        summary["signatureUnchanged"] = sha256(args.signature) == signature_hash
        summary["passed"] = exit_code == 0 and summary["originalUnchanged"] and summary["signatureUnchanged"]
        summary["requiredSymbols"] = runtime.symbols
        (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        # Relative directory only; never print private source/signature paths.
        print("Evidence: " + directory.relative_to(ROOT).as_posix(), flush=True)
    return 0 if summary["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
