#!/usr/bin/env python3
"""Generate synthetic stress-test EPUBs. Standard library only, deterministic.

Usage: python -I tools/gen_stress.py OUTDIR
"""
import os
import random
import struct
import sys
import zipfile
import zlib

WORDS = ("the of and to in a is that for it as was with be by on not he I this are or his from at "
         "which but have an they you were her she there one all we their been has more if will would "
         "lantern harbor quietly window morning river silver garden distant letter evening stone bridge "
         "orchard thunder whisper meadow candle journey kingdom mirror shadow forest winter summer").split()

CONTAINER = ('<?xml version="1.0" encoding="UTF-8"?>\n'
             '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
             '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
             '</rootfiles></container>')


def para(rng, n_words):
    w = [rng.choice(WORDS) for _ in range(n_words)]
    w[0] = w[0].capitalize()
    return " ".join(w) + "."


def xhtml(title, body, epub3=True):
    ns = ' xmlns:epub="http://www.idpf.org/2007/ops"' if epub3 else ""
    head = '<?xml version="1.0" encoding="UTF-8"?>\n'
    if not epub3:
        head += ('<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" '
                 '"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">\n')
    return (f'{head}<html xmlns="http://www.w3.org/1999/xhtml"{ns}><head><title>{title}</title>'
            f'<link rel="stylesheet" type="text/css" href="style.css"/></head><body>{body}</body></html>')


CSS = "body{margin:0 1em;} h1{text-align:center;} p{text-indent:1.5em;margin:0;}"


def write_epub(path, title, items, spine, toc, cover=None, epub3=True, author="Corpus Generator",
               extra_files=None, opf_override=None):
    """items: list of (id, href, media_type, bytes); spine: list of ids; toc: list of (label, href, children)."""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", CONTAINER, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/style.css", CSS, compress_type=zipfile.ZIP_DEFLATED)
        for _id, href, _mt, data in items:
            ct = zipfile.ZIP_STORED if href.endswith((".png", ".jpg")) else zipfile.ZIP_DEFLATED
            z.writestr("OEBPS/" + href, data, compress_type=ct)
        manifest = ['<item id="css" href="style.css" media-type="text/css"/>']
        for _id, href, mt, _d in items:
            props = ' properties="cover-image"' if (epub3 and cover == _id) else ""
            manifest.append(f'<item id="{_id}" href="{href}" media-type="{mt}"{props}/>')
        # NCX (always, for EPUB2 compatibility)
        counter = [0]

        def navpoints(entries):
            out = []
            for label, href, kids in entries:
                counter[0] += 1
                out.append(f'<navPoint id="np{counter[0]}" playOrder="{counter[0]}"><navLabel><text>{label}'
                           f'</text></navLabel><content src="{href}"/>{navpoints(kids)}</navPoint>')
            return "".join(out)
        ncx = ('<?xml version="1.0" encoding="UTF-8"?>\n<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" '
               f'version="2005-1"><head><meta name="dtb:uid" content="urn:uuid:{title}"/></head>'
               f'<docTitle><text>{title}</text></docTitle><navMap>{navpoints(toc)}</navMap></ncx>')
        z.writestr("OEBPS/toc.ncx", ncx, compress_type=zipfile.ZIP_DEFLATED)
        manifest.append('<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>')
        if epub3:
            def ol(entries):
                if not entries:
                    return ""
                return "<ol>" + "".join(f'<li><a href="{h}">{l}</a>{ol(k)}</li>' for l, h, k in entries) + "</ol>"
            nav = xhtml("Contents", f'<nav epub:type="toc" id="toc"><h1>Contents</h1>{ol(toc)}</nav>')
            z.writestr("OEBPS/nav.xhtml", nav, compress_type=zipfile.ZIP_DEFLATED)
            manifest.append('<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>')
        for name, data in (extra_files or {}).items():
            z.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
        spine_xml = "".join(f'<itemref idref="{s}"/>' for s in spine)
        cover_meta = f'<meta name="cover" content="{cover}"/>' if cover else ""
        ver = "3.0" if epub3 else "2.0"
        modified = '<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>' if epub3 else ""
        opf = opf_override or (
            f'<?xml version="1.0" encoding="UTF-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" '
            f'version="{ver}" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
            f'<dc:identifier id="uid">urn:uuid:corpus-{title.replace(" ", "-")}</dc:identifier>'
            f'<dc:title>{title}</dc:title><dc:creator>{author}</dc:creator><dc:language>en</dc:language>'
            f'{modified}{cover_meta}</metadata><manifest>{"".join(manifest)}</manifest>'
            f'<spine toc="ncx">{spine_xml}</spine></package>')
        z.writestr("OEBPS/content.opf", opf, compress_type=zipfile.ZIP_DEFLATED)


