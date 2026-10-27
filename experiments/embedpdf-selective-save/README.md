# Guardado selectivo de EmbedPDF: experimento de escritorio

Experimento aislado para Windows x64, probado con Python 3.13.3. No integra ni
compila Android. `app/`, el binding Android y sus motores/exportadores se conservan.
La firma usada es **sintética**, transparente y autorizada expresamente para esta
prueba; no representa una firma personal.

## Requisitos y procedencia

- Python Windows x64; dependencias locales en `_local/python` según `requirements.txt`.
- Paquete oficial de https://github.com/embedpdf/runtime, publicación fija
  `runtime-5f7608ba4a634c94cd718067d6b678a45ba89088`.
- Revisión `5f7608ba4a634c94cd718067d6b678a45ba89088`, upstream
  `7b3f6f93625e6df97a7d9e677137a558df8648c4`.
- Archivo `libembedpdf-runtime-win32-x64-5f7608ba4.tar.gz`, SHA-256
  `4455b048953c3cc7411a47d2036ec1fb7d2740c6b0c596b9d51b6fdf6049b2d0`.
- DLL SHA-256 `dd6c2e9839f0bfbf23e45dae46879ec8eff92d5719bfbe785175c1b245a616df`.
- PE AMD64; importaciones observadas KERNEL32, ADVAPI32, GDI32 y USER32.
  La DLL se carga y el adaptador comprueba los símbolos y tamaños ctypes antes
  de abrir documentos. Los exports son stdcall y los callbacks C cdecl; en x64
  comparten ABI. Punteros de 64 bits y unsigned long de 32 bits.
- `provenance/ENGINE.json` conserva URL exacta, configuración GN, hashes, símbolos
  y límites de procedencia. `provenance/headers` y `provenance/licenses` conservan
  los headers/licencias correspondientes. El tag y BUILD-METADATA coinciden con
  la revisión. La publicación no está marcada inmutable: el digest está fijado;
  no se afirma verificación criptográfica de una atestación de compilación.

## Ejecución desde la raíz del proyecto, PowerShell

```powershell
python experiments/embedpdf-selective-save/prepare_runtime.py
python -m pip install --only-binary=:all: --target experiments/embedpdf-selective-save/_local/python -r experiments/embedpdf-selective-save/requirements.txt
python -B experiments/embedpdf-selective-save/make_fixtures.py

# Primero el control pequeño (una ejecución por modo).
python -B experiments/embedpdf-selective-save/run_experiment.py --original experiments/embedpdf-selective-save/_local/fixtures/small-original.pdf --signature experiments/embedpdf-selective-save/_local/fixtures/synthetic-signature.png --synthetic-signature --label synthetic-small --repeat-after-verified 0

# Después el original real. Introducir su ruta absoluta, sin cambiar de archivo.
$originalPdf = Read-Host 'Ruta absoluta del PDF original de 198855860 bytes'
python -B experiments/embedpdf-selective-save/run_experiment.py --original $originalPdf --signature experiments/embedpdf-selective-save/_local/fixtures/synthetic-signature.png --synthetic-signature --expected-input-bytes 198855860 --page 1 --x 0.1 --y 0.1 --width 0.3 --label real-large --repeat-after-verified 3
```

Admite otros `--original`, `--signature`, `--page` (desde 1), `--x`, `--y`, `--width`
y `--height`. La geometría es normalizada sobre el área visible ya girada, con
origen superior izquierdo. Sin `--height` se conserva la proporción del PNG.
El informe guarda altura concreta, CropBox/MediaBox efectivo, rotación y matriz.
`--synthetic-signature` identifica explícitamente esta imagen de prueba; omitirlo
si posteriormente se usa un PNG proporcionado. No se sustituye el PDF real.

## Recorrido y archivos

`native.py` declara únicamente la API C pública. Cada intento conserva su propio
descriptor de solo lectura y callback posicional; completa lecturas parciales y
rechaza EOF/error. En Windows usa seek/readinto bajo un lock sobre ese descriptor
dedicado, sin compartir su posición con otro documento o con la copia. No precarga
el PDF en un ByteArray. Callback, descriptor, handles y píxeles permanecen vivos
hasta finalizar sus usos. Todo acceso al motor ocurre en un único hilo con
EPDF_InitThread/EPDF_ShutdownThread; se cierra capa antes de liberar base.

