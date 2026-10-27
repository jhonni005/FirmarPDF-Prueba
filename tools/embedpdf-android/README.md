# Motor EmbedPDF Android ARM64 y exportador aislado

Revisión fija: `5f7608ba4a634c94cd718067d6b678a45ba89088`, la del experimento
Windows. No se ejecuta de nuevo aquel benchmark ni se usa su DLL en Android.
Este incremento añade solo arm64-v8a; el visor conserva sus cuatro motores 8111.

## Compilación reproducible

Workflow `.github/workflows/embedpdf-android-arm64.yml`, ejecutable manualmente en
el repositorio autorizado `jhonni005/FirmarPDF-Prueba`. La rama de trabajo es
`codex/embedpdf-arm64-20261026`; no hay merge a main ni publicación de releases.
El disparo push está limitado a los archivos de build en esa rama para registrar
el workflow nuevo sin cambiar la rama principal. Los cambios de app no lo disparan.

El runner es Ubuntu 24.04. Se inspeccionan memoria/disco y se retiran únicamente
SDKs no utilizados del runner efímero, nunca del equipo Windows. Se usa Ninja -j2,
sin LTO y con symbol_level=0; no se cambia el heap ni el NDK anfitrión de Gradle.

`build.sh` descarga solo el fork PDFium y las dependencias de DEPS, con checkout
minimal, Android habilitado, V8/XFA y renderer Skia desactivados. Se descarga
la revisión Skia de DEPS porque el fork necesita redaction_pathops incluso con
pdf_use_skia=false; no se elimina esa dependencia ni se modifica su algoritmo. No descarga Chromium
completo. Fija depot_tools a `a03dfbf7f2d1cefa2795d81d59ca4f16c8616a8f` según
ese DEPS y desactiva su actualización automática. DEPS fija Chromium build a
`ebdaeefa62db7c40f27cacb0eb034db3a5cdb849`, buildtools, Clang y NDK
`2@30.0.16248370`, entre otros. El paquete conserva DEPS y las revisiones efectivas.

Se adapta el destino mediante GN, no ejecutando build-target.sh con un nombre
Android inventado. `pdf_is_complete_lib=true` activa el static_component_type y
complete_static_lib ya existentes en BUILD.gn; se verifica que el archivo no sea
thin. `embedpdf_thread_local_globals=true`, target_os=android, target_cpu=arm64,
default_min_sdk_version=28, use_custom_libcxx=false. El runtime C++ estático del
NDK fijado se entrega con el archivo para el enlace privado; el puente sigue
compilándose con NDK r26d del proyecto. No se parchea el algoritmo de guardado.

Además del runtime C++, el paquete debe incluir
`libclang_rt.builtins-aarch64-android.a` del Clang Android fijado en DEPS. libyuv
SME referencia __arm_get_current_vg y __arm_tpidr2_save, ausentes en el runtime
de r26d. Se enlaza ese archivo de soporte de forma privada sin migrar el NDK
anfitrión. SHA-256 del builtins:
`32faf9cae893d732d45799f18040b90c69f847bc408d8207c6f65dd2c2f940e4`.
El manifiesto documenta el tar.xz de origen, generación y SHA-256; la licencia
compiler-rt se conserva desde la revisión de su etiqueta LLVM
`62397f8b3c3986f54187ce08f00b3448ea1f8880`. La cadena --version del compilador
también se registra literalmente y puede identificar otro commit del paquete.

Único parche de metadatos GN: `android-skia-gn.patch` elimina del invocador
skia_opts una modificación duplicada de configs, indefinida en ese ámbito en
Android release. La plantilla skia_source_set ya aplica optimize_max dentro del
source_set y conserva sus opciones por defecto. También condiciona el objetivo
del renderer completo a pdf_use_skia, evitando su dependencia Expat ausente del
DEPS del fork cuando ese backend está desactivado; redaction_pathops se conserva.
El artefacto incluye el diff
efectivo en build-patches.log; no hay cambios C/C++ del motor. depot_tools se
inicializa explícitamente con ensure_bootstrap sin actualizar su revisión.

Para un host Linux ya preparado, con al menos 20 GB libres antes del checkout:

