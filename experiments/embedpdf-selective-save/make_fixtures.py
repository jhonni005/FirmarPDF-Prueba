"""Controlled PDF and transparent simulated signature; never a personal signature."""

import math
import random
from pathlib import Path
import sys
import zlib

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "_local/python"))
from PIL import Image, ImageDraw


def create():
    directory = ROOT / "_local/fixtures"
    directory.mkdir(parents=True, exist_ok=True)
    signature = directory / "synthetic-signature.png"
    pdf = directory / "small-original.pdf"
    if not signature.exists():
        scale = 4
        image = Image.new("RGBA", (480 * scale, 150 * scale), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        # Smooth looping strokes, no personal name or captured handwriting.
        points = []
        for i in range(401):
            t = i / 400
            x = 25 + 405 * t + 16 * math.sin(14 * math.pi * t)
            y = 82 - 28 * math.sin(11 * math.pi * t) - 17 * math.sin(3 * math.pi * t)
            points.append((round(x * scale), round(y * scale)))
        draw.line(points, fill=(18, 48, 130, 235), width=4 * scale, joint="curve")
        draw.line([(55 * scale, 125 * scale), (425 * scale, 100 * scale)], fill=(155, 28, 45, 170), width=2 * scale)
        image.resize((480, 150), Image.Resampling.LANCZOS).save(signature)
    if not pdf.exists():
        # Original image stream on both pages: can detect unwanted reserialization.
        pixels = random.Random(8111).randbytes(240 * 180 * 3)
        payload = zlib.compress(pixels)
        stream = lambda data, fields=b"": b"<< /Length " + str(len(data)).encode() + fields + b" >>\nstream\n" + data + b"\nendstream"
        objects = {
            1: b"<< /Type /Catalog /Pages 2 0 R >>",
            2: b"<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
            3: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources 8 0 R /Contents 4 0 R >>",
            4: stream(b"q 240 0 0 180 300 30 cm /Original Do Q\n"),
            5: stream(payload, b" /Type /XObject /Subtype /Image /Width 240 /Height 180 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode"),
            6: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources 8 0 R /Contents 7 0 R >>",
            7: stream(b"q 240 0 0 180 300 30 cm /Original Do Q\n"),
            8: b"<< /XObject << /Original 5 0 R >> >>",
        }
        data = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
        offsets = [0]
        for number, obj in objects.items():
            offsets.append(len(data))
            data.extend(f"{number} 0 obj\n".encode() + obj + b"\nendobj\n")
        xref = len(data)
        data.extend(b"xref\n0 9\n0000000000 65535 f \n")
        for offset in offsets[1:]:
            data.extend(f"{offset:010d} 00000 n \n".encode())
        data.extend(f"trailer\n<< /Root 1 0 R /Size 9 >>\nstartxref\n{xref}\n%%EOF\n".encode())
        with pdf.open("xb") as output:
            output.write(data)
    print("Synthetic fixtures ready (no personal signature).")


if __name__ == "__main__":
    create()
