# Binding local de PDFium para PdfiumPrueba

Origen: https://github.com/johngray1965/PdfiumAndroidKt

Etiqueta: `v1.0.35`. Revisión exacta: `2052da7c071002446f1e2dff082b373a17ebadd0`.
La copia se obtuvo de esa etiqueta, no de main. Se incluyen únicamente `src/main`,
reglas ProGuard, licencia y README de referencia; no se incorpora la aplicación de
ejemplo, los módulos Arrow ni las herramientas de publicación de upstream.

`LICENSE` y los avisos en las fuentes/headers se conservan. `README.upstream.md`
es el README original, sin modificaciones; sus instrucciones de compilación no
sustituyen a la configuración local.

## Configuración local

El proyecto anfitrión incorpora `:pdfium-local` mediante esta carpeta, en lugar
del AAR Maven `io.legere:pdfiumandroid:1.0.35`. Esta es una excepción explícita,
solicitada para este experimento, a la regla general de un único módulo `:app`.

Se conservan AGP 8.9.2, Kotlin 2.2.0, Gradle 8.11.1 y Java/JVM 11 del anfitrión.
La etiqueta upstream declara NDK 29.0.13846066; aquí se usa explícitamente el NDK
instalado 26.3.11579264 y CMake 3.22.1. El binding se compila como C++17 con STL
estática, sin una segunda `libc++_shared.so`. Se omiten los plugins upstream de
publicación, formato, cobertura y análisis. Guava 33.5.0-android se conserva como
en el AAR; coroutines 1.10.2 y annotation-jvm 1.9.1 son dependencias compileOnly.

Los cuatro `libpdfium.so` de la etiqueta se conservan sin modificar en `prebuilt`.
`SHA256SUMS.txt` permite comprobarlos. No se recompila PDFium. CMake importa esos
binarios para enlazar; el sourceSet `jniLibs` apunta explícitamente a `prebuilt`
para empaquetarlos una sola vez junto al puente compilado.
El enlace original con `jnigraphics`, `android` y `log` se conserva.
El puente propio usa `max-page-size=16384` y `common-page-size=16384` en el linker.
Los precompilados de 64 bits de la etiqueta tienen segmentos LOAD de 16 KB; los
de 32 bits conservan su alineación original de 4 KB. No se generaliza la
compatibilidad a partir de una compilación: el dispositivo/emulador de 16 KB
y el APK completo tienen sus comprobaciones registradas en BACKLOG.

## Modificaciones del binding

- `PdfPage.insertImage`: API sincronizada con `PdfiumCore.lock`, Bitmap de software
  ARGB_8888/sRGB y matriz PDF completa de seis coeficientes sobre el cuadrado unitario.
  No expone nuevos punteros a la app ni modifica píxeles del llamador.
- `PdfPage.generateContent`: llamada separada para generar una vez por página.
- `PdfDocument.getSignatureExportRestriction`: consulta nativa de cifrado/permisos
  y firmas digitales antes de editar. No es un validador estructural del PDF.
- `signature_export.inc`: extensión del puente existente que obtiene el verdadero
  `FPDF_DOCUMENT` de `DocumentFile`; usa los contratos de los headers incluidos.
  `FPDFPage_InsertObject` devuelve void; se comprueba el conteo y la identidad del
  objeto insertado y se respeta la transferencia de propiedad a la página.
- La conversión lee filas RGBA con su stride Android y escribe filas BGRA con el
  stride PDFium. Despremultiplica con redondeo/clamp cuando corresponde, pone RGB
  a cero con alfa cero y conserva alfa parcial. El intercambio R/B ocurre una vez.
  `Bitmap.hasAlpha/isPremultiplied` aporta el estado también en API 28/29; desde
  API 30 se contrasta con AndroidBitmapInfo.flags. Formatos/hardware/espacios de
  color incompatibles se rechazan. RAII equilibra lock/unlock y libera solo recursos
  propios; el bitmap nativo usa memoria propiedad de PDFium, nunca memoria Android.
- `FileWrite`: se devuelven cero y estado fallido ante errores de asignación,
  escritura o excepciones JNI; se liberan las referencias locales de cada bloque.
  `nativeSaveAsCopy` comprueba también ese estado y conserva excepciones pendientes.
- `initLibraryIfNeed`: se elimina el unlock explícito además de lock_guard, que
  suponía desbloquear dos veces el mismo mutex al abrir documentos.
- `getBlock`/FPDF_FILEACCESS: contexto DocumentFile propio, lectura completa con
  pread64 en bucle, reintento EINTR y fallo ante EOF anticipado/error/rango inválido,
  sin modificar la posición compartida del descriptor. fstat64 y validación del
  límite unsigned long evitan truncar tamaño/offset en las ABI de 32 bits.
- `PdfDocument.saveAsCopy` conserva el lock global y mide su espera; el puente mide
  FPDF_SaveAsCopy, lecturas de origen y callbacks de salida/preparación JNI.
  Diagnóstico explícito por documento, solo activado por el exportador debug y
  rechazado con NDEBUG; los contadores nativos se reinician por guardado y solo
  acumulan durante FPDF_SaveAsCopy. `PdfSaveDiagnostics` contiene la instantánea
  antes del cierre, también para fallos/cancelación. No hay logs por bloque ni
  reutilización de buffers JNI. Los intervalos se detallan en arquitectura §25 y
  su verificación en PERF-PDFIUM-002 de BACKLOG.

El exportador de la app conserva FULL como opción predeterminada y transmite
`FPDF_NO_INCREMENTAL` (2). Por autorización posterior, esta copia añade una acción
debug INCREMENTAL que transmite únicamente `FPDF_INCREMENTAL` (1), sin OR ni
FPDF_REMOVE_SECURITY (3). Ambos usan el mismo binding y un documento nuevo por
intento, abierto desde el URI original de solo lectura. Se entregan todos los
bloques del callback a un destino nuevo y vacío con búfer de 64 KiB; no hay copia
previa, descarte de prefijo, extracción de cola ni append sobre el original.
El modo incremental no reintenta FULL silenciosamente si falla.

Las lecturas completas/EINTR, excepciones, cancelación, cierre y diagnósticos
nativos conservan su recorrido. requestedSaveMode y saveFlags distinguen la
solicitud en la línea PdfiumExportTiming; no verifican el formato efectivo ni
garantizan una salida autónoma solo porque el motor devuelva éxito. El incremental
puede evitar parte de la reescritura, pero producir otro PDF que conserve el
documento original sigue teniendo coste y no implica emitir solo la imagen o
unos KB. Las comprobaciones externas y tiempos quedan en
PDFIUM-INCREMENTAL-001 de BACKLOG y la decisión en arquitectura §26.
El proyecto original y LectorDocumentos quedan fuera de esta excepción.
Los cambios no se publican upstream.

Las decisiones del recorrido están en `docs/ARQUITECTURA_FIRMA_PDF.md` §25 y los
resultados de compilación, límites y comprobaciones pendientes en `BACKLOG.md`,
ID `PDFIUM-EXPORT-001`.

Desde la raíz, `python scripts/verify_pdfium_apk.py` comprueba los hashes de los
precompilados, entradas nativas únicas/ABI, segmentos ELF y offsets ZIP del APK
debug generado. Es una inspección estática, sin cargar bibliotecas ni instalar.
