# Firma PDF — Arquitectura v0.1

Estado al 22/10/2026: BUILD-001 resuelto, Room v1 conservado, selección SAF comprobada y vista previa PDFium implementada y probada en Huawei Android 10. El flujo de firma sigue pendiente. Las secciones de flujo describen el diseño objetivo; la sección 10 distingue el código actual. Este documento es la referencia para decidir cambios de arquitectura; se actualiza cuando el código real difiera.

## 1. Objetivo y límites

Abrir un PDF, dibujar o reutilizar una firma manuscrita, colocarla en una o varias páginas, guardar el resultado y compartirlo. El trabajo con el PDF funciona sin conexión. La versión básica ofrece firmas y exportaciones ilimitadas, una firma reutilizable guardada, sin anuncios, suscripciones ni pagos en el MVP. Una posible compra única futura añadiría comodidades sin bloquear la salida básica. No habrá cuentas, OCR, Office, compresión, nube propia ni firma criptográfica.

## 2. Base técnica ya elegida

- `namespace` y `applicationId`: `com.zonadev.firmarpdf`; `minSdk 28`, `compileSdk 36`, `targetSdk 36`.
- Kotlin `2.2.0`, AGP `8.9.2`, Java/JVM `11`, Compose BOM `2024.09.00`. Kotlin se cambia por petición explícita para resolver BUILD-001; las demás versiones se conservan.
- PDFium `io.legere:pdfiumandroid:1.0.35` para visualizar; PDFBox Android `2.0.27.0` para producir la firma gráfica. Inicializar `PDFBoxResourceLoader` con el contexto de aplicación antes de usar PDFBox.
- Base incorporada: Lifecycle Compose `2.9.4`, Navigation Compose `2.8.0`, Room `2.8.2` + KSP `2.2.0-2.0.2` con KSP2, Firebase BoM `34.4.0`, Google Services `4.4.4` y plugin Crashlytics `3.0.6`. PDFium y PDFBox se centralizan en el catálogo sin cambiar sus versiones. DataStore queda pendiente del consentimiento persistente; no se añade una dependencia sin uso. Evitar Paging, Hilt, Koin y más módulos hasta que un problema concreto los justifique.
- Confirmar compatibilidad de todo el paquete nativo con páginas de 16 KB en el APK/AAB y con un emulador de 16 KB. No equivale a mirar únicamente la dependencia de PDFium.

## 3. Responsabilidades y dependencias

```text
ui/home, ui/signing, ui/signature, ui/components
           ↓
HomeViewModel / PdfSigningViewModel
           ↓
OpenPdf / SaveSignedPdf (coordinación)
           ↓
PdfSourceAccess | PdfRenderer | PdfSignatureExporter | SignedPdfStore
           ↓
ContentResolver/SAF | PDFium | PDFBox | Room/archivos privados
```

En `:app`, ordenar paquetes por función, con implementaciones de Android en `data`/`platform` y modelos de operación en `model`. `MainActivity` recibe intents, entrega una solicitud de apertura y hospeda la navegación; no debe gestionar geometría, PDFBox, Room ni decidir dónde se guarda. Los composables dibujan el estado y envían acciones, sin operaciones de archivo. Los ViewModels exponen `StateFlow<UiState>`, llaman al coordinador y se apoyan en un `AppContainer` pequeño para crear dependencias una sola vez. Evitar repositorios nuevos construidos dentro de cada ViewModel. Mantener PDFium y PDFBox detrás de interfaces estrechas, centradas en abrir/renderizar y exportar respectivamente.

La operación `SaveSignedPdf` es una pieza de coordinación justificada: resuelve el destino, ejecuta una sola exportación a la vez, verifica el resultado, publica el archivo y registra el URI final en Room. No hace falta crear un caso de uso para cada clic.

## 4. Modelo de datos y estado

- `PdfOpenRequest`: URI de origen, acción (`PICK`, `VIEW`, `SEND`), permisos recibidos y nombre mostrado. No depender de rutas absolutas ni del nombre del proveedor para decidir permisos.
- `PdfSource`: URI legible, acceso temporal/persistente y capacidades comprobadas. Una selección SAF puede venir de almacenamiento local o nube; un URI externo puede caducar. Conservar permiso persistente solo cuando el sistema lo otorgue.
- `SignaturePlacement`: firma/bitmap de referencia, índice de página y geometría en coordenadas PDF o una transformación reversible bien documentada. Nunca guardar como origen de verdad la posición en píxeles de pantalla: zoom, desplazamiento, rotación, MediaBox/CropBox y páginas de tamaños distintos cambian la vista.
- `PdfSigningUiState` (futuro): documento, página/zoom, firma cargada, colocaciones, progreso, error y resultado. Una pulsación doble en Guardar no inicia dos exportaciones. No cargar bytes PDF en argumentos de navegación.
- `SavedSignatureStore`: un PNG en almacenamiento privado más metadatos mínimos, opción de reemplazar/borrar; Room no es necesario para esta única imagen.
- `SignedPdfEntity` (Room): `id`, `resultUri`, `displayName`, `signedAt`, `resultKind` (`UPDATED_SOURCE` o `NEW_DOCUMENT`) y, si aporta valor, `sourceKind`. Guardar URI como texto y metadatos, nunca PDF/PNG en BLOB. Índice único o política definida para el mismo URI actualizado varias veces; al actualizar un original, cambiar su fecha en vez de crear duplicados sin fin. Exportar el esquema Room al repositorio y versionar migraciones; no usar migración destructiva cuando ya haya usuarios.

