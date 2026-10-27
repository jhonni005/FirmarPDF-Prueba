# Diagnóstico externo del crecimiento incremental

Ejecutar desde la raíz de **FirmarPDF - PdfiumPrueba**, con Python 3.10 o superior.
No usa Android, JNI ni PDFium; no modifica ni repara los PDFs. La dependencia es
exclusiva de esta herramienta, sin cambios en las versiones de la aplicación.

Instalación aislada en la copia de prueba:

```powershell
python -m pip install --target .gradle/pdf-diagnostics/python -r scripts/requirements-pdf-diagnostics.txt
```

La herramienta utiliza pypdf 6.21.2 del entorno activo o, si no está instalado,
de esa carpeta local. Si existe otra versión en el entorno activo, usar un
entorno aislado con la versión fijada. La selección exacta permite comprobar
los pocos accesos internos del parser: `_startxref`, `_data` y su caché.

Para analizar los dos archivos reales, introduciendo sus rutas sin suponer
dónde se encuentran:

```powershell
$originalPdf = Read-Host 'Ruta del PDF original'
$incrementalPdf = Read-Host 'Ruta del resultado PDFium incremental'
python -B scripts/analyze_pdf_incremental.py $originalPdf $incrementalPdf --decoded --report .gradle/pdf-diagnostics/informe-incremental.json
```

El informe se crea exclusivamente si el nombre de salida no existe. Elegir otro
nombre para una segunda ejecución; nunca se sobrescriben entradas ni informes.
Omitir `--report` para imprimir el JSON. Omitir `--decoded` para comparar solo
bytes codificados, con menos trabajo de descompresión. `--top 30` amplía el
resumen de streams; la lista `objects` incluye todos los objetos activos nuevos
o redefinidos del ámbito analizado.

Lectura del informe:

- `sizes` y `original_is_exact_prefix`: tamaños, diferencia y comparación exacta
  del prefijo por bloques de 1 MiB. El tamaño por sí solo no prueba duplicación.
- `result_xref_chain[0]`: `startxref` final, tipo table/stream/hybrid, `/Prev` y
  enlace híbrido `/XRefStm`. La cadena recorre revisiones reales, sin buscar
  cabeceras de objeto mediante expresiones regulares.
- `original_final_xref_in_result_chain`: si la revisión original está enlazada
  desde el resultado que conserva ese prefijo. Prefijo y cadena son evidencias
  distintas; no se infiere una de la otra.
- `objects`: número y generación, estado `new`/`redefined`, generación anterior,
  tipo técnico, almacenamiento directo o dentro de `ObjStm` y localización
  física. Los objetos libres no resucitan desde revisiones anteriores.
- `appended_stream_totals_by_type` y `top_streams`: tamaños de payload de imagen,
  contenido de página, formulario XObject, fuentes, `ObjStm`, xref y otros streams.
  `PageContent` se identifica por referencias `/Contents`, incluidas referencias
  a arrays; no se confunde con el número de objetos gráficos de PDFium.
- `encoded_bytes`/`encoded_sha256`: payload codificado completo, excluidos los
  delimitadores `stream`/`endstream` y el diccionario. Los campos
  `same_object_encoded_equal` comparan con la definición original del mismo
  número; `encoded_matches_original_*` también detectan payload repetido bajo
  otros números. Se guardan cantidad de coincidencias y hasta diez referencias.
- `--decoded`: hashes adicionales de datos sin filtros o tras Flate y sus
  predictores. Permite detectar recompresión de contenido equivalente en esos
  casos. No decodifica DCT/JPX/JBIG2 ni afirma comparar píxeles renderizados.
  `decoded_status` distingue filtros no tratados/límites/errores y
  `decoded_comparison_complete` indica si se pudo cubrir todo ese análisis opcional.
  Un hash igual de payload no garantiza que diccionarios o apariencia sean iguales.
- `growth`: bytes codificados de streams activos escritos después del límite del
  original, bytes repetidos y resto. El resto incluye diccionarios, sintaxis,
  xref, trailers y objetos sobrescritos en revisiones añadidas anteriores; no es
  una medida de CPU. Con varias revisiones añadidas se enumeran las definiciones
  efectivas finales, no cada versión física intermedia. Con prefijo diferente
  no se atribuye el crecimiento a un incremental y los números de objeto podrían
  haber sido reasignados.

Solo se emiten números, categorías fijas, tamaños y hashes. No se vuelcan texto,
nombres, rutas, URI, valores arbitrarios de diccionarios, imágenes ni firmas.
Se silencian los mensajes del parser que podrían contener esos valores. Los
errores se informan por categoría; código de salida 2 si el análisis estructural
falla o queda incompleto. `complete` se refiere al inventario estructural y de
payload codificado, no al análisis opcional decodificado ni a la validez visual.

Los archivos se abren como flujos de solo lectura, evitando la copia completa
que hace `PdfReader` si se le pasa directamente una ruta. Se liberan las cachés
después de cada objeto; se conservan metadatos/hashes, no todos los streams
decodificados. Un stream individual o `ObjStm` sí ocupa memoria. El parser limita
la longitud declarada de cada stream a 75.000.000 bytes; la descompresión Flate
se limita por defecto a 32 MiB (`--decode-limit-mib`). Superar un límite se informa,
sin inventar tamaños/hashes. Se usa modo estricto y se rechazan PDFs cifrados.
El análisis puede ser lento: inspecciona los objetos de ambos archivos y no se
incluye en el botón Guardar ni en las métricas de exportación.

Comprobación controlada del analizador:

```powershell
python -B scripts/test_analyze_pdf_incremental.py
```

Las pruebas generan PDFs sintéticos dentro de `.gradle/pdf-diagnostics`, comprueban
xref clásicos, xref streams, híbridos, `ObjStm`, generaciones, objetos libres,
payload binario con falsas cabeceras, recompresión, límites y privacidad. No
ejecutan el motor PDFium ni reproducen el documento real de 190 MB. Su resultado
y el estado de la investigación se registran en `BACKLOG.md`, PDFIUM-GROWTH-001.

Para una comparación controlada, generar FULL e INCREMENTAL con el **mismo motor
8111**, mismo original y mismas firmas/posiciones. Cada intento debe abrir su
documento independiente desde el original, con un destino nuevo; no usar una
exportación previa como entrada. Registrar ambos resúmenes `PdfiumExportTiming`
y analizar original/incremental. Comparar la salida FULL nueva por separado; el
tamaño FULL histórico no prueba identidad de los bytes de la revisión añadida.
PDFBox queda como tercera referencia, también sobre el original y el mismo borrador.

Referencia del parser: https://pypdf.readthedocs.io/en/6.21.2/dev/pypdf-parsing.html
