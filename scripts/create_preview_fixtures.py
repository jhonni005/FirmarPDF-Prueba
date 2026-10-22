from pathlib import Path
import random, tempfile
root = Path(tempfile.gettempdir()) / 'FirmarPdf-preview-fixtures'
root.mkdir(exist_ok=True)
def make_pdf(name, sizes, images=False):
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    kids = []
    rng = random.Random(1234)
    for i, (w,h) in enumerate(sizes):
        image_id = None
        if images:
            pixels = rng.randbytes(1000*1000*3)
            objects.append(b'<< /Type /XObject /Subtype /Image /Width 1000 /Height 1000 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length '+str(len(pixels)).encode()+b' >>\nstream\n'+pixels+b'\nendstream')
            image_id = len(objects)
        content = f'0.94 0.96 1 rg 0 0 {w} {h} re f\n0.1 0.2 0.6 rg BT /F1 24 Tf 25 {h-50} Td (Preview test page {i+1}) Tj ET\n'.encode()
        if image_id:
            content += f'q {w-40} 0 0 {h-120} 20 40 cm /Im0 Do Q\n'.encode()
        else:
            for x in range(20, w, 50):
                content += f'0.2 0.4 0.7 RG {x} 20 m {x} {h-80} l S\n'.encode()
            content += f'1 0.3 0.1 rg {w//3} {h//3} 60 60 re f\n'.encode()
        objects.append(b'<< /Length '+str(len(content)).encode()+b' >>\nstream\n'+content+b'endstream')
        content_id = len(objects)
        resources = '/Font << /F1 3 0 R >>' + (f' /XObject << /Im0 {image_id} 0 R >>' if image_id else '')
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {w} {h}] /Resources << {resources} >> /Contents {content_id} 0 R >>'.encode())
        kids.append(len(objects))
    objects[1] = ('<< /Type /Pages /Kids ['+' '.join(f'{i} 0 R' for i in kids)+f'] /Count {len(kids)} >>').encode()
    path=root/name
    with path.open('wb') as f:
        f.write(b'%PDF-1.4\n')
        offsets=[0]
        for i,obj in enumerate(objects,1):
            offsets.append(f.tell())
            f.write(f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n')
        xref=f.tell()
        f.write(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
        for pos in offsets[1:]: f.write(f'{pos:010d} 00000 n \n'.encode())
        f.write(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    print(name, path.stat().st_size)
make_pdf('Multipagina.pdf', [(300,600),(600,300),(612,792),(400,900),(900,400),(595,842)])
make_pdf('Grande.pdf', [(612,792)]*8, images=True)
(root/'Invalido.pdf').write_text('invalid test fixture', encoding='ascii')
print(root)