Home observa `Flow<List<SignedPdfEntity>>` ordenado por fecha. Si un archivo externo se movió o borró, al abrirlo se marca como no disponible y se permite quitar su fila. Un resultado nuevo en almacenamiento público no depende de que la app siga instalada.

## 5. Entrada y salida de documentos

| Entrada | Acceso inicial | Salida prevista |
| --- | --- | --- |
| `ACTION_OPEN_DOCUMENT` (SAF) | URI seleccionada y permisos otorgados; consultar `FLAG_SUPPORTS_WRITE` y probar capacidades reales | Actualizar original únicamente cuando el procedimiento de reemplazo se haya demostrado seguro para ese proveedor. Si no, producir archivo nuevo. |
| `ACTION_VIEW` o `ACTION_SEND` desde Gmail/WhatsApp/otra app | URI de `data`, `ClipData` o `EXTRA_STREAM` y lectura temporal | Crear PDF firmado nuevo en `Documents/FirmarPdf/`. |
| URI sin escritura o proveedor remoto | Lectura concedida por el proveedor | Crear PDF firmado nuevo; no inferir acceso directo a sus rutas privadas. |

Para adjuntos externos, copiar el origen a caché privada **solo si** el motor requiere acceso aleatorio o el permiso temporal no alcanza para completar la sesión; eliminar ese temporal tras el trabajo. Esta posible preparación no se presume parte del flujo actual de LectorDocumentos. Un PDF de WhatsApp puede abrirse mediante el URI compartido aunque no figure en SAF; la configuración de visibilidad de WhatsApp no es un permiso de Android para leer sus carpetas privadas.

Para una salida nueva, intentar escribir el PDF final en la carpeta **pública** `Documents/FirmarPdf/` con la API de almacenamiento apropiada a la versión de Android y MIME `application/pdf`; reservar nombre sin colisiones. En Android 10+ estudiar la colección de documentos de MediaStore y `RELATIVE_PATH`/`IS_PENDING`, verificando en Android 10 y 15 la ruta visible y que `IS_PENDING` quede en 0. En Android 9 hay que resolver por separado la escritura en la carpeta pública (permiso de escritura de esa versión o selección de destino con SAF); no asumir que la ruta directa funcionará por tener `minSdk 28`. Si PDFBox puede escribir directamente a un destino nuevo e independiente, evitar una copia temporal adicional. En fallo, descartar el destino incompleto y conservar el estado del editor para reintentar.

Actualizar el original es una capacidad **pendiente de demostración**. No usar `saveIncremental`, ni escribir `PDDocument.save()` sobre el origen abierto. Una bandera de escritura o `DocumentFile.canWrite()` no prueba reemplazo atómico ni recuperable. Medir y probar sobre copias de ensayo: fuentes locales/proveedores diferentes, PDFs grandes, poco espacio, cierre de app y fallo durante escritura. La ruta de original se habilita solo si el procedimiento preserva el archivo ante fallos; de lo contrario `SaveSignedPdf` genera un archivo nuevo. El botón de la UI es simplemente «Guardar» y muestra el resultado al terminar, sin diálogo para elegir modo.

Orden de éxito: terminar escritura → cerrar flujos → volver a abrir y comprobar estructura/páginas → publicar resultado → insertar/actualizar fila Room → mostrar éxito. Si el archivo está listo pero Room falla, mostrar el archivo y reintentar registrar la fila; si Room existe y el archivo no, nunca anunciar éxito. La verificación de apertura no sustituye comparar visualmente las páginas en las pruebas.

Compartir usa la URI del resultado con permiso temporal de lectura. Desde Home y desde el resultado se reutiliza el mismo URI final; no exportar por segunda vez al pulsar Compartir.

## 6. Pantallas y navegación

