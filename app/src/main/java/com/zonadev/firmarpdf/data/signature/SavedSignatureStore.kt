package com.zonadev.firmarpdf.data.signature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** PNG privados: cada guardado añade una firma. La firma antigua conserva el ID "signature". */
class SavedSignatureStore(private val directory: File) {
    private val mutex = Mutex()

    /** Devuelve el ID solo después de publicar el PNG completo. */
    suspend fun save(strokes: List<SignatureStroke>, aspectRatio: Float): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo crear el directorio de firmas")
            var id: String
            var target: File
            do {
                id = UUID.randomUUID().toString()
                target = signatureFile(id)
            } while (target.exists())
            val bitmap = renderSignature(strokes, aspectRatio)
            var temporary: File? = null
            try {
                val pending = File.createTempFile(".signature-", ".tmp", directory)
                temporary = pending
                FileOutputStream(pending).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("No se pudo codificar la firma")
                    output.flush()
                    output.fd.sync()
                }
                // Publicar en el mismo directorio; nunca escribir sobre una firma existente.
                if (target.exists() || !pending.renameTo(target)) throw IOException("No se pudo publicar la firma")
                id
            } finally {
                temporary?.delete()
                bitmap.recycle()
            }
        }
    }

    /** IDs estables, incluido signature.png; los temporales incompletos quedan fuera. */
    suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!directory.exists()) return@withLock emptyList()
            val files = directory.listFiles() ?: throw IOException("No se pudieron listar las firmas")
            files.filter { it.isFile && it.extension == "png" && validId(it.nameWithoutExtension) }
                .map { it.nameWithoutExtension }.sorted()
        }
    }

    /** null si falta. Con maxDimension se decodifica muestreado, sin cargar primero el PNG completo. */
    suspend fun load(id: String, maxDimension: Int? = null): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = signatureFile(id)
            if (!file.exists()) return@withLock null
            require(maxDimension == null || maxDimension > 0)
            val options = BitmapFactory.Options()
            if (maxDimension != null) {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("No se pudo leer la firma")
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight).toLong() > maxDimension.toLong() * options.inSampleSize) {
                    options.inSampleSize *= 2
                }
                options.inJustDecodeBounds = false
            }
            // Propiedad del llamador. No reciclar imágenes publicadas mientras Compose las utilice.
            BitmapFactory.decodeFile(file.path, options) ?: throw IOException("No se pudo leer la firma")
        }
    }

    /** Elimina solo el ID solicitado; false si ya no existe. */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = signatureFile(id)
            if (!file.exists()) return@withLock false
            if (!file.delete()) throw IOException("No se pudo borrar la firma")
            true
        }
    }

    private fun signatureFile(id: String): File {
        require(validId(id)) { "Identificador de firma inválido" }
        return File(directory, "$id.png")
    }

    private fun validId(id: String): Boolean = id == "signature" || UUID_ID.matches(id)

    private companion object {
        val UUID_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
