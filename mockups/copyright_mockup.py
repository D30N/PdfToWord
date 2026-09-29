from PIL import Image, ImageDraw, ImageFont

W, H = 720, 1500
RED = "#B3261E"; INK = "#1A1C1E"; GREY = "#8A8A8A"; BG = "#FDF8F2"

def font(sz, bold=False):
    p = "/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf" % ("-Bold" if bold else "")
    return ImageFont.truetype(p, sz)

def wrap_segs(draw, segs, maxw, fsz):
    words = []
    for text, b in segs:
        for w_ in text.split():
            words.append((w_, b))
    lines, cur, curw = [], [], 0
    for w_, b in words:
        f = font(fsz, True if b is True else False)
        ww = draw.textlength(w_ + " ", font=f)
        if curw + ww <= maxw:
            cur.append((w_, b)); curw += ww
        else:
            lines.append(cur); cur = [(w_, b)]; curw = ww
    if cur: lines.append(cur)
    return lines

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

# status bar (compact)
d.rectangle([0, 0, W, 40], fill="#5a5a5a")
d.text((20, 6), "10:31", font=font(18), fill="white")
d.text((W-120, 6), "5G+  100", font=font(18), fill="white")

# app bar (shrunk)
d.rectangle([0, 40, W, 140], fill=RED)
d.rounded_rectangle([28, 62, 84, 118], 14, fill="#8f1d17")
d.text((42, 70), "W", font=font(26, True), fill="white")
d.text((100, 62), "Pdf to Word by Deon", font=font(27, True), fill="white")
d.text((100, 96), "Malayalam PDF \u2192 Word converter", font=font(16), fill="#f5d9d6")

y = 158
d.text((36, y), "Settings", font=font(30, True), fill=INK); y += 56

# card 1 (compact)
c1_lines = ["Converts Malayalam PDFs to Word (.docx).",
            "\u2022 Line spacing is preserved from the PDF",
            "\u2022 Malayalam font is always applied",
            "\u2022 Files are saved to your Downloads folder",
            "Only text-based PDFs are supported."]
d.rounded_rectangle([36, y, W-36, y+296], 26, fill="white")
d.text((62, y+18), "Pdf to Word  1.0", font=font(23, True), fill=INK)
ty = y + 58
for line in c1_lines:
    d.text((62, ty), line, font=font(17), fill=GREY); ty += 30
y += 316

# card 2 : notice (compact, all in one screen)
FSZ = 19
paras = [
    ([("APP USAGE & COPYRIGHT NOTICE", True)], 21),
    ([("This application is free to use and share.", False)], FSZ),
    ([("All rights reserved.", True)], FSZ),
    ([("\u00a9 Deepak Deon", "link")], FSZ),
]
maxw = W - 124
all_lines, total_h = [], 0
for segs, fsz in paras:
    ls = wrap_segs(d, segs, maxw, fsz)
    all_lines.append((ls, fsz)); total_h += len(ls) * (fsz + 13) + 20
nav_h = 130
ch = total_h + 48
# shrink if overflowing
avail = H - nav_h - y - 24
if ch > avail:
    ch = avail
d.rounded_rectangle([36, y, W-36, y+ch], 26, fill="white")
ty = y + 24
for ls, fsz in all_lines:
    lh = fsz + 13
    for line in ls:
        if ty + lh > y + ch - 8: break
        x = 62
        for w_, b in line:
            is_link = (b == "link")
            f = font(fsz, True if b is True else False)
            col = "#1A73E8" if is_link else INK
            d.text((x, ty), w_ + " ", font=f, fill=col)
            if is_link:
                wpx = d.textlength(w_ + " ", font=f)
                d.line([x, ty + fsz + 6, x + wpx, ty + fsz + 6], fill=col, width=2)
            x += d.textlength(w_ + " ", font=f)
        ty += lh
    ty += 20

# bottom nav
d.rectangle([0, H-nav_h, W, H], fill="white")
d.line([0, H-nav_h, W, H-nav_h], fill="#e0e0e0", width=2)
for i, (icon, label, active) in enumerate([("\u2302", "Home", False), ("\U0001F4C1", "Files", False), ("\u2699", "Settings", True)]):
    x = W//6 + i*(W//3)
    col = RED if active else GREY
    d.text((x-12, H-nav_h+16), icon, font=font(30), fill=col)
    d.text((x-36, H-52), label, font=font(18, active), fill=col)

img.save("/home/hatch/workspace/pdf-to-word/mockups/copyright-notice.png")
print("saved", img.size, "card2:", ch)
