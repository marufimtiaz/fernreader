package app.fernreader.core.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Stable row ids (F-115): random UUID v4 text. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()