Ambos modos cargan una base independiente con EPDF_LoadBaseDocument, abren una
capa vacía con EPDFLayer_OpenLayer, comienzan transacción, insertan el mismo
bitmap BGRA con alfa recta, generan contenido y confirman la transacción.

- FULL: EPDF_SaveAsCopyEx con FPDF_NO_INCREMENTAL=2 produce un PDF completo.
- Selectivo: EPDFLayer_SaveDelta produce **solo la cola PDF**, con offsets
  absolutos calculados desde la longitud de la base. Se copia el original por
  bloques de 1 MiB a un archivo nuevo y después se escriben **todos** los bytes
  de ese callback. No se recorta ningún prefijo de callback, no se usa un artefacto
  de capa y no hay fallback FULL. El resultado final es original + cola.

La selectividad procede de CPDF_Creator::PrepareIncrementalObjects en esta
variante: compara valores efectivos con los gemelos originales/cargados y
selecciona cambios alcanzables. No se obtiene cambiando únicamente un flag del
PDFium 8111 de Android. Los headers y fuentes consultados pertenecen a esta
misma revisión, sin modificar ni recompilar el motor.

Cada invocación crea un directorio distinto en `_local/results/<etiqueta>-<UTC>-<id>`:
PDFs FULL/selectivos nuevos, renders y recortes PNG, informes estructurales JSON
y `summary.json`. Los fallos conservan evidencia y detienen las repeticiones.
`_local/` y extensiones PDF/PNG/binarios están excluidos de Git. Nunca se
sobrescriben originales ni directorios de resultados anteriores.

`measurements.json` conserva solamente las mediciones y metadatos técnicos de la
ejecución realizada, sin rutas de entrada ni contenido del PDF. Los directorios
de evidencia local están enumerados allí. Requisitos, resultado y comprobaciones
pendientes se registran en BACKLOG, `EMBEDPDF-DESKTOP-001`.

## Medición y comprobaciones

Reloj perf_counter_ns, nanosegundos acumulados y conversión final a ms. Apertura,
bitmap, capa/transacción (apertura/inicio y luego commit), inserción/contenido,
copia original, save nativo, flush/close y cierre de recursos se miden por separado.
`writeStageMs` contiene copia + save + flush/close y preparación de salida; no se
suma de nuevo a esas fases. `saveMs` incluye callbacks/escrituras que ocurren
dentro del motor, pero no el vaciado final. `totalToPdfMs` llega hasta cerrar el
PDF completo, incluyendo la copia; `totalMs` añade liberación de handles. No se
ejecuta fsync/FlushFileBuffers: no son tiempos de persistencia física garantizada.

La primera pareja se verifica antes de permitir tres repeticiones por modo.
Las medianas usan solo esas tres repeticiones y se calculan por columna, por lo
que sumarlas no reconstruye necesariamente una ejecución. Se alterna el orden
FULL/selectivo. No se purgan las cachés del sistema operativo y no se compara
directamente Windows con los segundos medidos en el teléfono.

Fuera del tiempo de exportación se reabren los resultados mediante el cargador
PDF ordinario, sin capa/base, y se renderiza la página firmada. Se comprueban
número de páginas, cambio visible en la zona de firma, acuerdo con una composición
esperada del PNG y preservación fuera de ella, además de igualdad de renders
FULL/selectivo. Se permite diferencia de filtrado en bordes frente a Pillow;
no se afirma igualdad perfecta con otro motor de renderizado. Los hashes antes
y después comprueban que PDF original y PNG no cambiaron.

El analizador externo existente comprueba prefijo exacto, /Prev, xref y objetos
con sus generaciones y tamaños/hashes codificados, sin texto ni imágenes en el
informe. En un FULL sin /Prev, `redefinedObjects` significa números originales
serializados en la reescritura, no una revisión incremental. No todos los bytes
recomprimidos coincidirán por hash. En el selectivo real también se comprueba
que sus nuevos streams pequeños son imagen RGB/máscara de la firma y contenido,
sin streams originales redefinidos.

Límites: solo se renderiza la página firmada, no todas las páginas ni con un
segundo motor independiente; no se prueban errores/cancelación, entradas cifradas
o certificadas, archivos mayores de 4 GiB ni firmas personales. El FPDF_FILEACCESS
de Windows usa unsigned long de 32 bits. La publicación consultada no incluye
Android: portar este resultado requiere primero un runtime Android compatible,
headers/ABI y una adaptación JNI con ciclo base/capa/transacción y confinamiento
de hilo. No se implementa en esta tarea.
