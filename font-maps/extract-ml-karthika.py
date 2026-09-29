import sys, json, re
sys.path.insert(0, '/tmp')
N2U = json.load(open('/tmp/n2u.json'))
from pypdf import PdfReader
from pypdf.generic import ContentStream

r = PdfReader('/home/hatch/workspace/user/files/Script.pdf')

def get_b2u(font_ref, n2u):
    f = font_ref.get_object()
    enc = f['/Encoding'].get_object()
    diffs = enc.get('/Differences')
    b2u = {}
    cur = 0
    for item in diffs:
        if isinstance(item, int):
            cur = item
        else:
            name = str(item).lstrip('/')
            b2u[cur] = n2u.get(name, f'<{name}>')
            cur += 1
    return b2u

# F2: 'o' is ീ (long), not ി
N2U_F2 = {'space':' ', 'zero':'0','one':'1','two':'2','three':'3','four':'4',
          'five':'5','six':'6','seven':'7','eight':'8','nine':'9',
          'k':'\u0d38', 'o':'\u0d40', 'greaterequal':'\u0d7b'}

def fix_visual_order(s):
    """Convert visual order to logical order.
    PDF has [pre-base matra][consonant] (visual), Unicode needs [consonant][matra].
    Also [ra-vattu][consonant] -> [consonant][ra-vattu].
    Also combine െ+ാ→ൊ, േ+ാ→ോ, ൈ+ാ→ൌ.
    """
    chars = list(s)
    # Pass 1: move ra-vattu (്ര) after the following consonant
    # The ്ര appears as U+0D4D U+0D30 sequence before the base
    res = []
    i = 0
    while i < len(chars):
        # Check for ് + ര (virama + ra) followed by consonant
        if (i + 2 < len(chars) and chars[i] == '\u0d4d' and chars[i+1] == '\u0d30'
            and '\u0d15' <= chars[i+2] <= '\u0d39'):
            # [്ര][C] -> [C][്ര]
            res.append(chars[i+2])
            res.append(chars[i])
            res.append(chars[i+1])
            i += 3
            continue
        res.append(chars[i])
        i += 1
    chars = res
    # Pass 2: move pre-base matras (െ/േ/ൈ) after the following consonant
    res = []
    i = 0
    while i < len(chars):
        c = chars[i]
        if c in ('\u0d46', '\u0d47', '\u0d48') and i + 1 < len(chars):
            nxt = chars[i+1]
            if '\u0d15' <= nxt <= '\u0d39' or '\u0d05' <= nxt <= '\u0d14':
                # swap: [matra][base] -> [base][matra]
                res.append(nxt)
                res.append(c)
                i += 2
                continue
        res.append(c)
        i += 1
    # Pass 3: combine െ/േ/ൈ + ാ -> ഒ/ോ/ൌ signs
    out = []
    i = 0
    while i < len(res):
        c = res[i]
        if c in ('\u0d46', '\u0d47', '\u0d48') and i + 1 < len(res) and res[i+1] == '\u0d3e':
            combined = {'\u0d46': '\u0d4a', '\u0d47': '\u0d4b', '\u0d48': '\u0d4c'}[c]
            out.append(combined)
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)

def clean_text(s):
    # Remove redundant virama: ് followed by conjunct (which has ്)
    # Conjunct pattern: C + ് + C. If we have ് + (C + ് + C), remove first ്.
    s = re.sub(r'\u0d4d(?=[\u0d15-\u0d39]\u0d4d)', '', s)
    # Remove hyphens between Malayalam chars (discretionary hyphens)
    s = re.sub(r'(?<=[\u0d00-\u0d7f])-(?=[\u0d00-\u0d7f])', '', s)
    # Fix visual order
    s = fix_visual_order(s)
    return s

def extract_page(pno):
    page = r.pages[pno]
    fonts = page.get('/Resources', {}).get('/Font', {})
    b2u_map = {}
    for fname in fonts:
        f = fonts[fname].get_object()
        bf = str(f.get('/BaseFont', ''))
        if 'Karthika-Normal' in bf:
            b2u_map[str(fname)] = get_b2u(fonts[fname], N2U)
        elif 'Karthika-Bold' in bf:
            b2u_map[str(fname)] = get_b2u(fonts[fname], N2U_F2)
    try:
        cs = ContentStream(page.get('/Contents').get_object(), r)
    except:
        return ""
    cur_font = None
    out = []
    for operands, op in cs.operations:
        if op == b'Tf':
            cur_font = str(operands[0])
        elif op == b'Tj':
            t = operands[0]
            raw = t.original_bytes if hasattr(t, 'original_bytes') else bytes(str(t), 'latin1')
            b2u = b2u_map.get(cur_font)
            if b2u:
                s = ''.join(b2u.get(b, f'[{b}]') for b in raw)
                out.append(clean_text(s))
            else:
                out.append(str(t))
        elif op == b'TJ':
            parts = []
            for item in operands[0]:
                if hasattr(item, 'original_bytes') or isinstance(item, str):
                    raw = item.original_bytes if hasattr(item, 'original_bytes') else bytes(str(item), 'latin1')
                    b2u = b2u_map.get(cur_font)
                    if b2u:
                        s = ''.join(b2u.get(b, f'[{b}]') for b in raw)
                        parts.append(clean_text(s))
                    else:
                        parts.append(str(item))
            out.append(''.join(parts))
        elif op in (b'Td', b'TD', b'T*'):
            out.append('\n')
    return ''.join(out)

if __name__ == '__main__':
    pno = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    text = extract_page(pno)
    print(text)
    open(f'/tmp/page{pno}.txt', 'w').write(text)