def png(width, height, seed, noise_rows=200):
    """Gradient PNG with a band of noise so it is not trivially compressible."""
    rng = random.Random(seed)
    rows = []
    r0, g0, b0 = rng.randrange(256), rng.randrange(256), rng.randrange(256)
    for y in range(height):
        if y < noise_rows:
            rows.append(b"\x00" + rng.randbytes(width * 3))
        else:
            c = bytes(((r0 + y) % 256, (g0 + y // 2) % 256, (b0 + y // 3) % 256))
            rows.append(b"\x00" + c * width)
    raw = zlib.compress(b"".join(rows), 6)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", raw) + chunk(b"IEND", b""))


def cover_item(title, seed):
    return ("cover", "cover.png", "image/png", png(600, 900, seed, noise_rows=60))


def huge_single(out):
    rng = random.Random(1)
    parts, toc, size, sec = [], [], 0, 0
    while size < 10 * 1024 * 1024:
        if size // (200 * 1024) >= sec:  # a heading roughly every 200 KB
            sec += 1
            h = f'<h2 id="s{sec}">Section {sec}</h2>'
            parts.append(h)
            toc.append((f"Section {sec}", f"huge.xhtml#s{sec}", []))
            size += len(h)
        p = f"<p>{para(rng, rng.randint(40, 120))}</p>\n"
        if sec == 40 and "MARKERWORDHUGE" not in "".join(parts[-3:]):
            p = f"<p>The unique search token MARKERWORDHUGE appears here. {para(rng, 30)}</p>\n"
        parts.append(p)
        size += len(p)
    body = '<h1>Huge Single Chapter</h1>' + "".join(parts)
    items = [cover_item("huge", 1), ("c1", "huge.xhtml", "application/xhtml+xml", xhtml("Huge", body).encode())]
    write_epub(os.path.join(out, "stress-huge-single-chapter.epub"), "Stress Huge Single Chapter",
               items, ["c1"], toc, cover="cover")


def many_chapters(out, n=2000):
    rng = random.Random(2)
    items, spine, toc = [cover_item("many", 2)], [], []
    part = None
    for i in range(1, n + 1):
        body = f'<h1 id="top">Chapter {i}</h1>' + "".join(f"<p>{para(rng, rng.randint(20, 60))}</p>"
                                                          for _ in range(rng.randint(1, 4)))
        if i == 1777:
            body += "<p>The unique search token MARKERWORDMANY appears here.</p>"
        href = f"ch{i:04d}.xhtml"
        items.append((f"c{i}", href, "application/xhtml+xml", xhtml(f"Chapter {i}", body).encode()))
        spine.append(f"c{i}")
        if (i - 1) % 100 == 0:  # nested TOC: parts of 100 chapters
            part = (f"Part {(i - 1) // 100 + 1}", href, [])
            toc.append(part)
        part[2].append((f"Chapter {i}", href, []))
    write_epub(os.path.join(out, "stress-2000-chapters.epub"), "Stress 2000 Chapters", items, spine, toc,
               cover="cover")


def many_images(out, n=200):
    rng = random.Random(3)
    items, spine, toc = [cover_item("img", 3)], [], []
    for i in range(1, n + 1):
        img = png(1600, 2400, 1000 + i, noise_rows=120)
        items.append((f"img{i}", f"images/p{i:03d}.png", "image/png", img))
        body = (f'<h2>Plate {i}</h2><div style="text-align:center"><img src="images/p{i:03d}.png" '
                f'alt="Plate {i}" style="max-width:100%"/></div><p>{para(rng, 40)}</p>')
        items.append((f"c{i}", f"plate{i:03d}.xhtml", "application/xhtml+xml", xhtml(f"Plate {i}", body).encode()))
        spine.append(f"c{i}")
        toc.append((f"Plate {i}", f"plate{i:03d}.xhtml", []))
    write_epub(os.path.join(out, "stress-200-images.epub"), "Stress 200 Images", items, spine, toc, cover="cover")


def epub2_ncx(out):
    rng = random.Random(4)
    items, spine, toc = [cover_item("e2", 4)], [], []
    for i in range(1, 21):
        body = f"<h1>Chapter {i}</h1>" + "".join(f"<p>{para(rng, 60)}</p>" for _ in range(30))
        items.append((f"c{i}", f"ch{i}.xhtml", "application/xhtml+xml",
                      xhtml(f"Chapter {i}", body, epub3=False).encode()))
        spine.append(f"c{i}")
        toc.append((f"Chapter {i}", f"ch{i}.xhtml", []))
    write_epub(os.path.join(out, "epub2-ncx-only.epub"), "EPUB2 NCX Only", items, spine, toc, cover="cover",
               epub3=False)


def no_cover(out):
    rng = random.Random(5)
    items, spine, toc = [], [], []
    for i in range(1, 6):
        body = f"<h1>Chapter {i}</h1>" + "".join(f"<p>{para(rng, 60)}</p>" for _ in range(20))
        items.append((f"c{i}", f"ch{i}.xhtml", "application/xhtml+xml", xhtml(f"Chapter {i}", body).encode()))
        spine.append(f"c{i}")
        toc.append((f"Chapter {i}", f"ch{i}.xhtml", []))
    write_epub(os.path.join(out, "no-cover.epub"), "No Cover Book", items, spine, toc)


def footnotes(out):
    """Footnotes (epub:type noteref/footnote), internal links, external link, nested TOC with anchors."""
    rng = random.Random(6)
    body1 = "<h1>Notes Test</h1>"
    for i in range(1, 11):
        body1 += (f'<p>{para(rng, 50)}<a epub:type="noteref" href="notes.xhtml#n{i}" id="r{i}">{i}</a> '
                  f'{para(rng, 40)}</p>')
    body1 += '<p>See <a href="ch2.xhtml#target">the target in chapter two</a> and an ' \
             '<a href="https://www.gutenberg.org/">external website</a>.</p>'
    body1 += '<p>Inline aside note<a epub:type="noteref" href="#a1">*</a>.</p>' \
             '<aside epub:type="footnote" id="a1"><p>This is an aside footnote in the same file.</p></aside>'
    body2 = "<h1>Chapter Two</h1>" + "".join(f"<p>{para(rng, 60)}</p>" for _ in range(30)) + \
            '<p id="target"><b>This is the link target paragraph.</b></p>' + \
            "".join(f"<p>{para(rng, 60)}</p>" for _ in range(10))
    notes = "<h1>Notes</h1>" + "".join(
        f'<aside epub:type="footnote" id="n{i}"><p><a href="ch1.xhtml#r{i}">{i}.</a> Footnote number {i} text.</p></aside>'
        for i in range(1, 11))
    items = [cover_item("fn", 6),
             ("c1", "ch1.xhtml", "application/xhtml+xml", xhtml("Notes Test", body1).encode()),
             ("c2", "ch2.xhtml", "application/xhtml+xml", xhtml("Two", body2).encode()),
             ("n", "notes.xhtml", "application/xhtml+xml", xhtml("Notes", notes).encode())]
    toc = [("Notes Test", "ch1.xhtml", []), ("Chapter Two", "ch2.xhtml", [("Target", "ch2.xhtml#target", [])]),
           ("Notes", "notes.xhtml", [])]
    write_epub(os.path.join(out, "footnotes-links.epub"), "Footnotes and Links", items, ["c1", "c2", "n"], toc,
               cover="cover")


def malformed(out):
    rng = random.Random(7)
    good = xhtml("Good", "<h1>Readable Chapter</h1>" + "".join(f"<p>{para(rng, 50)}</p>" for _ in range(20)))
    # Undeclared entities, unclosed tags, stray ampersands, mismatched nesting.
    bad = ('<?xml version="1.0" encoding="UTF-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml"><head>'
           '<title>Bad</title></head><body><h1>Bad XML Chapter</h1><p>Entity&nbsp;test &mdash; &bogus; '
           'and a stray & ampersand.<p>Unclosed paragraph <b>bold <i>mismatched</b> nesting</i>'
           '<br><img src="missing.png">' + "".join(f"<p>{para(rng, 50)}</p>" for _ in range(10)) + '</body>')
    items = [("c1", "good.xhtml", "application/xhtml+xml", good.encode()),
             ("c2", "bad.xhtml", "application/xhtml+xml", bad.encode()),
             ("c4", "good2.xhtml", "application/xhtml+xml", good.encode())]
    # Manifest lists a file that is not in the zip (c3), spine references an id not in the manifest (ghost),
    # TOC points to a missing file and to a missing anchor.
    opf_items = "".join(f'<item id="{i}" href="{h}" media-type="{m}"/>' for i, h, m, _ in items)
    opf_items += '<item id="c3" href="missing-chapter.xhtml" media-type="application/xhtml+xml"/>'
    opf_items += '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
    opf = ('<?xml version="1.0" encoding="UTF-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="2.0" '
           'unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
           '<dc:identifier id="uid">malformed-1</dc:identifier><dc:title>Malformed &amp; Broken</dc:title>'
           '<meta name="cover" content="nonexistent-cover"/></metadata>'
           f'<manifest>{opf_items}</manifest><spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/>'
           '<itemref idref="c3"/><itemref idref="ghost"/><itemref idref="c4"/></spine></package>')
    toc = [("Good", "good.xhtml", []), ("Bad XML", "bad.xhtml", []), ("Missing", "missing-chapter.xhtml", []),
           ("Missing anchor", "good2.xhtml#nope", [])]
    write_epub(os.path.join(out, "malformed-missing-files-bad-xml.epub"), "Malformed", items, [], toc,
               epub3=False, opf_override=opf)

    # OPF that is not well-formed XML at all.
    write_epub(os.path.join(out, "malformed-broken-opf.epub"), "Broken OPF",
               [("c1", "good.xhtml", "application/xhtml+xml", good.encode())], ["c1"], [],
               opf_override='<?xml version="1.0"?><package version="3.0"><metadata><dc:title>Broken OPF'
                            '</metadata><manifest><item id="c1" href="good.xhtml"</manifest>')
    # No META-INF/container.xml.
    with zipfile.ZipFile(os.path.join(out, "malformed-no-container.epub"), "w") as z:
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("OEBPS/good.xhtml", good)
    # Not a zip at all.
    with open(os.path.join(out, "malformed-not-a-zip.epub"), "wb") as f:
        f.write(b"This file is plain text pretending to be an EPUB.\n" * 50)
    # Truncated zip: valid book with its tail cut off.
    src = os.path.join(out, "no-cover.epub")
    data = open(src, "rb").read()
    with open(os.path.join(out, "malformed-truncated.epub"), "wb") as f:
        f.write(data[: len(data) * 2 // 3])
    # Empty spine.
    write_epub(os.path.join(out, "malformed-empty-spine.epub"), "Empty Spine", [], [], [])


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    no_cover(out)
    epub2_ncx(out)
    footnotes(out)
    malformed(out)
    huge_single(out)
    many_chapters(out)
    many_images(out)
    for f in sorted(os.listdir(out)):
        print(f"{os.path.getsize(os.path.join(out, f)):>12}  {f}")


if __name__ == "__main__":
    main()
