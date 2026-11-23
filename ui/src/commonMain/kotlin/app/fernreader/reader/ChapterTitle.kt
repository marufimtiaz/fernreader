package app.fernreader.reader

import app.fernreader.reader.bridge.TocItem

/** Label of the contents entry for [currentHref]; null when there is none or it is blank. */
fun chapterLabel(toc: List<TocItem>, currentHref: String?): String? =
    currentHref?.let { h -> toc.firstOrNull { it.href == h }?.label?.trim()?.takeIf { it.isNotEmpty() } }

/** A bookmark's or highlight's chapter: label of the contents entry for [currentHref], else the book title. */
fun chapterTitle(toc: List<TocItem>, currentHref: String?, bookTitle: String): String = chapterLabel(toc, currentHref) ?: bookTitle