1. **Home**: acción principal «Firmar un PDF», lista de PDF firmados, acceso discreto a la firma guardada y ajustes/privacidad.
2. **Editor**: PDFium, paginación/zoom, firma manuscrita, arrastre entre páginas, redimensionar/eliminar y botón Guardar. Reusar la geometría probada de LectorDocumentos tras revisar sus dependencias y separar el control de anuncios/premium.
3. **Crear firma**: dibujar, deshacer/borrar, usar y opcionalmente guardar/reemplazar la única firma reutilizable.
4. **Resultado**: puede ser panel o pantalla breve con Abrir, Compartir y volver a Home. Confirmar solo después de una salida válida; informar «Original actualizado» o ubicación del archivo nuevo.

Una firma guardada se puede colocar ilimitadamente; no hay límite de tres PDF ni tres colocaciones. El soporte de texto/fecha es una ampliación de la primera versión pública si la beta de firmas muestra que se necesita, no una dependencia del editor básico.

## 7. Privacidad, errores y alcance de PDF

El contenido del PDF y los PNG de firma se procesan localmente. Analytics/Crashlytics son servicios de red separados; habilitar/deshabilitar recopilación según una decisión visible y reflejar el comportamiento en la política de privacidad. Eventos mínimos: apertura lograda/fallida, guardado iniciado/logrado/fallido, tipo de origen, duración y rangos de tamaño. No registrar URI, rutas, nombres de archivo, texto, firma, contraseñas ni excepciones que los contengan. Los fallos de Firebase no bloquean firmas.

Detectar antes de editar PDF no admitidos, incluidos los que ya tengan firmas digitales con certificado; comunicar que ese archivo no se edita en el MVP. Decidir mediante una prueba corta si se admiten PDF con contraseña conocida; no prometerlo por el hecho de que PDFium pueda visualizarlos. «Firma manuscrita» indica marca gráfica, no firma digital certificada.

## 8. Orden de implementación y comprobaciones

1. Incorporar solo navegación y estados Home/editor; dibujar un wireframe de estas pantallas y de Crear firma antes de pedir a la IA una pantalla completa.
2. Extraer un ejemplo mínimo del visor, geometría/arrastre y exportador de LectorDocumentos; retirar importaciones de anuncios, paywall y repositorios de Office. Inicializar PDFBox.
3. Hacer una prueba vertical: SAF → abrir PDFium → colocar una firma → PDFBox crea **archivo nuevo** → volver a abrir con PDFium y un segundo lector. Medir apertura, exportación y memoria con PDF pequeño y grande.
4. Probar intents reales de Gmail y WhatsApp en Huawei Android 10 y Android 15; gestionar permisos temporales y nombre de salida. Incluir Android 9 para la política de carpeta pública.
5. Incorporar Room, lista de Home y reabrir el resultado tras reiniciar la app; manejar archivo desaparecido.
6. Probar por separado el reemplazo seguro del original con PDFBox y SAF. Activarlo solo al superar los escenarios de fallo; el flujo de nueva salida ya funciona independientemente.
7. Añadir Analytics/Crashlytics y controles de recopilación. Comprobar modo avión, exportación múltiple, páginas giradas, primera/última página, pulsación doble, espacio agotado y compatibilidad de 16 KB.

## 9. Reglas para el trabajo con IA y cambios posteriores

- Toda petición nueva o bug va a un único `BACKLOG.md`: ID, descripción, reproducción/resultado esperado, prioridad y estado. No usar notas dispersas.
- Si una decisión afecta URI, permisos, archivo final, Room o geometría, anotar por qué cambia aquí antes de modificar varias capas. Mantener interfaces, datos y flujo de guardado coherentes con el código.
- Trabajar una tarea vertical a la vez; no solicitar «toda la pantalla» sin objetivo, estado y criterio de aceptación. Revisar el resultado en un dispositivo antes de cerrar la tarea.
- No agregar dependencias, módulos, tablas o pantallas por funciones futuras hipotéticas. Separar responsabilidades actuales y ampliar cuando exista el caso real.

## 10. Base implementada y decisiones actuales

