package com.elio.recipecraft.data.nutrition

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.elio.recipecraft.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Documents the user picked through the Storage Access Framework, by their content URI string. */
interface CsvDocuments {
    /** The file name the provider shows, or null when it gives none. */
    suspend fun displayName(uri: String): String?

    /** Throws [java.io.IOException] or [SecurityException] when the document cannot be opened. */
    fun open(uri: String): InputStream
}

class AndroidCsvDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CsvDocuments {

    override suspend fun displayName(uri: String): String? = withContext(ioDispatcher) {
        try {
            context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun open(uri: String): InputStream =
        context.contentResolver.openInputStream(uri.toUri()) ?: throw FileNotFoundException()
}
