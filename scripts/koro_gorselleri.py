"""CI testi için örnek koralin açık (4 porte) ve kapalı (2 porte, porte başına 2 ses)
partisyonlarını Verovio ile çizip PNG'ye çevirir.  Kullanım: python koro_gorselleri.py <çıktı klasörü>"""
import os, re, sys
import cairosvg, verovio

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
here = os.path.dirname(os.path.abspath(__file__))
open_xml = open(os.path.join(here, "..", "app", "src", "main", "assets", "isinma-korali.musicxml"), encoding="utf-8").read()

def notes_of(part_id):
    body = re.search(rf'<part id="{part_id}">(.*?)</part>', open_xml, re.S).group(1)
    return re.findall(r"<measure[^>]*>(.*?)</measure>", body, re.S)

def strip_attrs(m):
    return re.sub(r"<attributes>.*?</attributes>|<direction.*?</direction>|<barline.*?</barline>", "", m, flags=re.S)

def with_voice(m, v):
    return re.sub(r"<voice>\d+</voice>", f"<voice>{v}</voice>", strip_attrs(m))

def pitch_down_written(m):
    # Tenor sekizlik sol anahtarında yazılıydı (yazılı = çalan + 12); fa anahtarına çalan perdeyle taşı
    def f(x): return f"<octave>{int(x.group(1)) - 1}</octave>"
    return re.sub(r"<octave>(\d)</octave>", f, m)

S, A, T, B = (notes_of(p) for p in ["P1", "P2", "P3", "P4"])
def merged(upper, lower, clef, lower_fix=lambda m: m, upper_fix=lambda m: m):
    ms = []
    for i, (u, l) in enumerate(zip(upper, lower)):
        dur = 8 if i == len(upper) - 1 else 8
        attrs = f'<attributes><divisions>2</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef>{clef}</clef></attributes>' if i == 0 else ""
        ms.append(f'<measure number="{i+1}">{attrs}{with_voice(upper_fix(u), 1)}<backup><duration>{dur}</duration></backup>{with_voice(lower_fix(l), 2)}</measure>')
    return "".join(ms)

closed = ('<?xml version="1.0" encoding="UTF-8"?><score-partwise version="4.0"><work><work-title>Isınma Korali (kapalı)</work-title></work>'
          '<part-list><score-part id="K"><part-name>Kadınlar</part-name></score-part><score-part id="E"><part-name>Erkekler</part-name></score-part></part-list>'
          f'<part id="K">{merged(S, A, "<sign>G</sign><line>2</line>")}</part>'
          f'<part id="E">{merged(T, B, "<sign>F</sign><line>4</line>", upper_fix=pitch_down_written)}</part></score-partwise>')

for name, xml in [("acik", open_xml), ("kapali", closed)]:
    tk = verovio.toolkit()
    tk.setOptions({"pageWidth": 2100, "pageHeight": 2970, "scale": 45, "adjustPageHeight": True, "header": "none", "footer": "none"})
    if not tk.loadData(xml):
        raise SystemExit(f"Verovio {name} dosyasını yükleyemedi")
    svg = tk.renderToSVG(1)
    cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=os.path.join(out, f"{name}.png"), output_width=2000, background_color="white")
    print(name, "sayfa:", tk.getPageCount())
