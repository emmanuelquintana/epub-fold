"""Limpia las capturas: quita la barra de estado y tapa títulos con derechos de autor."""
import sys
from PIL import Image, ImageDraw

src, out = sys.argv[1], sys.argv[2]

# nombre -> (archivo, [(rect, color)])
jobs = {
    'p_curl':      ('141400', []),
    'p_read':      ('141415', []),
    'p_highlight': ('141422', []),
    'p_stats':     ('141514', [((0, 0, 1080, 105), (0, 0, 0))]),
    't_curl':      ('141532', []),
    't_notes':     ('141622', [((40, 140, 1100, 240), (248, 248, 248))]),
    't_gutenberg': ('141718', [((0, 0, 2256, 112), (0, 0, 0))]),
    't_themes':    ('141733', []),
}
for name, (stamp, patches) in jobs.items():
    im = Image.open(f'{src}/Screenshot_20261113_{stamp}_EpubFold.jpg').convert('RGB')
    d = ImageDraw.Draw(im)
    for rect, color in patches:
        d.rectangle(rect, fill=color)
    im.save(f'{out}/{name}.png')
    print(name, im.size)
