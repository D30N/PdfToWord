from PIL import Image, ImageDraw, ImageFont

W, H = 420, 900
RED = "#B3261E"; GREEN = "#0A7D2C"; INK = "#1A1C1E"; GREY = "#8A8A8A"
BG = "#FDF8F2"; TRACK = "#E4E4E4"

def font(sz, bold=False):
    p = "/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf" % ("-Bold" if bold else "")
    return ImageFont.truetype(p, sz)

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

# status bar
d.rectangle([0, 0, W, 28], fill="#000000")
d.text((16, 5), "9:41", font=font(13), fill="white")
d.text((W-70, 5), "5G  100%", font=font(13), fill="white")

# app bar
d.rectangle([0, 28, W, 128], fill=RED)
d.rounded_rectangle([18, 52, 62, 96], 10, fill="#8f1d17")
d.text((28, 60), "W", font=font(22, True), fill="white")
d.text((74, 52), "Pdf to Word by Deon", font=font(20, True), fill="white")
d.text((74, 80), "Malayalam PDF \u2192 Word converter", font=font(12), fill="#f5d9d6")

y = 148
# Select PDF button
d.rounded_rectangle([16, y, W-16, y+56], 12, fill=RED)
d.text((32, y+16), "\U0001F4C1  Select PDF", font=font(16, True), fill="white")
y += 70
# selected file card (dashed)
d.rounded_rectangle([16, y, W-16, y+72], 12, outline=GREY, width=2)
d.rounded_rectangle([30, y+16, 70, y+56], 10, fill="#fbe9e7")
d.text((40, y+24), "P", font=font(18, True), fill=RED)
d.text((82, y+18), "DOC-20260924-WA0263.pdf", font=font(13, True), fill=INK)
d.text((82, y+42), "PDF \u2022 Selected", font=font(11), fill=GREY)
y += 86
# keep original card
d.rounded_rectangle([16, y, W-16, y+64], 12, fill="white")
d.text((32, y+10), "Keep original file name", font=font(13), fill=INK)
d.text((32, y+32), "Output: DOC-20260924-WA0263.docx", font=font(11), fill=GREY)
d.rounded_rectangle([W-76, y+18, W-32, y+44], 13, fill=GREEN)
d.ellipse([W-58, y+20, W-36, y+42], fill="white")
y += 78
# convert button (dimmed - conversion running)
d.rounded_rectangle([16, y, W-16, y+56], 12, fill="#9db89f")
d.text((32, y+16), "\u2B07  Convert to Word file", font=font(16, True), fill="white")
y += 70
d.text((16, y), "Recent conversions", font=font(13, True), fill=INK)

# dim overlay
dim = Image.new("RGBA", (W, H), (0, 0, 0, 110))
img = Image.alpha_composite(img.convert("RGBA"), dim)
d = ImageDraw.Draw(img)

# progress dialog
dw, dh = 340, 300
dx, dy = (W-dw)//2, 300
d.rounded_rectangle([dx, dy, dx+dw, dy+dh], 18, fill="white")
d.text((dx+24, dy+22), "Converting\u2026", font=font(18, True), fill=INK)
d.text((dx+24, dy+52), "DOC-20260924-WA0263.pdf", font=font(12), fill=GREY)
# percent
d.text((dx+24, dy+92), "45%", font=font(34, True), fill=INK)
d.text((dx+110, dy+108), "completed", font=font(13), fill=GREY)
# bar
bx, by, bw, bh = dx+24, dy+150, dw-48, 16
d.rounded_rectangle([bx, by, bx+bw, by+bh], 8, fill=TRACK)
fw = int(bw * 0.45)
d.rounded_rectangle([bx, by, bx+fw, by+bh], 8, fill=GREEN)
d.text((dx+24, dy+180), "Reading page 18 of 40\u2026", font=font(13), fill=GREY)
d.text((dx+24, dy+206), "Colours, fonts and spacing are", font=font(12), fill="#b0b0b0")
d.text((dx+24, dy+224), "being preserved.", font=font(12), fill="#b0b0b0")

img.convert("RGB").save("/home/hatch/workspace/pdf-to-word/mockups/progress-bar.png")
print("saved")