- `MainActivity` hospeda Compose; no procesa URI externos todavía. `FirmaPdfApplication` inicializa PDFBox con `applicationContext` y posee un `AppContainer` con una única base Room/repositorio compartido.
- `navigation/FirmaPdfApp` conserva los destinos Inicio, PdfSigningScreen y CreateSignatureScreen. Seleccionar correctamente un PDF ahora abre una sola vez la vista previa; la tarjeta pendiente de Inicio permite reabrirla. PdfSigningScreen solo ofrece lectura, páginas, zoom y Volver. Ofrece un acceso a Crear firma; no hay colocación, exportación, resultado ni ajustes. Se conservan los archivos `ui.theme` generados.
- Home observa `StateFlow` de `HomeViewModel` con recolección vinculada al ciclo de vida; muestra carga, vacío, lista o error recuperable. El repositorio expone el `Flow` de Room, sin datos de muestra. Cada fila contiene solo `id`, `resultUri`, `displayName`, `signedAt` (Unix en milisegundos) y `resultKind`; Room convierte el enum a texto. Índice único por URI y orden `signedAt DESC, id DESC`. La inserción rechaza duplicados sin eliminar filas; la futura actualización de original necesitará una operación explícita que conserve el ID y cambie su fecha, antes de activar esa función.
- Esquema v1 exportado por KSP a `app/schemas/`, destinado al control de versiones. No se usa migración destructiva. La firma reutilizable se guarda en PNG privado mediante SignatureStore, sin tabla Room para la imagen.
- Etapa 2 comprobada: `data/signedpdf` contiene `SignedPdfDatabase`, `SignedPdfRepository`, `SignedPdfEntity` y `SignedPdfDao`; `AppContainer.signedPdfRepository` es la instancia compartida. `ui/signing/PdfSigningScreen` y `ui/signature/CreateSignatureScreen` aclaran las funciones de las pantallas existentes, todavía de preparación. `AppContainer`, `FirmaPdfApp` y `FirmaPdfApplication` conservan sus nombres y responsabilidades. Se mueve el esquema a `app/schemas/com.zonadev.firmarpdf.data.signedpdf.SignedPdfDatabase/1.json`; tablas, columnas, índice, versión 1 y hash `360fc0bb3119eb7704b952858936cada` se conservan, al igual que el archivo privado `signed_pdf_history.db`. Un renombrado de clase no necesita migración SQL y no se borra ninguna base para hacerlo funcionar. assembleDebug y assembleDebugAndroidTest: BUILD SUCCESSFUL (16 s). connectedDebugAndroidTest: 5 pruebas superadas en Huawei YAL-L21 / Android 10 (40 s), incluida la apertura del esquema v1 anterior con una fila existente.
- BUILD-001 resuelto: la preparación inicial con Kotlin 2.0.21 fallaba por los metadatos 2.2.0 de PDFium y usaba temporalmente `runtimeOnly`. La etapa 1 autorizada cambia a Kotlin 2.2.0 + KSP 2.2.0-2.0.2, manteniendo KSP2 y PDFium 1.0.35 como `implementation`. `AppContainer.pdfiumCore` referencia directamente el tipo y constructor de PDFium desde Kotlin, con inicialización diferida; no abre ni renderiza documentos. `:app:assembleDebug` terminó con BUILD SUCCESSFUL (1 min 58 s), incluidas KSP y compileDebugKotlin. No se usan reflexión ni banderas para omitir comprobaciones de metadatos. AGP, PDFBox, Compose, Firebase y demás versiones se conservaron. La disponibilidad al compilar no acredita renderizado ni compatibilidad nativa de 16 KB.
- Se conserva el `app/google-services.json` existente, cuyo cliente coincide con `com.zonadev.firmarpdf`. Se integran Analytics/Crashlytics mediante BoM, módulos principales (sin KTX) y plugins oficiales. La recopilación global y de ambos productos está desactivada por metadatos desde el inicio. No hay eventos propios ni llamadas que habiliten envío. Crashlytics puede conservar informes locales aunque el envío esté desactivado; no prometer que Firebase nunca inicializa ni que no existe ninguna comunicación de red. El procesamiento PDF futuro no dependerá de estos SDK.
- La pantalla de consentimiento, persistencia, revocación y comprobación de un informe voluntario en consola siguen en `MET-001`. No se incorpora DataStore todavía. No registrar nombres, URI ni contenido del PDF/firma en telemetría. Se desactiva la recopilación del identificador publicitario y se eliminan del Manifest combinado AD_ID y permisos de AdServices.
- Guardado previsto: archivo nuevo público en `Documents/FirmarPdf/` para adjuntos externos. Para Android 9 se elige la futura escritura pública con `WRITE_EXTERNAL_STORAGE` limitado a `maxSdkVersion=28`; se declara ahora y solo se solicitará al implementar guardado. Android 10+ usará APIs de almacenamiento apropiadas y deberá verificar publicación y ruta. No hay permiso general de lectura, gestión de almacenamiento, medios ni notificaciones; tampoco VIEW/SEND ni FileProvider.
- `allowBackup=false`: no se restauran datos privados de la app. `backup_rules.xml` excluye todos los dominios para Android 9–11; `data_extraction_rules.xml` los excluye tanto de nube como de transferencia entre dispositivos en Android 12+, incluido almacenamiento protegido del dispositivo. Esto cubre firmas PNG futuras, bases Room, preferencias y archivos privados; caché y `noBackupFilesDir` ya quedan fuera por el sistema. Una reinstalación pierde firma, historial y preferencias. Los PDF públicos futuros permanecerán en Documents y estas reglas no gestionan las copias personales que el usuario haga fuera de la app.
- Manifest combinado: además de INTERNET y escritura solo hasta API 28, las dependencias aportan ACCESS_NETWORK_STATE, WAKE_LOCK y BIND_GET_INSTALL_REFERRER_SERVICE. AndroidX aporta un permiso propio de protección `signature` para receptores internos. No requieren solicitudes de permisos peligrosos en esta tarea.
- La preparación inicial se recibió sin `.git`. Al empezar estas etapas el usuario ya había creado el repositorio (commit inicial `509a87f`); se conserva su cambio previo en `.idea/vcs.xml`. La comprobación de Git usa `safe.directory` solo para el comando, sin modificar configuración global.