```bash
export EMBEDPDF_BUILD_ROOT="$HOME/build/embedpdf-arm64"
export EMBEDPDF_PACKAGE="$PWD/engine-artifact"
export EMBEDPDF_BUILD_JOBS=2
bash tools/embedpdf-android/build.sh
```

El checkout grande está fuera de Git y en una ruta sin espacios. El script puede
instalar los paquetes básicos del host Linux; no debe
ejecutarse en Windows ni instala WSL/Docker. Requiere sudo en ese host.

## Control y descarga desde Windows

Los comandos usan credenciales existentes de Git Credential Manager o GH_TOKEN,
sin imprimir/persistir tokens ni abrir login interactivo. Las redirecciones de
descargas no propagan Authorization a otro host.

```powershell
python tools/embedpdf-android/github_actions.py status
python tools/embedpdf-android/github_actions.py dispatch
python tools/embedpdf-android/github_actions.py logs --run ID
python tools/embedpdf-android/github_actions.py download --run ID
python tools/embedpdf-android/import_engine.py .gradle/embedpdf-android-artifacts/ID/embedpdf-android-arm64-ID
```

El artefacto entrega archive completo, runtime C++, headers públicos, licencias,
args.gn, configuración GN efectiva, dependencias, compilador, run URL y hashes.
La importación verifica revisión, ABI, API 28 y cada hash antes de copiarlo a
`third_party/embedpdf/prebuilt`, ignorado por Git. Headers/licencias/procedencia
se conservan además junto al puente. No toca third_party/pdfiumandroid/prebuilt.
Las rutas largas del artefacto se gestionan con el prefijo Windows extendido,
sin cambiar políticas globales. La copia legal versionada se aplana y
licenses/INDEX.json conserva la relación con las rutas originales; el árbol
original íntegro sigue en el artefacto y en prebuilt, con hashes verificables.

