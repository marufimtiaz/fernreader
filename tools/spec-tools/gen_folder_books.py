#!/usr/bin/env python3
"""Generate small, uniquely titled EPUBs for folder-discovery tests.

Usage: python -I tools/gen_folder_books.py OUTDIR NAME [NAME ...]
Each NAME produces OUTDIR/NAME.epub titled "Folder Test NAME".
"""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_stress as g  # noqa: E402


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name in sys.argv[2:]:
        rng = random.Random(name)
        items = [g.cover_item(name, sum(map(ord, name)))]
        body = f"<h1>Folder Test {name}</h1>" + "".join(f"<p>{g.para(rng, 60)}</p>" for _ in range(15))
        items.append(("c1", "ch1.xhtml", "application/xhtml+xml", g.xhtml(name, body).encode()))
        g.write_epub(os.path.join(out, f"{name}.epub"), f"Folder Test {name}", items, ["c1"],
                     [("Start", "ch1.xhtml", [])], cover="cover")
        print(os.path.join(out, f"{name}.epub"))


if __name__ == "__main__":
    main()