### Etapa 3 histórica: Inicio y selección pendiente SAF, antes de la vista previa

- `FirmaPdfApp` hospeda el único Scaffold, el FAB extendido «Firmar PDF» y el contrato `ActivityResultContracts.OpenDocument` con MIME `application/pdf`. La pulsación abre inmediatamente el selector; la cancelación (`null`) no cambia selección ni historial. No se añaden permisos, filtros de intents, proveedores ni dependencias.
- `HomeScreen` dibuja Inicio, la sección «PDF seleccionado» solo si existe una selección legible y la sección «Documentos firmados» observada desde Room. Son estados independientes: carga/error del historial no eliminan la selección. El LazyColumn reserva 112 dp al final para que el FAB no tape la última fila.
- `SelectedPdfReader`, compartido por AppContainer, usa ContentResolver en Dispatchers.IO: abre el flujo en lectura, comprueba una lectura mínima y lo cierra; consulta OpenableColumns.DISPLAY_NAME. Si falta el nombre se muestra «PDF sin nombre», sin revelar URI ni rutas. Esto comprueba acceso, no la validez estructural del PDF. No copia bytes a archivos ni llama a PDFium/PDFBox.
- HomeViewModel conserva solo un `SelectedPdf` (Uri y nombre opcional) en estado de pantalla; SavedStateHandle guarda únicamente el URI como texto para restaurar la tarea. El ViewModel conserva el estado al rotar. Si hay recreación de proceso, reconsulta nombre y acceso antes de mostrar la selección. No hay inserciones en Room, preferencias ni historial de selecciones; tampoco se acumulan permisos persistentes SAF. Una tarea cerrada no constituye un historial recuperable.
- Elegir otro PDF cancela la comprobación anterior y reemplaza la única selección al verificar lectura. Si falla, se comunica el error sin exponer la excepción y se conserva la selección válida anterior; el usuario puede elegir otro desde el FAB. Un selector del sistema no disponible tiene un mensaje propio. Nunca se solicita permiso general de lectura/escritura para SAF.
- Etapa 3: `.\gradlew.bat :app:assembleDebug :app:connectedDebugAndroidTest --console=plain` terminó con BUILD SUCCESSFUL (36 s), con 7 pruebas superadas en Huawei YAL-L21 / Android 10. Las dos pruebas nuevas ejercitan el estado de selección con flujos de prueba legibles e ilegibles, cancelación/reemplazo y Room sin nuevas filas. No sustituyen la interacción real con OpenDocument ni prueban rotación.
- Verificación manual detenida por instrucción del usuario: tras crear dos PDF de prueba e intentar iniciar MainActivity, `adb shell am start -n com.zonadev.firmarpdf/.MainActivity` devolvió el error exacto siguiente. `adb shell pm path com.zonadev.firmarpdf` confirmó que no había paquete instalado tras las pruebas instrumentadas. Esto impidió iniciar el recorrido manual; no demuestra un fallo del código de SAF. No se reinstaló ni se continuó para respetar la regla de detenerse ante un fallo. El APK compilado está disponible en `app/build/outputs/apk/debug/app-debug.apk`. Pendiente instalarlo y comprobar abrir SAF, cancelar, seleccionar un PDF, reemplazarlo y girar la pantalla. Las funcionalidades completas de firma e historial siguen pendientes en BACKLOG.md.

```text
Error type 3
Error: Activity class {com.zonadev.firmarpdf/com.zonadev.firmarpdf.MainActivity} does not exist.
```

Los archivos de prueba son `Seleccion-A.pdf` y `Seleccion-B.pdf` (585 bytes cada uno), creados para esta comprobación en `/sdcard/Download/FirmarPDF-Prueba-09fa683f/`; no provienen de documentos del usuario. El selector aún no se abrió en esta comprobación.

### Verificación histórica de la preparación inicial (antes de estas etapas)

