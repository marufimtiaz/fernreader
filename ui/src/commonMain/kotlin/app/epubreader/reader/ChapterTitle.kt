package app.epubreader.reader

import app.epubreader.reader.bridge.TocItem

/** The controls' chapter line: label of the contents entry for [currentHref], else the book title. */
fun chapterTitle(toc: List<TocItem>, currentHref: String?, bookTitle: String): String =
    currentHref?.let { h -> toc.firstOrNull { it.href == h }?.label?.trim()?.takeIf { it.isNotEmpty() } } ?: bookTitle
