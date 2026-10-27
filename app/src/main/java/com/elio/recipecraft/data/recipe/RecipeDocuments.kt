package com.elio.recipecraft.data.recipe

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Export files the user picked through the Storage Access Framework, by their content URI string. */
interface RecipeDocuments {
    /** Throws [java.io.IOException] or [SecurityException] when the document cannot be opened. */
    fun openInput(uri: String): InputStream

    /** Opens the document for writing from the start, dropping what it held. Throws like [openInput]. */
    fun openOutput(uri: String): OutputStream
}

class AndroidRecipeDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
) : RecipeDocuments {

    override fun openInput(uri: String): InputStream =
        context.contentResolver.openInputStream(uri.toUri()) ?: throw FileNotFoundException()

    override fun openOutput(uri: String): OutputStream =
        context.contentResolver.openOutputStream(uri.toUri(), "wt") ?: throw FileNotFoundException()
}
