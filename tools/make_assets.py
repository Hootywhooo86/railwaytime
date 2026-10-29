"""Builds PixTime's Android assets: 30-second chiptune alarm sounds (res/raw) and
pixel launcher + notification icons. Run from the repo root: python3 tools/make_assets.py"""
import math, os, struct, wave
from PIL import Image

RES = 'android/app/src/main/res'
RATE = 11025

# Same note sequences as SOUNDS in app/index.html: (frequency Hz, seconds, wave)
SOUNDS = {
    'pixel_chime':   [(784, .15), (988, .15), (1175, .15), (1568, .35), (0, .4)],
    'station_bell':  [(1319, .5), (0, .1), (1319, .5), (0, .6)],
    'train_whistle': [(587, .9, 'saw'), (0, .15), (587, .4, 'saw'), (0, .6)],
    'classic_beep':  [(1000, .12), (0, .08), (1000, .12), (0, .08), (1000, .12), (0, .08), (1000, .12), (0, .5)],
    'sunrise':       [(523, .25, 'tri'), (659, .25, 'tri'), (784, .25, 'tri'), (1047, .5, 'tri'), (0, .5)],
    'rooster':       [(700, .1), (900, .1), (1200, .25), (1000, .3), (800, .4), (0, .5)],
}

def osc(kind, phase):
    p = phase % 1.0
    if kind == 'saw': return 2 * p - 1
    if kind == 'tri': return 4 * abs(p - 0.5) - 1
    return 1.0 if p < 0.5 else -1.0

def render(seq, seconds=30):
    out, t_total, n = bytearray(), 0.0, 0
    while t_total < seconds:
        gain = min(0.25 + n * 0.06, 0.9)  # gentle start, louder each repeat
        for note in seq:
            f, d = note[0], note[1]
            kind = note[2] if len(note) > 2 else 'sq'
            count = int(d * RATE)
            phase = 0.0
            for i in range(count):
                env = min(1.0, i / (RATE * 0.01)) * max(0.0, 1 - i / count) ** 0.6
                ff = f * (1 - 0.06 * i / count) if kind == 'saw' else f
                v = osc(kind, phase) * env * gain if f else 0.0
                phase += ff / RATE
                out.append(int(128 + 110 * v))
            t_total += d
        n += 1
    return bytes(out)

os.makedirs(f'{RES}/raw', exist_ok=True)
for name, seq in SOUNDS.items():
    with wave.open(f'{RES}/raw/{name}.wav', 'wb') as w:
        w.setnchannels(1); w.setsampwidth(1); w.setframerate(RATE)
        w.writeframes(render(seq))

# 16x16 pixel alarm-clock glyph: '#' body, 'o' face, '|' hands, 'r' second-hand dot
GLYPH = [
    '..##........##..',
    '.####......####.',
    '.###.######.###.',
    '...##oooooo##...',
    '..#oooooooooo#..',
    '..#ooooo|oooo#..',
    '.#oooooo|ooooo#.',
    '.#oooooo|ooooo#.',
    '.#oooooo||||oo#.',
    '.#oooooooooooo#.',
    '.#oooooooooroo#.',
    '..#oooooooooo#..',
    '..#oooooooooo#..',
    '...##oooooo##...',
    '..##.######.##..',
    '.##..........##.',
]

def glyph(scale, colours, pad=0, bg=None):
    size = 16 * scale + 2 * pad
    img = Image.new('RGBA', (size, size), bg or (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(GLYPH):
        for x, ch in enumerate(row):
            c = colours.get(ch)
            if not c: continue
            for dy in range(scale):
                for dx in range(scale):
                    px[pad + x * scale + dx, pad + y * scale + dy] = c
    return img

TEAL, INK, FACE, PINK, BG = (45, 212, 191, 255), (11, 11, 22, 255), (244, 241, 255, 255), (255, 93, 143, 255), (11, 11, 22, 255)
launcher = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
for dens, size in launcher.items():
    d = f'{RES}/mipmap-{dens}'
    os.makedirs(d, exist_ok=True)
    full = glyph(1, {'#': TEAL, 'o': FACE, '|': INK, 'r': PINK}, pad=4, bg=BG).resize((size, size), Image.NEAREST)
    full.save(f'{d}/ic_launcher.png'); full.save(f'{d}/ic_launcher_round.png')
    # Adaptive icon foreground: 108dp canvas, glyph inside the 66dp safe zone.
    fg_size = size * 108 // 48
    fg = Image.new('RGBA', (fg_size, fg_size), (0, 0, 0, 0))
    g = glyph(1, {'#': TEAL, 'o': FACE, '|': INK, 'r': PINK}).resize((fg_size * 56 // 108,) * 2, Image.NEAREST)
    fg.paste(g, ((fg_size - g.width) // 2,) * 2, g)
    fg.save(f'{d}/ic_launcher_foreground.png')

# Notification icon: white silhouette on transparent (Android tints it).
WHITE = (255, 255, 255, 255)
for dens, size in {'mdpi': 24, 'hdpi': 36, 'xhdpi': 48, 'xxhdpi': 72, 'xxxhdpi': 96}.items():
    d = f'{RES}/drawable-{dens}'
    os.makedirs(d, exist_ok=True)
    glyph(1, {'#': WHITE, 'o': WHITE}, pad=2).resize((size, size), Image.NEAREST).save(f'{d}/ic_stat_alarm.png')
print('assets ok')