Cuando exista el artefacto importado:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
python scripts/verify_pdfium_apk.py --require-embedpdf
```

Estado y resultados reales, incluidos fallos/reintentos: BACKLOG,
`EMBEDPDF-ANDROID-001`. Las instrucciones no equivalen a una compilación ejecutada.
`third_party/embedpdf/ENGINE.json` identifica el artefacto descargado y
`third_party/embedpdf/APK-VERIFICATION.json` conserva hashes del APK/puente,
entradas estáticas usadas, exportaciones, dependencias e inspección API 28.
Los binarios y checkouts grandes permanecen fuera de Git. Para reconstruir
desde otro checkout hay que descargar/importar el artefacto antes de Gradle.

## Aislamiento y recorrido

El CMake del módulo existente añade libembedpdf_signature.so solo para ARM64.
Enlaza el motor estático PIC y runtime C++ privado, --exclude-libs=ALL, -Bsymbolic
y un version script local:* que exporta únicamente la entrada JNI del exportador.
El verificador exige esa única exportación, ningún FPDF/EPDF importado o exportado,
ausencia de TEXTREL, dependencias de plataforma disponibles en API 28, ELF/ZIP
16 KiB y hashes intactos de los cuatro motores anteriores. No basta renombrar
una biblioteca ni depender de RTLD_LOCAL.

Excepción de importación débil revisada: compiler-rt aarch64.c.o referencia
memfd_create solo como marcador de presencia de API 30. Su resolver FMV comprueba
la dirección y retorna si falta; no llama a esa función. Se contrastó el código
`compiler-rt/lib/builtins/cpu_model/aarch64/fmv/android.inc` de
62397f8b3c3986f54187ce08f00b3448ea1f8880 y el resolver/relocación GOT del binario
enlazado. El verificador admite solo ese marcador débil fuera de API 28;
cualquier otro import débil no disponible exige una nueva revisión explícita.

No se transfieren handles nativos entre Kotlin ni entre motores. Una llamada JNI
síncrona ejecutada en Executors.newSingleThreadExecutor contiene InitThread, base,
capa vacía, transacción, edición por página, generación, commit, copia y SaveDelta,
cierre de recursos y ShutdownThread. La carga suspendida de PNG ocurre antes de
crear handles; se usa SavedSignatureStore sin miniaturas. Su conversión RGBA a
BGRA con alfa recta respeta stride y premultiplicación como el puente anterior.
La matriz se obtiene de PdfiumExportGeometry para cada colocación y bbox/rotación
de la nueva página, sin exponer punteros del motor al llamador.

El descriptor original permanece abierto hasta terminar el JNI; pread64 completa
peticiones, reintenta EINTR y rechaza EOF/rangos inválidos sin alterar posición.
Solo tras confirmar cambios se crea PublicPdfDestination. Copia y delta pasan
por el mismo PdfSaveOutputStream de 64 KiB. No se descarta ningún byte del callback,
no se usa SaveLayerArtifact, fallback ni reapertura del resultado al guardar.
Se publican/registra historial solo después de flush/cierre y validar contadores.

Las excepciones JNI se conservan, se limpian temporalmente para liberar recursos
y escribir métricas, y se relanzan después de ShutdownThread. La transacción
incompleta se aborta; se cierra página, capa y base en el hilo propietario. Cancelar
se comprueba entre lecturas, bloques y filas de bitmap, sin cerrar descriptores ni
handles desde otro hilo. Una llamada/proveedor bloqueado puede tardar en responder.
El destino no publicado se elimina y el borrador/original se conservan. Una salida
ya publicada nunca se borra desde la limpieza; publicación e historial tienen
los puntos de cancelación del flujo suspendido, sin anunciar éxito al cancelarse.

El mutex se comparte con PDFBox y los dos recorridos PDFium. El botón debug
"Guardar selectivo (EmbedPDF)" usa el mismo permiso Android 9, progreso y resultado.
Fechas/dibujos se rechazan antes de crear destino. La opción se oculta fuera de
un proceso ARM64 o sin artefacto; no se carga el motor al iniciar la aplicación.

## Métricas

Una línea debug `EmbedPdfExportTiming` por intento que alcanza el exportador.
Nanosegundos monotónicos acumulados y conversión final a ms, sin datos privados.

| Campo | Intervalo |
| --- | --- |
| openMs | Metadata/descriptor Kotlin más InitThread y apertura de base nativa; no implica leer todo el PDF. |
| imagesMs | PNG completos/Bitmap Kotlin antes del JNI más conversión BGRA y preparación SetBitmap por colocación. |
| layerTransactionMs | Apertura de capa, controles de páginas/firmas/permisos, begin y posterior commit. |
| insertContentMs | Páginas/bbox, geometría, matrices/inserción y generación/cierre de cada página, excluyendo preparación del bitmap. |
| copyOriginalMs | Lectura posicional y callbacks de escritura de todos los bytes originales. |
| deltaSaveMs | EPDFLayer_SaveDelta, incluidos sus callbacks y escrituras durante la llamada. |
| flushCloseMs | Vaciado del búfer y cierre final del proveedor; no fsync. |
| nativeCloseMs | Liberación de recursos pendientes, abort si procede, capa, base y ShutdownThread. |
| publishMs/historyMs | Publicación y registro del resultado. |
| totalMs | Operación completa hasta finalizar limpieza, publicación e historial; incluye espera del worker, carga inicial de la .so y preparación de destino, no asignadas a otra fase. |

Las escrituras de copia/delta incluyen las descargas parciales del búfer que
ocurran dentro de sus intervalos. flushClose mide el vaciado restante: no se
presenta el tiempo de delta como tiempo de producir el PDF completo. open/images/
transaction/insert son acumulaciones de segmentos no contiguos. Los contadores
inputBytes/copiedOriginalBytes/deltaBytes/outputBytes se contrastan antes de
publicar; en errores pueden diferir por bytes aún pendientes en el búfer.

Contrato de LongArray JNI: 0=open ns, 1=transaction ns, 2=insert ns, 3=copy ns,
4=save ns, 5=close ns, 6=input bytes, 7=copied bytes, 8=delta bytes, 9=error,
10=images ns, 11=page count. No comparte estructuras ctypes Windows.

Filtro Logcat: `tag:EmbedPdfExportTiming` con nivel Debug. Las comprobaciones en
teléfono se realizan después por el usuario: firma visible al reabrir externamente,
geometría/alfa/varias páginas, original intacto, estructura selectiva, cancelación,
proveedores y Android 9. Compilar y verificar el APK no prueba esos comportamientos.
