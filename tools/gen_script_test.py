#!/usr/bin/env python3
"""Writes script-test.epub: one chapter whose inline <script> replaces the text with "SCRIPT RAN" (R-007 check).

Usage: python3 -I tools/gen_script_test.py OUTDIR
"""
import os
import sys
import zipfile

OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">script-test</dc:identifier>
<dc:title>Script test</dc:title><dc:language>en</dc:language>
<meta property="dcterms:modified">2026-10-30T00:00:00Z</meta></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml" properties="scripted"/></manifest>
<spine><itemref idref="c1"/></spine></package>"""
NAV = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"
xmlns:epub="http://www.idpf.org/2007/ops"><head><title>nav</title></head><body>
<nav epub:type="toc"><ol><li><a href="c1.xhtml">Chapter</a></li></ol></nav></body></html>"""
C1 = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c1</title>
</head><body><h1 id="h">SCRIPTS ARE OFF</h1><p>If the heading above says SCRIPT RAN, book scripts executed.</p>
<script>document.getElementById('h').textContent = 'SCRIPT RAN';</script></body></html>"""
CONTAINER = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

out = os.path.join(sys.argv[1], "script-test.epub")
with zipfile.ZipFile(out, "w") as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    for name, data in (("META-INF/container.xml", CONTAINER), ("OEBPS/content.opf", OPF),
                       ("OEBPS/nav.xhtml", NAV), ("OEBPS/c1.xhtml", C1)):
        z.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
print(out)
