package app.epubreader.core.data

import kotlinx.coroutines.flow.Flow

/** Library changes the library screen makes (F-024..F-026); [LibraryRepository] implements it, tests fake it. */
interface LibraryOps {
    fun collections(): Flow<List<CollectionEntity>>
    /** [name] must pass [CollectionNames.problem]; returns the new collection's id. */
    suspend fun createCollection(name: String): String
    suspend fun renameCollection(id: String, name: String)
    suspend fun deleteCollection(id: String)
    suspend fun addToCollection(id: String, bookIds: List<String>)
    suspend fun removeFromCollection(id: String, bookIds: List<String>)
    /** F-025 "remove from library only": hidden until un-hidden; scans keep it hidden. */
    suspend fun hide(bookIds: List<String>)
    /** F-025: the entries leave the library; with [deleteFiles] their local files are deleted too.  */
    suspend fun deleteBooks(bookIds: List<String>, deleteFiles: Boolean): DeleteResult
}
