#!/usr/bin/env python3
"""Per-spine-item text length and block-image count for an EPUB (S1 estimator input).
Usage: python3 -I spikes/tools/s1_textlen.py BOOK.epub > out.json
Output: [{"href":..., "linear": bool, "chars": n, "images": n}, ...] in spine order.
"""
import json, posixpath, re, sys, zipfile
from html.parser import HTMLParser


class Body(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.inbody = False; self.skip = 0; self.chars = 0; self.images = 0
    def handle_starttag(self, tag, attrs):
        t = tag.lower().split(':')[-1]
        if t == 'body': self.inbody = True
        if t in ('script', 'style'): self.skip += 1
        if self.inbody and t in ('img', 'svg', 'image'): self.images += 1
    def handle_endtag(self, tag):
        t = tag.lower().split(':')[-1]
        if t in ('script', 'style') and self.skip: self.skip -= 1
        if t == 'body': self.inbody = False
    def handle_data(self, d):
        if self.inbody and not self.skip: self.chars += len(d)


def main():
    z = zipfile.ZipFile(sys.argv[1])
    cont = z.read('META-INF/container.xml').decode()
    opf = re.search(r'full-path="([^"]+)"', cont).group(1)
    base = posixpath.dirname(opf)
    o = z.read(opf).decode('utf-8', 'replace')
    items = {m.group(1): m.group(2) for m in re.finditer(r'<item\b[^>]*?\bid="([^"]+)"[^>]*?\bhref="([^"]+)"', o)}
    items.update({m.group(2): m.group(1) for m in re.finditer(r'<item\b[^>]*?\bhref="([^"]+)"[^>]*?\bid="([^"]+)"', o)})
    out = []
    for m in re.finditer(r'<itemref\b([^>]*)>', o):
        attrs = m.group(1)
        idref = re.search(r'idref="([^"]+)"', attrs).group(1)
        linear = 'linear="no"' not in attrs
        href = posixpath.normpath(posixpath.join(base, items[idref]))
        p = Body(); p.feed(z.read(href).decode('utf-8', 'replace'))
        out.append({'href': href, 'linear': linear, 'chars': p.chars, 'images': p.images})
    json.dump(out, sys.stdout)


main()