Ejecutado en PowerShell con el wrapper existente:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Resultado: `BUILD SUCCESSFUL`. APK en `app/build/outputs/apk/debug/app-debug.apk`. Se ejecutó la prueba unitaria existente de la plantilla (1, sin fallos); se compilaron las pruebas instrumentadas de Room sobre base aislada en memoria (vacío, orden/lectura del enum y rechazo de URI repetido). Las pruebas instrumentadas no se ejecutaron ni se instaló esta compilación en el Huawei. Navegación, reinicio y comportamiento de Firebase en dispositivo siguen necesitando comprobación manual.

Lint: 0 errores y 53 avisos, incluidos avisos repetidos de versiones más nuevas, recursos originales sin uso/etiqueta redundante y `TrustAllX509TrustManager` de Bouncy Castle transitivo de PDFBox (`SEC-001`). No se cambian versiones por esos avisos ni se ocultan verificaciones. Se revisó el Manifest combinado, el esquema generado y el diff contra la copia temporal de los archivos iniciales; no quedan Greeting, Hello Android, textos nuevos de interfaz en Kotlin, recursos de texto nuevos sin uso ni declaraciones duplicadas de PDFium/PDFBox.

## 11. Vista previa — decisión y comprobación del 22/10/2026

Se instaló con `:app:installDebug` (BUILD SUCCESSFUL, instalado en 1 dispositivo) y se confirmó el paquete con `pm path`. En Huawei YAL-L21 / Android 10 se abrió realmente DocumentsUI desde el FAB, se canceló, se eligió Seleccion-A.pdf, se eligió B y se giró a horizontal: se mantuvo una única tarjeta B y el historial vacío. La orientación del sistema se restauró al terminar. Esto sustituye la verificación pendiente de SAF-001; el error histórico de Activity inexistente correspondía a la ausencia de instalación.

Se leyeron los dos archivos de referencia de LectorDocumentos indicados por el usuario. Se aprovecha el patrón de descriptor en lectura/PDFium, mutex, cierre de páginas con use, generación de apertura, LruCache y LazyColumn con gestos de zoom/pan. No se copian la pantalla completa ni búsqueda, selección de texto, firmas, anuncios, Premium, operaciones de archivo, logs o telemetría de esa app.

La vista previa es una sola función nueva. `PdfPreviewSession` posee el documento y su descriptor; abre sin copia previa, serializa renderizado/cierre, usa páginas con use y limita la caché a min(24 MiB, heap/8), más las referencias visibles de LazyColumn. Cada bitmap RGB_565 tiene como máximo aproximadamente 4 MiB / 2 Mpx, ancho 1440 y alto 4096, conservando proporción. La resolución no aumenta con el zoom: se acepta suavidad/pixelado al ampliar como límite de esta primera vista previa. Los bitmaps publicados no se reciclan manualmente porque Compose puede seguir usándolos; al expulsar/salir se liberan referencias y Android recupera la memoria. Apertura/renderizado/cierre se ejecutan en IO, con invalidación antes de cancelar y cierre serializado incluso si termina tarde un trabajo nativo.

`PdfSigningViewModel` pertenece al destino de navegación y sobrevive a rotación; al retirarlo cierra la sesión con un trabajo de limpieza que puede completar tras cancelar viewModelScope. Cada apertura y reintento tiene su sesión/generación; las imágenes se producen con claves de sesión/página/ancho y nunca se comparten entre PDF. La selección emite un evento de navegación consumible una sola vez; restaurar la selección guardada no emite ese evento. La tarjeta de Inicio reabre explícitamente. Room y la política de guardado no cambian.

El zoom adapta únicamente los gestos desacoplables de la referencia: doble toque 1×/2×/3×/1×, pinza hasta 5×, pan horizontal acotado y desplazamiento vertical delegado a la lista con conversión de escala. A 1× el desplazamiento y fling pertenecen a LazyColumn. Se mantienen índice de lista y zoom al rotar, sin fijar orientación. La futura pantalla para dibujar firma tendrá diseño vertical (UX-002); no se implementa aquí.

Los errores de acceso, formato, contraseña, memoria y renderizado se representan por categorías y recursos en español. No se registran nombres, URI, contenido ni excepciones originales, y el logger configurable de PDFium se silencia. No hay acciones de firma, acceso a Crear firma, modificación del original, exportación ni registro en Room al visualizar. El cierre del descriptor tiene un finally adicional por si falla el cierre nativo. El indicador de página usa derivedStateOf para no recomponer por cada píxel de desplazamiento; sus coordenadas de pan se limitan de nuevo al cambiar el tamaño de la ventana.

### Resultados de compilación y pruebas de vista previa

