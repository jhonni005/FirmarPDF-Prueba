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

El binding Kotlin/JNI conserva esa procedencia, pero el motor vigente es PDFium
**158.0.8111.0**, distribución fija `chromium/8111` de bblanchon/pdfium-binaries:
https://github.com/bblanchon/pdfium-binaries/releases/tag/chromium/8111

Los cuatro `libpdfium.so` se sustituyen por los paquetes Android sin V8 de esa
publicación inmutable, sin modificar los binarios. `prebuilt/SHA256SUMS.txt`
permite comprobarlos. Los headers públicos se toman de los mismos paquetes y
son idénticos entre sus cuatro ABI; `include/utils` sigue siendo el auxiliar
Android del binding. No se recompila PDFium. CMake importa esos
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
  En el motor actual `FPDFPage_InsertObject` devuelve FPDF_BOOL y consume el objeto:
  lo transfiere a la página si tiene éxito y lo libera si falla. Se comprueba el
  resultado, se anula el propietario RAII en ambos casos para evitar doble
  liberación y se conservan los controles de conteo/identidad tras el éxito.
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

## Motor corregido y trazabilidad

El usuario comunicó un incremental sin firma, pese a success=true, con
198855860 bytes de entrada y 198856032 de salida. La revisión del binario anterior
arm64/x86_64 aportada por él identifica la lógica antigua de InitNewObjNumOffsets.
El SHA exacto del motor embebido en la etiqueta del binding no estaba documentado;
no se confunde el commit del binding con un commit PDFium. Sus hashes anteriores
quedan registrados en el manifiesto de procedencia para conservar esa referencia.

La corrección upstream es `2ddec2c6db6a368f166b5dd1039560dc322cb59b`, del 10/09/2025:
https://pdfium.googlesource.com/pdfium/+/2ddec2c6db6a368f166b5dd1039560dc322cb59b

Antes, el modo incremental saltaba todos los objetos. Después se conservan los
objetos cargados/nuevos en el listado incremental y se excluyen objetos anteriores
solo para el recorrido no incremental. La referencia posterior `a25c77d...` ya
contiene ese arreglo, pero corresponde a un cambio de flags del 11/02/2026, no al
primer commit corrector.

Procedencia y comprobaciones se conservan en `provenance/chromium-8111/ENGINE.json`,
`release.json`, `compatibility-audit.json`, `apk-verification.json`, configuración `args.gn` por ABI,
scripts/parches del constructor, metadatos oficiales y la implementación del
creador de la revisión seleccionada. No se utiliza una URL flotante latest.

- Fuente PDFium: rama chromium/8111, revisión resuelta
  `a50f1a5953264de698f398fe3a2ef6153677fafa`, también último commit de fuente
  enumerado por la publicación. La implementación oficial de esa revisión
  conserva la corrección. Los headers fpdf_edit/fpdf_save y el fpdfview original
  se contrastaron byte a byte con ella.
- Constructor: bblanchon/pdfium-binaries, commit
  `2de99cdab0cd84493538ce141342515b95ebb15c`; publicación inmutable del 19/10/2026,
  release ID 417611707. Los digests de los cuatro archivos tgz coinciden con la
  API de esa publicación y los subjects de su atestación publicada. Se conserva
  el bundle Sigstore; no se ejecutó verificación criptográfica de su firma/identidad.
  La atestación identifica el constructor, no el checkout transitivo de PDFium;
  esa distinción y la base de la revisión resuelta quedan explícitas en ENGINE.json.
- args conocidos: min SDK 23, standalone, release, sin V8/XFA/PartitionAlloc,
  is_component_build=false, target_os=android y CPU arm/arm64/x86/x64.
  Los parches publicados habilitan biblioteca compartida/exports públicos;
  no parchean InitNewObjNumOffsets ni se añaden parches binarios propios.
- `.note.android.ident` identifica API 23 y NDK r30 build 16248370. El puente
  sigue compilándose con NDK r26d/CMake 3.22.1 y el anfitrión no cambia de versiones.
  Las importaciones fuertes se resuelven contra los stubs de API 28 de cada ABI.
- En las cuatro ABI están presentes los 80 símbolos PDFium usados por el puente.
  La comparación de sus declaraciones detectó únicamente el cambio real de retorno
  de FPDFPage_InsertObject y un cambio de nombre de parámetro en FPDF_SaveAsCopy.
  El enlace final vuelve a comprobar los símbolos del puente adaptado.
- Dependencias nativas: libdl.so, libm.so y libc.so. No requiere libc++_shared.so;
  el motor lleva su runtime C++ y el puente conserva c++_static. La frontera es
  la API C pública, sin intercambiar objetos C++ entre ambos runtimes.
- Los segmentos LOAD arm64-v8a/x86_64 son de 16 KB. Los de armeabi-v7a/x86
  conservan 4 KB; el puente propio mantiene 16 KB en todas sus ABI. La comprobación
  de empaquetado del APK no sustituye una prueba en un dispositivo de 16 KB.

La licencia Apache del binding se conserva en LICENSE; el MIT del distribuidor
en prebuilt/LICENSE.distribution y el BSD de PDFium y sus terceros en
prebuilt/licenses. No cambian inserción/geometría, sincronización, búfer, métricas,
errores, cancelación, destinos ni los tres modos de comparación.

Comprobación mínima que debe ejecutar el usuario: abrir un PDF pequeño original,
colocar una firma, guardar con PDFium incremental, y reabrir el archivo NUEVO en
un visor externo para confirmar que la firma persiste. También comprobar páginas
originales, posición/tamaño/transparencia y original intacto. Esta reapertura no
se incorpora al botón Guardar ni se confunde success=true con persistencia visual.
Estado y verificaciones realmente realizadas: PDFIUM-ENGINE-001 en BACKLOG.

Las decisiones del recorrido están en `docs/ARQUITECTURA_FIRMA_PDF.md` §25 y los
resultados de compilación, límites y comprobaciones pendientes en `BACKLOG.md`,
ID `PDFIUM-EXPORT-001`.

Desde la raíz, `python scripts/verify_pdfium_apk.py` comprueba los hashes de los
precompilados y que cada motor empaquetado coincida byte a byte con ellos,
entradas nativas únicas/ABI, segmentos ELF y offsets ZIP del APK
debug generado. Es una inspección estática, sin cargar bibliotecas ni instalar.