Comando final ejecutado en PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:installDebug --console=plain
```

BUILD SUCCESSFUL, instalado en un Huawei YAL-L21 / Android 10. Lint: 0 errores, 56 avisos (versiones más nuevas, recursos/label de la plantilla, recomendaciones UseKtx y el aviso Bouncy Castle ya registrado como SEC-001). No se cambian versiones ni se silencian comprobaciones. Manifest, dependencias, Room y hash v1 permanecen sin cambios.

Tras instalar el APK de pruebas se ejecutó directamente el runner, conservando la app instalada al terminar:

```powershell
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e previewDeviceFixtures true com.zonadev.firmarpdf.test/androidx.test.runner.AndroidJUnitRunner
```

Resultado final: **OK (12 tests)**, aproximadamente 22 s. Incluye las 7 pruebas previas, 4 de sesión PDFium y 1 de interfaz real. Las de sesión verifican dimensiones distintas/proporción y límite de bitmap, lectura inválida/inaccesible, contraseña, cierre del descriptor por identidad de archivo y rechazo de renderizado tras cerrar. La prueba de interfaz no llama al ViewModel: toca el FAB y DocumentsUI e inyecta eventos táctiles reales de dos dedos, doble toque y pan; gira con zoom activo, recorre páginas, vuelve/reabre y cambia de archivo. Una versión inicial del test de descriptor comparaba rutas equivalentes de Android y falló; se corrigió para comparar dispositivo/inodo y se comprobó apertura y cierre efectivo en cinco ciclos.

| Comprobación real | Resultado ejecutado |
| --- | --- |
| SAF antes del visor | Abrir, cancelar, A, B y rotar; una tarjeta pendiente, historial vacío. |
| PDF de una página | Seleccion-A.pdf: apertura automática, volver, reabrir tarjeta y girar sin navegación duplicada. |
| PDF de seis páginas | Multipagina.pdf (4873 bytes), tamaños verticales/horizontales diferentes: desplazamiento hasta la última, pinza, doble toque, pan, vuelta/reapertura, rotación horizontal/vertical conservando zoom. Se revisó una captura de páginas consecutivas renderizadas. |
| PDF grande disponible | Grande.pdf, sintético, 8 páginas con imágenes RGB, 24 004 789 bytes (~22,9 MiB): apertura, desplazamiento hasta la página 8, regreso a Inicio con tarjeta e historial vacío. |
| Errores | Archivo inválido elegido realmente en SAF muestra error comprensible; protegido e inaccesible se verifican con PDFium/ContentResolver en pruebas instrumentadas, no mediante el selector. |
| Originales | SHA-256 de los tres archivos nuevos de prueba coincide con los locales tras visualizar; no hay modificación de origen. |
| Instalación | pm path confirma que el paquete permanece instalado después de las pruebas. |

Captura de memoria aislada con el PDF grande abierto: Java Heap 18 096 KiB, Native Heap 86 608 KiB y Graphics 52 508 KiB. Son categorías de una instantánea de dumpsys, no un pico medido ni una garantía de memoria total. No se midieron FPS ni tiempos con un benchmark. La caché acota bitmaps propios; el motor nativo y la GPU mantienen asignaciones adicionales.

Límites: los PDF grandes son sintéticos, no documentos complejos reales. La resolución fija puede verse pixelada al ampliar; no hay rerenderizado de alta resolución ni inercia propia al desplazar con zoom. El indicador corresponde a la primera página visible, de modo que puede indicar 7/8 con la última página visible debajo. No se comprobaron proveedores remotos o descriptores no posicionables, memoria agotada, muerte de proceso ni dispositivos de 16 KB; QA-PREVIEW-001 conserva esas verificaciones. No se bloquea la orientación de la vista previa. Firma, colocación y exportación permanecen sin implementar.

Para reproducir las pruebas de interfaz: `python scripts/create_preview_fixtures.py` genera Multipagina.pdf, Grande.pdf e Invalido.pdf en el directorio temporal mostrado, usando solo la biblioteca estándar. Copiarlos con adb a una carpeta de pruebas pública y dejar DocumentsUI situado en ella antes de ejecutar el runner con `previewDeviceFixtures=true`. Sin ese parámetro, la prueba de laboratorio se omite; las otras 11 son independientes del selector. Las capturas y la salida de esta ejecución están en `app/build/reports/preview/` (artefactos generados, no fuente de requisitos).

## 12. Crear firma — etapa de dibujo y guardado privado

Se conserva el trabajo existente, versiones, dependencias, selección SAF, Room y su esquema v1. CreateSignatureScreen sustituye únicamente la pantalla de preparación. Desde la vista previa se añade un acceso Crear firma; no se modifica un menú inferior ni se implementan herramientas sobre el PDF.

- CreateSignatureViewModel mantiene trazos y ajustes por destino. Cada trazo conserva color ARGB, grosor relativo al ancho y puntos normalizados. Cambiar color/grosor afecta solo al trazo siguiente. Los trazos en curso se confirman al levantar el dedo; una cancelación del gesto descarta solo ese trazo. Deshacer elimina el último y Borrar limpia el borrador.
- La paleta tiene 10 colores, negro inicial, selección con borde y semántica accesible de radio. Slider 1,0–10,0 cada 0,5, formato decimal español y descripción accesible. Canvas blanco con borde ocupa el espacio restante; los controles inferiores conservan su lugar. Todos los textos y etiquetas proceden de recursos.
- El borrador sobrevive a recreación de Activity mediante ViewModel; no se promete recuperación tras muerte de proceso. Salir con trazos exige confirmar descarte. Durante guardado se bloquean acciones para impedir escrituras repetidas o descartar el dibujo a mitad de operación. Un error conserva el borrador y permite reintentar; no se registran trazos ni excepciones en telemetría.
- No existía almacenamiento implementado de firmas. SignatureStore guarda una única firma reutilizable en filesDir/signature/signature.png, siguiendo el MVP existente: Guardar reemplaza la anterior con AtomicFile, fuera de Room. El renderizador compartido dibuja tanto en Canvas como en el PNG, con extremos redondos y soporte de puntos. PNG ARGB_8888 transparente, recortado alrededor de los trazos con margen, sin fondo blanco. Se limita la superficie de renderizado a 1600×2400 conservando la proporción del lienzo. Compresión/escritura en IO y liberación del bitmap al terminar.
- La política de copia de seguridad existente ya excluye todos los archivos privados: el PNG no entra en backup ni transferencia. No cambia la política de guardado de PDF ni se solicitan permisos nuevos.
- La navegación controla portrait únicamente mientras el destino es Crear firma; al volver solicita UNSPECIFIED para que el sistema gobierne de nuevo la orientación. No se añade screenOrientation al Manifest. Tras guardar se retira el destino y vuelve al visor sin colocar ninguna firma.

Verificación ejecutada: assembleDebug y assembleDebugAndroidTest, BUILD SUCCESSFUL. Solo se ejecutó CreateSignatureTest (3 pruebas, OK) en Huawei Android 10: dibujo táctil y controles con 400 dp de altura, selección de color/slider, deshacer/borrar, confirmación de salida, guardado/callback, PNG con zonas transparentes y ambos colores, reemplazo del PNG anterior y fallo de escritura conservando el dibujo. Recorrido real adicional por SAF y visor: abrir Crear firma, trazar, intentar salir, seguir dibujando, guardar y volver a Vista previa; se comprobó el PNG privado y se eliminó únicamente esa firma sintética de prueba. Se verificó con dumpsys que al regresar la política era SCREEN_ORIENTATION_UNSPECIFIED. El giro simulado por settings volvió a portrait tras la solicitud vertical; no se declara verificado un giro físico del dispositivo. SIG-002 deja constancia del ajuste y ese límite de la prueba.

Pendiente para próximas etapas: selección de la firma guardada y colocación/redimensionado en PDF. No hay sheet, borde rojo, arrastre, texto, sellos ni exportación. El plan de producto describe el objetivo futuro; esta sección describe el alcance implementado actual.

## Fuentes técnicas

- Android, recomendaciones de arquitectura: https://developer.android.com/topic/architecture/recommendations
- Android, Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Android, almacenamiento de documentos: https://developer.android.com/training/data-storage/use-cases
- Android, Room: https://developer.android.com/jetpack/androidx/releases/room
- PDFBox Android, inicialización: https://github.com/TomRoush/PdfBox-Android
- PDFBox, advertencia de origen y salida: https://pdfbox.apache.org/3.0/migration.html
- PdfiumAndroidKt, issue de 16 KB: https://github.com/johngray1965/PdfiumAndroidKt/issues/30
- Firebase, integración Android y BoM: https://firebase.google.com/docs/android/setup
- Firebase, versiones y compatibilidad del BoM: https://firebase.google.com/support/release-notes/android
- Firebase, Analytics desactivado por defecto: https://firebase.google.com/docs/analytics/android/configure-data-collection
- Firebase, Crashlytics y recopilación opcional: https://firebase.google.com/docs/crashlytics/android/customize-crash-reports
- Android, exclusiones de backup y transferencia: https://developer.android.com/identity/data/autobackup
- KSP 2.2.0-2.0.2 para Kotlin 2.2.0: https://github.com/google/ksp/releases/tag/2.2.0-2.0.2
- Android, contrato OpenDocument: https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument
- Android, estado temporal y SavedStateHandle: https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate
- Android, gestos y consumo de eventos Compose: https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures
- Android, zoom y desplazamiento multitáctil: https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch
