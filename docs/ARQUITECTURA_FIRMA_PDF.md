# Firma PDF — Arquitectura v0.1

Política vigente: Guardar crea siempre un PDF NUEVO en Documents/FirmarPdf para cualquier origen. Se mantienen Room v2 y sus datos. La finalización actual (§23) conserva cierre/salida no vacía/publicación/registro, sin segunda carga PDFBox ni comparación de páginas del resultado. Las secciones históricas conservan decisiones y pruebas anteriores, sin habilitar modificación del original. Esta copia `FirmarPDF - PdfiumPrueba` usa el identificador y la configuración sin Firebase descritos en §24; las referencias históricas a su integración no se aplican a esta copia. §25 incorpora el binding local y el modo PDFium completo junto a Guardar/PDFBox. Por autorización explícita posterior, §26 añade únicamente a esta copia un modo PDFium incremental experimental, siempre hacia otro PDF. Las prohibiciones anteriores de incremental se mantienen como alcance histórico; la excepción actual no habilita sobrescribir originales ni usar saveIncremental de PDFBox.

La autorización posterior para Android añade el recorrido selectivo independiente de §30: original exacto seguido de la cola de `EPDFLayer_SaveDelta`, siempre en un destino nuevo. No cambia las políticas de PDFBox ni de los modos PDFium anteriores.

## 1. Objetivo y límites

Abrir un PDF, dibujar o reutilizar una firma manuscrita, colocarla en una o varias páginas, guardar el resultado y compartirlo. El trabajo con el PDF funciona sin conexión. La versión básica ofrecerá varias firmas manuscritas guardadas y exportaciones ilimitadas, sin anuncios, suscripciones ni pagos en el MVP. Una posible compra única futura añadiría otras comodidades sin bloquear la firma ni la salida básica. No habrá cuentas, OCR, Office, compresión, nube propia ni firma criptográfica.

## 2. Base técnica ya elegida

- `namespace` y paquetes Kotlin: `com.zonadev.firmarpdf`; `applicationId` de esta copia: `com.zonadev.firmarpdf.pdfiumtest`; `minSdk 28`, `compileSdk 36`, `targetSdk 36`.
- Kotlin `2.2.0`, AGP `8.9.2`, Java/JVM `11`, Compose BOM `2024.09.00`. Kotlin se cambia por petición explícita para resolver BUILD-001; las demás versiones se conservan.
- Binding PDFium `v1.0.35` incorporado localmente en `:pdfium-local` (§25), con motor PDFium `158.0.8111.0` de procedencia fijada (§27) para visualizar y exportar; sustituye al AAR `io.legere:pdfiumandroid:1.0.35` sin migrar el binding. PDFBox Android `2.0.27.0` conserva el botón Guardar y todas sus herramientas. Inicializar `PDFBoxResourceLoader` con el contexto de aplicación antes de usar PDFBox.
- Base incorporada: Lifecycle Compose `2.9.4`, Navigation Compose `2.8.0`, Room `2.8.2` + KSP `2.2.0-2.0.2` con KSP2. Esta copia excluye Firebase, Google Services y Crashlytics (§24). PDFium y PDFBox se centralizan en el catálogo sin cambiar sus versiones. DataStore queda pendiente del consentimiento persistente; no se añade una dependencia sin uso. Evitar Paging, Hilt, Koin y más módulos hasta que un problema concreto los justifique.
- Confirmar compatibilidad de todo el paquete nativo con páginas de 16 KB en el APK/AAB y con un emulador de 16 KB. No equivale a mirar únicamente la dependencia de PDFium.

## 3. Responsabilidades y dependencias

```text
ui/home, ui/pdf, ui/signature
           ↓
HomeViewModel / PdfPreviewViewModel
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
- `SavedSignatureStore`: PNG privados individuales `filesDir/signature/<UUID>.png`; API `save(strokes, aspectRatio): String`, `list(): List<String>`, `load(id): Bitmap?` y `delete(id): Boolean`. El ID es estable y no contiene rutas. El PNG antiguo `filesDir/signature/signature.png` se reconoce como ID `signature`, se lista/carga igual que los nuevos y solo puede borrarse mediante una llamada explícita a `delete("signature")`. No se mueve, sobrescribe ni borra durante la actualización. No se usa Room para PNG ni se altera la base de resultados. La política anterior de reemplazo queda documentada como histórica en §12.
- `SignedPdfEntity` (Room): `id`, `resultUri`, `displayName`, `signedAt`, `resultKind` (`UPDATED_SOURCE` o `NEW_DOCUMENT`) y, si aporta valor, `sourceKind`. Guardar URI como texto y metadatos, nunca PDF/PNG en BLOB. Índice único o política definida para el mismo URI actualizado varias veces; al actualizar un original, cambiar su fecha en vez de crear duplicados sin fin. Exportar el esquema Room al repositorio y versionar migraciones; no usar migración destructiva cuando ya haya usuarios.

Inicio observa recent_pdfs; signed_pdfs se conserva como registro de resultados, sin una segunda sección vacía en Inicio. Si un archivo externo se movió o borró, al abrirlo se marca como no disponible y se permite quitar su fila. Un resultado nuevo en almacenamiento público no depende de que la app siga instalada.

## 5. Entrada y salida de documentos

| Entrada | Acceso inicial | Salida prevista |
| --- | --- | --- |
| `ACTION_OPEN_DOCUMENT` (SAF) | URI seleccionada y permisos otorgados; consultar `FLAG_SUPPORTS_WRITE` y probar capacidades reales | Crear siempre un archivo nuevo en Documents/FirmarPdf; nunca actualizar el original. |
| `ACTION_VIEW` o `ACTION_SEND` desde Gmail/WhatsApp/otra app | URI de `data`, `ClipData` o `EXTRA_STREAM` y lectura temporal | Crear PDF firmado nuevo en `Documents/FirmarPdf/`. |
| URI sin escritura o proveedor remoto | Lectura concedida por el proveedor | Crear PDF firmado nuevo; no inferir acceso directo a sus rutas privadas. |

Para adjuntos externos, copiar el origen a caché privada **solo si** el motor requiere acceso aleatorio o el permiso temporal no alcanza para completar la sesión; eliminar ese temporal tras el trabajo. Esta posible preparación no se presume parte del flujo actual de LectorDocumentos. Un PDF de WhatsApp puede abrirse mediante el URI compartido aunque no figure en SAF; la configuración de visibilidad de WhatsApp no es un permiso de Android para leer sus carpetas privadas.

Para una salida nueva, intentar escribir el PDF final en la carpeta **pública** `Documents/FirmarPdf/` con la API de almacenamiento apropiada a la versión de Android y MIME `application/pdf`; reservar nombre sin colisiones. En Android 10+ estudiar la colección de documentos de MediaStore y `RELATIVE_PATH`/`IS_PENDING`, verificando en Android 10 y 15 la ruta visible y que `IS_PENDING` quede en 0. En Android 9 hay que resolver por separado la escritura en la carpeta pública (permiso de escritura de esa versión o selección de destino con SAF); no asumir que la ruta directa funcionará por tener `minSdk 28`. Si PDFBox puede escribir directamente a un destino nuevo e independiente, evitar una copia temporal adicional. En fallo, descartar el destino incompleto y conservar el estado del editor para reintentar.

Actualizar originales queda fuera de esta política. No usar saveIncremental de PDFBox ni abrir el origen en escritura. Guardar siempre genera una salida nueva y cierra los flujos; un fallo conserva el borrador y nunca borra ni trunca el origen. La finalización vigente (§23) no reabre el resultado ni compara sus páginas. Únicamente la acción debug de §26 solicita FPDF_INCREMENTAL de PDFium hacia un destino nuevo y vacío, sin cambiar la política del proyecto original.

Orden de éxito vigente (§23): terminar escritura → cerrar flujos/documento → comprobar bytes escritos >0 → publicar resultado → registrar URI final en Room → mostrar éxito o resultado con advertencia si falla Room. No se reabre el resultado con PDFBox ni se compara su número de páginas; la integridad estructural/visual no se certifica automáticamente al guardar. Si el registro falla tras publicar, se conserva el archivo y se informa; un fallo anterior limpia solo la salida pendiente. El borrador se conserva ante errores de exportación.

Compartir usa la URI del resultado con permiso temporal de lectura. Desde Home y desde el resultado se reutiliza el mismo URI final; no exportar por segunda vez al pulsar Compartir.

## 6. Pantallas y navegación

1. **Home**: acción principal «Firmar un PDF», lista de PDF firmados, acceso discreto a la firma guardada y ajustes/privacidad.
2. **Editor**: PDFium, paginación/zoom, firma manuscrita, arrastre entre páginas, redimensionar/eliminar y botón Guardar. Reusar la geometría probada de LectorDocumentos tras revisar sus dependencias y separar el control de anuncios/premium.
3. **Crear firma**: dibujar, deshacer/borrar y guardar cada nueva firma reutilizable. No hay opción «Usar solo esta vez».
4. **Resultado**: puede ser panel o pantalla breve con Abrir, Compartir y volver a Home. Confirmar solo después de una salida nueva válida; informar su ubicación y que el original permanece intacto.

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
6. Comprobar que el original permanece intacto en todas las entradas y escenarios de fallo; el reemplazo del original queda fuera de este MVP.
7. Añadir Analytics/Crashlytics y controles de recopilación. Comprobar modo avión, exportación múltiple, páginas giradas, primera/última página, pulsación doble, espacio agotado y compatibilidad de 16 KB.

## 9. Reglas para el trabajo con IA y cambios posteriores

- Toda petición nueva o bug va a un único `BACKLOG.md`: ID, descripción, reproducción/resultado esperado, prioridad y estado. No usar notas dispersas.
- Si una decisión afecta URI, permisos, archivo final, Room o geometría, anotar por qué cambia aquí antes de modificar varias capas. Mantener interfaces, datos y flujo de guardado coherentes con el código.
- Trabajar una tarea vertical a la vez; no solicitar «toda la pantalla» sin objetivo, estado y criterio de aceptación. Revisar el resultado en un dispositivo antes de cerrar la tarea.
- No agregar dependencias, módulos, tablas o pantallas por funciones futuras hipotéticas. Separar responsabilidades actuales y ampliar cuando exista el caso real.

## 10. Base implementada y decisiones actuales

- `MainActivity` hospeda Compose; no procesa URI externos todavía. `FirmaPdfApplication` inicializa PDFBox con `applicationContext` y posee un `AppContainer` con una única base Room/repositorio compartido.
- `navigation/FirmaPdfApp` conserva las rutas `home`, `preview/{documentUri}` y `signature`. Inicio ofrece Recientes y menú lateral; la tarjeta provisional queda sustituida por las filas de Recientes (§15–16). PdfPreviewScreen ofrece páginas, zoom, Volver, biblioteca de firmas, fechas y dibujo visual, con ViewModels por destino (§14, §17–18). No hay escritura del PDF, exportación, resultado ni ajustes de telemetría. Se conservan los archivos `ui.theme` generados.
- Home observa `StateFlow` de `HomeViewModel` con recolección vinculada al ciclo de vida; muestra carga, vacío, lista o error recuperable. El repositorio expone el `Flow` de Room, sin datos de muestra. Cada fila contiene solo `id`, `resultUri`, `displayName`, `signedAt` (Unix en milisegundos) y `resultKind`; Room convierte el enum a texto. Índice único por URI y orden `signedAt DESC, id DESC`. La inserción rechaza duplicados sin eliminar filas; la futura actualización de original necesitará una operación explícita que conserve el ID y cambie su fecha, antes de activar esa función.
- Esquemas exportados por KSP a `app/schemas/`, conservando v1 como histórico y añadiendo v2 con recent_pdfs (§15). MIGRATION_1_2 preserva signed_pdfs; no se usa migración destructiva. Las firmas reutilizables se guardan como PNG privados mediante SavedSignatureStore, sin tabla Room para las imágenes.
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

## 12. Crear firma — etapa histórica de dibujo y guardado privado de una firma

Se conserva el trabajo existente, versiones, dependencias, selección SAF, Room y su esquema v1. CreateSignatureScreen sustituye únicamente la pantalla de preparación. Desde la vista previa se añade un acceso Crear firma; no se modifica un menú inferior ni se implementan herramientas sobre el PDF.

- CreateSignatureViewModel mantiene trazos y ajustes por destino. Cada trazo conserva color ARGB, grosor relativo al ancho y puntos normalizados. Cambiar color/grosor afecta solo al trazo siguiente. Los trazos en curso se confirman al levantar el dedo; una cancelación del gesto descarta solo ese trazo. Deshacer elimina el último y Borrar limpia el borrador.
- La paleta tiene 10 colores, negro inicial, selección con borde y semántica accesible de radio. Slider 1,0–10,0 cada 0,5, formato decimal español y descripción accesible. Canvas blanco con borde ocupa el espacio restante; los controles inferiores conservan su lugar. Todos los textos y etiquetas proceden de recursos.
- El borrador sobrevive a recreación de Activity mediante ViewModel; no se promete recuperación tras muerte de proceso. Salir con trazos exige confirmar descarte. Durante guardado se bloquean acciones para impedir escrituras repetidas o descartar el dibujo a mitad de operación. Un error conserva el borrador y permite reintentar; no se registran trazos ni excepciones en telemetría.
- En esta etapa no existía almacenamiento previo de firmas. SignatureStore guardaba una única firma reutilizable en filesDir/signature/signature.png: Guardar reemplazaba la anterior con AtomicFile, fuera de Room. Esta política histórica se sustituye en §13 para permitir varias firmas gratuitas sin perder el PNG anterior. El renderizador compartido dibuja tanto en Canvas como en el PNG, con extremos redondos y soporte de puntos. PNG ARGB_8888 transparente, recortado alrededor de los trazos con margen, sin fondo blanco. Se limita la superficie de renderizado a 1600×2400 conservando la proporción del lienzo. Compresión/escritura en IO y liberación del bitmap al terminar.
- La política de copia de seguridad existente ya excluye todos los archivos privados: el PNG no entra en backup ni transferencia. No cambia la política de guardado de PDF ni se solicitan permisos nuevos.
- La navegación controla portrait únicamente mientras el destino es Crear firma; al volver solicita UNSPECIFIED para que el sistema gobierne de nuevo la orientación. No se añade screenOrientation al Manifest. Tras guardar se retira el destino y vuelve al visor sin colocar ninguna firma.

Verificación ejecutada: assembleDebug y assembleDebugAndroidTest, BUILD SUCCESSFUL. Solo se ejecutó CreateSignatureTest (3 pruebas, OK) en Huawei Android 10: dibujo táctil y controles con 400 dp de altura, selección de color/slider, deshacer/borrar, confirmación de salida, guardado/callback, PNG con zonas transparentes y ambos colores, reemplazo del PNG anterior y fallo de escritura conservando el dibujo. Recorrido real adicional por SAF y visor: abrir Crear firma, trazar, intentar salir, seguir dibujando, guardar y volver a Vista previa; se comprobó el PNG privado y se eliminó únicamente esa firma sintética de prueba. Se verificó con dumpsys que al regresar la política era SCREEN_ORIENTATION_UNSPECIFIED. El giro simulado por settings volvió a portrait tras la solicitud vertical; no se declara verificado un giro físico del dispositivo. SIG-002 deja constancia del ajuste y ese límite de la prueba.

Pendiente para próximas etapas: selección de la firma guardada y colocación/redimensionado en PDF. No hay sheet, borde rojo, arrastre, texto, sellos ni exportación. El plan de producto describe el objetivo futuro; esta sección describe el alcance implementado actual.

## 13. Incremento del 23/10/2026: nombres de vista previa y almacenamiento de varias firmas

- Mantener `:app`, `AppContainer` como creador manual de dependencias, `data/pdf` para lectura/renderizado, `data/signature` para la firma manuscrita y `data/signedpdf` para el historial Room de PDF terminados. Aunque los dos últimos nombres se parecen, representan datos diferentes. Conservar clase, ruta del esquema v1 y archivo físico `signed_pdf_history.db` al ordenar paquetes; no hay motivo para migrar Room por un nombre.
- La pantalla de lectura pasa de `ui/signing` a `ui/pdf` y de `PdfSigningScreen`/`PdfSigningViewModel` a `PdfPreviewScreen`/`PdfPreviewViewModel`. Se trasladan PdfPreviewGestures y el paquete de PdfPreviewDeviceTest. Solo cambian nombres, imports y referencias: rutas, lógica del visor y gestos se conservan. Las referencias antiguas de §10–11 describen las etapas históricas y sus pruebas. `PdfPreviewSession` conserva su nombre: representa una apertura nativa de solo lectura con descriptor, caché y cierre, no un archivo temporal.
- `SignatureStore.kt` se separa en `SignatureStroke.kt` (puntos y estilos), `SignatureRenderer.kt` (dibujo compartido y bitmap transparente recortado) y `SavedSignatureStore.kt` (guardar/listar/cargar/borrar por ID). El renderizado conserva límites, colores y geometría de la etapa anterior. AppContainer comparte una instancia `savedSignatureStore` y CreateSignatureViewModel utiliza la nueva API. No hay cuota, pago, Room para PNG ni opción «Usar solo esta vez».
- Motivo del cambio de política: cada guardado debe conservar las firmas anteriores. Los nuevos nombres son UUID y nunca el nombre legado `signature.png`. Este último se reconoce con ID `signature` sin renombrarlo ni modificar sus bytes. No hay migración ni limpieza automática de firmas. Listar incluye los PNG con UUID y el legado, ordenados por ID; no incluye temporales. Cargar devuelve null si falta el ID y un error si no se puede decodificar; quien carga libera el bitmap. Borrar actúa solo sobre el ID validado, devuelve false si falta y comunica fallos de borrado.
- Escritura segura: con operaciones serializadas por Mutex en la instancia compartida y trabajo en IO, se renderiza el bitmap, se comprime en un `.tmp` privado del mismo directorio, se sincroniza/cierra el flujo y se publica mediante renombrado a un destino nuevo. Un fallo limpia solo ese temporal; los PNG existentes no se abren en escritura. Una interrupción de proceso puede dejar un temporal que no aparece al listar; no se promete limpieza tras muerte de proceso. El ViewModel conserva los trazos ante error y los limpia únicamente tras guardar correctamente. Tras guardar se consume el mismo evento de regreso al visor, sin colocar la firma.
- Consecuencias: se acumulan PNG hasta que se solicite borrar cada ID; no hay cuota ni reemplazo silencioso. Los archivos siguen siendo privados y quedan excluidos del backup/transferencia por las reglas existentes; desinstalar pierde estas firmas. No cambian el guardado de PDF, permisos, dependencias, versiones, base de historial ni esquema Room v1.
- Los componentes exclusivos de una pantalla se quedarán junto a ella (`ui/home/components`, `ui/pdf/components` o `ui/signature/components`) si se extraen; `ui/components` solo contendrá componentes compartidos reales. No crear directorios vacíos ni un `utils` genérico.
- En una tarea posterior, añadir `analytics/AppAnalytics` y `analytics/AppCrashReporter` con eventos y categorías propias de PDF. Mantener la recopilación desactivada hasta implementar consentimiento separado y revocable (MET-001). No reutilizar eventos Premium/Office del lector ni adjuntar nombres, URI, rutas, contenido, trazos o mensajes de excepciones que puedan contenerlos.
- Las pruebas existentes de CreateSignatureTest ahora utilizan la API por ID y conservan ambos guardados; añaden comprobaciones de bytes del PNG legado, reapertura de la instancia, carga, eliminación independiente y rechazo de un guardado inválido sin pérdida. Se mantiene el caso de error de escritura con borrador disponible. Estos cambios de pruebas no se compilan ni se ejecutan en este incremento. La reapertura de una instancia en la prueba no equivale a reiniciar la app.
- Verificación de este incremento: se ejecutó una sola vez `.\gradlew.bat :app:assembleDebug --console=plain` el 23/10/2026: `BUILD SUCCESSFUL in 37s`, 38 tareas (12 ejecutadas, 26 al día). Incluyó KSP, compilación Kotlin, DEX y generación de `app/build/outputs/apk/debug/app-debug.apk`. Comprueba que el código principal y sus referencias compilan y se empaquetan; no demuestra funcionamiento del nuevo almacén ni de la interfaz. La revisión del diff confirmó que los cuatro archivos trasladados solo cambian nombres/paquete y que Room, esquema v1, PdfPreviewSession, dependencias y Manifest no tienen cambios. No se compilaron ni ejecutaron pruebas instrumentadas, connectedDebugAndroidTest ni pruebas de firmado/dibujo/vista previa; tampoco se instaló el APK ni se hicieron recorridos manuales de SAF, zoom o firma en Huawei. Los resultados anteriores de §10–12 permanecen como historial; no acreditan el nuevo almacén. El detalle de verificaciones de comportamiento pendientes está en SIG-003 de BACKLOG.md.

Al cerrar el incremento de esta sección, sheet y colocación seguían fuera de alcance; se añaden por separado en §14. Texto, dibujo sobre PDF y exportación siguen sin implementar. Analytics/Crashlytics conservan la configuración previa con recopilación desactivada; no se implementan wrappers, eventos ni consentimiento.

## 14. Biblioteca y colocación visual sobre el visor actual

Se implementa un solo recorrido visual: barra inferior Firma → biblioteca (o Crear firma si está vacía) → seleccionar → colocar → mover/redimensionar. La barra permanece después de insertar y permite añadir varias colocaciones. Cada colocación conserva ID propio, ID del PNG, página y rectángulo normalizado respecto de la página visual completa, con origen arriba a la izquierda. El borrador pertenece a un PdfSignatureViewModel del destino de vista previa; su vida abarca la recreación normal de Activity y la visita a Crear firma. No se promete persistencia tras muerte de proceso o salir del documento. Un gesto interrumpido al rotar se cancela; las colocaciones ya confirmadas permanecen en el ViewModel.

La geometría visible se calcula desde los offsets y tamaños reales de LazyColumn, su padding de 12 dp y la transformación centrada de PdfPreviewZoomState. El offset del elemento se lleva al viewport restando viewportStartOffset antes de aplicar escala y traslación. Se conserva el primer aspecto renderizado de cada página durante el destino para no cambiar su tamaño provisional al rotar o volver a cargar. No se reutilizan sin ajuste las coordenadas del lector, que restablecía su zoom durante la firma. La inserción elige la página con más área visible y centra la firma dentro de su parte visible, también con desplazamiento horizontal por zoom.

El detector de gesto vive en el viewport para seguir activo aunque la página de origen salga de composición. Sobre la firma se mueve el borrador o se usa el control de tamaño; fuera de ella se conservan desplazamiento, pinza y doble toque del visor. Una segunda punta sobre la firma cancela su arrastre y permite zoom. Se detiene el fling al empezar; cerca de los bordes se desplaza la lista y, con zoom, se utiliza su pan para alcanzar los extremos. Al soltar, se elige mayor solapamiento y, en huecos o extremos, la página más cercana entre las visibles con aspecto conocido; se limita el resultado a esa página conservando proporciones y reduciendo tamaño únicamente si no cabe. Borde rojo y controles tienen medidas visuales independientes del zoom y acciones accesibles de movimiento/tamaño/eliminación. Solo el gesto en curso usa píxeles como estado transitorio.

SavedSignatureStore.load añade maxDimension opcional: lee dimensiones y utiliza inSampleSize antes de decodificar. La biblioteca perezosa de dos columnas carga miniaturas con dimensión máxima de 256 px; la firma seleccionada se carga a un máximo de 1024 px y se comparte entre sus colocaciones. No se cargan todos los PNG completos para mostrar la biblioteca. Los bitmaps publicados no se reciclan manualmente mientras Compose pueda utilizarlos; retirar la última colocación de una firma libera su referencia en el ViewModel.

El sheet tiene fondo blanco, tirador y scrim sobre el PDF, título/subtítulo, botón +, tarjetas claras con papelera y Nueva firma inferior; su cuadrícula se ajusta al espacio vertical disponible. Todos los textos y descripciones son recursos españoles. Borrar un PNG requiere confirmación y retira todas sus colocaciones de este borrador después de completar el borrado; un fallo conserva el borrador y muestra error. Quitar una colocación conserva el PNG. El listado sigue incluyendo signature.png con ID signature. La selección cierra el sheet y consume sincrónicamente la elección antes de cargar la imagen; se bloquea el viewport durante esa carga y se detiene el desplazamiento para mantener el objetivo visible. Crear otra firma vuelve a la biblioteca refrescada al reanudarse el destino PDF, también al cancelar, sin insertar automáticamente. La política de escritura privada de PNG permanece igual y no se escribe ningún byte al PDF ni se registra un resultado en Room.

Adaptaciones del proyecto local LectorDocumentos: PdfSignatureLibrarySheet (estructura, carga perezosa y confirmación); PdfSignatureModels (solo colocación normalizada, sin resultado exportado); PdfSignatureGeometry (proporciones, límites, mayor solapamiento y borde cercano al soltar); PdfSignaturePlacementOverlay (cuerpo, controles y vista durante arrastre); selección, borrado y actualización de colocación de PdfSignatureViewModel (rehechos para SavedSignatureStore y varias colocaciones). No se copian PdfViewerScreen ni el ViewModel completo, exportador, anuncios, Premium ni telemetría. El lector no se modifica.

Los requisitos y la verificación de este incremento se registran en SIG-004 de BACKLOG.md. Arrastre entre páginas, huecos, zoom, rotación y controles requieren comprobación manual posterior; la compilación por sí sola no los verifica.

Verificación ejecutada para este incremento: `.\gradlew.bat :app:assembleDebug --console=plain`, una sola vez el 23/10/2026, `BUILD SUCCESSFUL in 11s` (38 tareas: 17 ejecutadas, 21 al día). Se compilaron Kotlin/KSP, recursos y DEX. En esta ejecución el APK quedó en `app/build/intermediates/apk/debug/app-debug.apk`, confirmado con redirect.txt, output-metadata.json y presencia de las clases nuevas dentro del APK; no en la ruta outputs/apk/debug de las ejecuciones históricas. Aviso no bloqueante de Kotlin sobre el destino futuro de la anotación StringRes en el parámetro error; no se cambiaron flags ni versiones para ocultarlo. Revisión estática de recursos XML/referencias y diff sin cambios en Room/esquema v1, PdfPreviewSession, Manifest, versiones o dependencias. No se compilaron ni ejecutaron pruebas instrumentadas, connectedDebugAndroidTest ni suites de dibujo/visualización; tampoco se instaló el APK ni se hicieron recorridos en Huawei. La compilación acredita integración del código, no comportamiento táctil, rotación, rendimiento ni un PDF exportado.

### Referencia visual recibida después de la compilación de SIG-004

La captura facilitada se usa únicamente como referencia del sheet. PdfSignatureLibrarySheet ajusta esquinas superiores de 28 dp, tirador oscuro, botón + lila, tarjetas de proporción 1,5 con marco rosa pálido e interior blanco, papeleras rojas y botón Nueva firma rojo con contorno gris. Se mantienen dos columnas, carga muestreada, confirmación de borrado y navegación. El ancho de las tarjetas determina su altura y la cuadrícula conserva su límite vertical para pantallas pequeñas. No se copia la barra del visor ni el contenido del documento de la captura.

Este ajuste de presentación es posterior al BUILD SUCCESSFUL de 11 s registrado arriba. Solo se revisa el diff; no se repite assembleDebug ni se ejecutan pruebas o recorridos en dispositivo. El estado y las comprobaciones pendientes de esta revisión están en UX-003 de BACKLOG.md; la compilación anterior no acredita estos cambios posteriores.

## 15. Recientes y evolución de la base a v2

Por petición explícita se añade recent_pdfs a SignedPdfDatabase v2, conservando clase, archivo signed_pdf_history.db, signed_pdfs y esquema histórico 1.json. MIGRATION_1_2 crea únicamente la tabla nueva; no hay migración destructiva. KSP exporta 2.json. Los recientes son aperturas, no resultados firmados: solo se registran después de abrir el PDF mediante PDFium, nunca desde el selector, una cancelación o un intento inválido. Un upsert por URI actualiza orden y metadatos sin duplicar. Un error de registro informa en el visor y no impide mostrar el documento abierto.

documentModifiedAt es nullable y procede exclusivamente de COLUMN_LAST_MODIFIED del proveedor, con milisegundos positivos. Si falta, no se sustituye por la hora actual ni por la apertura. lastOpenedAt se usa únicamente para ordenar; Inicio no lo muestra. Su orden se serializa y desempata con máximo anterior + 1 ms, incluso si se atrasa el reloj. Nombre y modificación se consultan por separado para tolerar proveedores que no admitan ambas columnas. La fecha/hora visible sigue idioma, formato horario y zona del dispositivo.

Se solicita permiso de lectura persistente tras apertura correcta, aceptando que algunos proveedores solo permiten lectura temporal. Si falla acceso o apertura, una referencia existente se marca no disponible y puede quitarse; no se elimina el archivo origen ni se liberan permisos que otra referencia pudiera necesitar. La tarjeta provisional deja de mostrarse para evitar duplicados.

PdfThumbnailCache abre un documento a la vez bajo Mutex, renderiza solo su primera página en IO y publica PNG de hasta 180 px en cacheDir/recent_pdf_thumbnails. La caché descartable tiene límite técnico de 8 MiB; no limita los recientes ni almacena imágenes en Room. La clave incluye URI y modificación (o apertura cuando no se conoce). Un error de miniatura muestra icono PDF sin quitar la fila. No cambia el guardado del PDF ni se escribe ninguna salida.

La prueba focalizada de migración construye una base aislada con el SQL/hash v1 y una fila firmada, abre con Room v2 y verifica todos sus valores, la tabla nueva y un upsert reciente sin alterar la fila anterior. Resultado ejecutado y límites en §19; no requiere la suite completa ni consulta filas privadas de la base de producción.

## 16. Menú de Inicio

HomeNavigationDrawer envuelve la navegación pero solo permite apertura por gesto en Inicio y se cierra al cambiar de destino. La hamburguesa pertenece al encabezado de Inicio; el FAB Firmar PDF mantiene su contrato y cancelación SAF. HomeAppLinks consulta la versión instalada a PackageManager y construye los enlaces de Play con context.packageName, sin fijar una versión ni inventar un applicationId.

No se encontraron URL legales reales. Los recursos privacy_policy_url y terms_conditions_url están vacíos; sus opciones permanecen visibles y muestran Enlace pendiente. La respuesta «prueba» no es una URL y no se usa como enlace. Se necesitan las dos URL HTTPS reales de Política de privacidad y Términos y condiciones; no se generan textos legales.

Califícanos intenta market y, si no hay handler, HTTPS. El aviso aclara que una ficha aún no publicada no estará disponible; la app no puede deducir publicación por el mero éxito de un intent ni afirma haberla verificado. Si no hay ningún handler o se rechaza el intent, muestra error. Compartir app usa ACTION_SEND text/plain y el selector de Android. No se añaden permisos ni se hace una petición de red automática para comprobar enlaces.

## 17. Herramienta Fecha

PdfDateFormat genera cuatro formatos con java.time a partir de LocalDate.now() en la zona actual del dispositivo. El sheet de dos columnas toma la referencia de la captura, usando día/mes/año como opción inicial recomendada; los ejemplos son dinámicos y el mes largo se presenta en español. El reloj se consulta de nuevo al elegir. PdfDateAnnotation conserva fecha, formato, texto y geometría normalizada; una futura exportación deberá utilizar ese texto capturado, aunque cambie el día o la zona horaria.

PdfDateRenderer genera solo la imagen de presentación en memoria. No se escribe un PNG de fecha en la biblioteca ni se añade una fila Room. PdfDateViewModel conserva su borrador por destino y delega movimiento, límites, tamaño y cambio de página a la geometría existente. PdfPlacementOverlay sustituye el nombre específico de firma porque ahora comparte controles entre firmas y fechas, con descripciones accesibles diferenciadas. No hay firma digital certificada ni exportación implementada. La fecha de estas marcas es independiente de documentModifiedAt de Recientes.

## 18. Dibujo libre en el borrador

PdfDrawingViewModel mantiene el modo, estilo, trazo activo y trazos terminados; cada PdfPageStroke conserva página, puntos normalizados y grosor relativo al ancho completo de la página. El estilo se captura al iniciar cada trazo. El detector del viewport consume el gesto durante el dibujo y no desplaza ni amplía el PDF. Un trazo se mantiene en su página, recortado a sus bordes; al cruzarlos los puntos se limitan a esa página. Una segunda punta o cancelación descarta solo el trazo en curso. Terminar restablece los gestos normales; Deshacer y Borrar dibujos solo afectan los trazos, nunca firmas o fechas.

PdfDrawingOverlay reutiliza el renderizador de trazos existente para dibujar sobre la imagen de página con su transformación visible. Los controles usan colores accesibles, grosor 1–10 cada 0,5 y Terminar siempre visible; no se modifica Crear firma ni se guardan estos trazos en SavedSignatureStore. PdfPageImage y PdfEditorToolbar separan representación de página y barra para mantener responsabilidades acotadas en el visor. Los tres borradores sobreviven a recreación normal de Activity mediante ViewModel; no se promete recuperación tras muerte de proceso ni persistencia al salir del documento. Exportación, original, copia final, etiqueta Firmado, texto libre, sellos, anuncios, compras y Analytics no forman parte del incremento.

## 19. Verificación de los cuatro bloques del 23/10/2026

Se ejecutó inicialmente `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`: falló en compileDebugKotlin (12 s) porque una limpieza de imports había retirado getValue/setValue de PdfPreviewScreen. Se restauraron esos dos imports. Para respetar el límite de una ejecución se pidió autorización; el usuario respondió «Recompilar y verificar la migración». La recompilación adicional del mismo comando terminó con BUILD SUCCESSFUL (17 s, 65 tareas: 16 ejecutadas y 49 al día). Se compilaron app y APK de pruebas; no se ejecutaron suites con Gradle. Permanece el aviso no bloqueante previo sobre el destino futuro de StringRes; no se cambiaron flags ni versiones para ocultarlo.

APK final confirmado en app/build/outputs/apk/debug/app-debug.apk y prueba en app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk. Se actualizaron ambos con adb install -r (Success), sin desinstalar ni borrar datos. Se ejecutó solo:

```text
adb shell am instrument -w -r -e class com.zonadev.firmarpdf.data.signedpdf.SignedPdfDatabaseTest#migrationOneToTwoKeepsSignedRowAndSeparatesRecentPdfs com.zonadev.firmarpdf.test/androidx.test.runner.AndroidJUnitRunner
```

Resultado: OK (1 test), 0,164 s de prueba. Se comprobó Room v2 y conservación de ID 7, URI, nombre, fecha 100 y NEW_DOCUMENT de la fila anterior en la base aislada; recent_pdfs inicialmente vacía y upsert del mismo URI a apertura 300 sin duplicados ni cambios en signed_pdfs. Se eliminó únicamente la base aislada creada por esa prueba. La salida está en app/build/reports/migration-v1-v2.txt. No se ejecutó connectedDebugAndroidTest, la suite completa ni pruebas largas de firmado, dibujo de firma o vista previa. No se hicieron recorridos de interfaz.

También se compararon los esquemas exportados: signed_pdfs conserva exactamente sus campos, índice y SQL; 1.json permanece intacto y 2.json añade recent_pdfs (hash v2 d1562d455bc5e6f647f4bc592a30db54). Una comprobación SQL local adicional conservó una fila anterior y verificó que el CREATE de la migración coincide con 2.json; esta comprobación no se presenta como sustituto del test Room, que sí se ejecutó. Se revisaron XML, referencias y diff. Manifest, permisos, dependencias, versiones de bibliotecas y módulo único :app no cambian.

La compilación acredita integración y empaquetado, y la prueba aislada acredita la migración descrita. Los flujos manuales pendientes están separados en HOME-001, MENU-001, DATE-001 y DRAW-001: SAF/cancelación/reapertura/permisos y miniaturas; enlaces y compartir; selección y reloj de fechas; dibujo, controles y Terminar; posición, arrastre entre páginas, zoom y recreación de Activity. Ninguna de estas verificaciones acredita exportación ni un PDF firmado válido.

## 20. Guardar siempre un PDF nuevo: implementación actual

La política vigente reemplaza las propuestas históricas de actualizar el original, también para SAF con escritura disponible. Guardar crea un PDF NUEVO en `Documents/FirmarPdf/`. El origen solo se abre para lectura con ContentResolver; nunca se trunca, elimina ni reemplaza. No se usa saveIncremental. Se conservan las versiones, un módulo :app, Room v2, sus entidades, esquema y datos. Compartir el resultado sigue pendiente.

### Exportador y destino público

`PdfExportDraft` captura únicamente firmas colocadas, imágenes de fecha ya fijadas y trazos terminados, sin la firma seleccionada pendiente ni el trazo activo. `PdfExporter` serializa exportaciones mediante Mutex y trabaja en IO. PDFBoxResourceLoader ya se inicializa en FirmaPdfApplication y se reutiliza. La lectura inicial es un stream; no se copia anticipadamente a un archivo de entrada. PDFBox usa MemoryUsageSetting mixto con 32 MiB y su almacenamiento temporal interno en cacheDir; esto no equivale a un límite total de memoria. No se ha necesitado un fallback temporal propio para proveedores; su compatibilidad queda pendiente de probar.

Se consultó la geometría y appendSignatures del exportador local de LectorDocumentos, sin trasladar su visor ni monetización y sin modificar ese proyecto. La adaptación actual de firmas se detalla en §22: fórmula explícita del proyecto de referencia y stream dedicado sin recorte previo. `PdfExportPage` conserva la geometría de fechas y puntos de dibujo. Cada página mantiene sus cajas y rotación; no se rasteriza. Los trazos mantienen puntos, color, alfa y grosor relativo al ancho visual de su página; se usan extremos/uniones redondos y un punto aislado se pinta como círculo. El orden base es dibujo, firmas y fechas, ahora en streams separados; la elevación temporal de una colocación seleccionada en el visor no altera el PDF. Las fechas se exportan desde la imagen capturada, sin consultar otra vez el reloj. Si falta una imagen se falla sin omitir silenciosamente una anotación.

Antes de crear salida se rechazan PDFs cifrados/no modificables, con diccionarios de firma digital o certificación DocMDP, y documentos cuyo número de páginas difiera del abierto. No se ofrecen capacidades de firma digital. Los errores del proveedor no se registran con su excepción ni se envían a Firebase.

`PublicPdfDestination` solo administra salidas creadas por él. API 29+ usa MediaStore.Files, MIME application/pdf, RELATIVE_PATH Documents/FirmarPdf/ e IS_PENDING=1. El nombre documento_signed.pdf recibe sufijos ante colisiones y MediaProvider reserva un archivo distinto. API 28 solicita el permiso WRITE_EXTERNAL_STORAGE ya declarado únicamente al pulsar Guardar; escribe un temporal oculto de salida en la carpeta pública y publica por renombrado a una reserva vacía creada por la app, sin sustituir resultados anteriores. Después intenta indexarlo con MediaScanner (espera limitada a 5 s); si no devuelve URI se conserva el file URI propio para lectura interna. No se añade FileProvider ni se implementa Compartir.

El save normal escribe solo en el destino nuevo. Tras cerrar salida/documento, se exige tamaño escrito mayor que cero y se publica quitando IS_PENDING o renombrando. Por decisión explícita del usuario (§23), se elimina la segunda carga PDFBox y su comparación de páginas: no se hace otra lectura completa del resultado. Un fallo/cancelación anterior elimina únicamente la salida pendiente. El cierre de streams al cancelar ayuda a desbloquear operaciones del proveedor; la respuesta de proveedores que bloqueen su propia apertura sigue siendo una limitación externa pendiente de probar. La publicación y el registro se completan sin cancelar entre ambos; un archivo ya publicado nunca se elimina por fallo Room.

`PdfExportHistory` registra signed_pdfs con NEW_DOCUMENT y recent_pdfs con el MISMO URI en una transacción. Si falla esta parte se informa que el PDF se guardó pero no se registró en Inicio. No se cambian tablas ni se añade migración. Las miniaturas siguen generándose con el URI y modificación reales del nuevo archivo; no se reutiliza la del original.

### Visor, Inicio y entradas externas

`PdfExportViewModel` conserva progreso, resultado y una instantánea de lo guardado. Guardar requiere alguna anotación terminada, sin un trazo activo ni otra operación de guardado; muestra etapas y cancelación. El error conserva los borradores para reintentar. El éxito indica claramente la creación de otro archivo y ofrece ir a Inicio. Atrás del sistema confirma el descarte si hay cambios sin guardar. Se retira solo el porcentaje textual de zoom; los gestos existentes se mantienen. Los borradores sobreviven a recreación normal mediante ViewModel, sin prometer recuperación tras muerte del proceso.

RecentPdfDao.recordOpened inserta URI nuevos con un orden monotónico y, ante un URI existente, actualiza solo nombre, modificación real y disponibilidad. Reabrir o colocar anotaciones no mueve la fila. El resultado recién creado queda primero sin mover el original. Inicio elimina papelera y sección Documentos firmados; signed_pdfs se conserva para registro y futura etiqueta, todavía sin etiqueta visible. La ubicación ocupa el ancho de la tarjeta hasta dos líneas: ruta relativa real de MediaStore, carpeta propia para resultados legados o etiqueta del proveedor/Ubicación no disponible. No se deducen rutas absolutas a partir de content URI. La fecha/hora sigue siendo modificación real, no apertura. Drawer, URL legales pendientes, miniaturas y FAB se conservan.

MainActivity procesa exclusivamente ACTION_VIEW/ACTION_SEND application/pdf desde data, EXTRA_STREAM o ClipData, tanto en onCreate como onNewIntent. Conserva el Intent y sus concesiones temporales; no presupone permisos persistentes de Gmail/WhatsApp. IncomingPdfViewModel mantiene la entrada pendiente pequeña durante recreación. Navegación consume la entrada una vez y evita abrir otra instancia si el mismo URI ya está visible; documentos diferentes reciben sus propios ViewModel/borradores. Una entrada recibida mientras se crea una firma se atiende al regresar al visor. Si el acceso falla, se muestra el error sin descartar los borradores existentes.

### Mediciones y verificación de este incremento

Solo debug emite `PdfExportTiming` con elapsedRealtime: sourceMs, loadMs, annotateMs, saveMs (solo document.save), writeStageMs (reserva/escritura/cierre), publishMs (sin segunda carga, antes verifyPublishMs), historyMs y totalMs, tamaños disponibles (-1 desconocido), access=stream y éxito, más los campos del búfer descritos en §21. No incluye nombres, rutas, URI, excepciones, contenido, imágenes ni puntos. No se envía a Firebase. Las observaciones aportadas por el usuario se registran en BACKLOG; no se afirma velocidad ni un máximo seguro de memoria.

Se ejecutó una sola vez `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests com.zonadev.firmarpdf.data.pdf.PdfExportGeometryTest --console=plain`: BUILD SUCCESSFUL en 1 min 3 s, 45 tareas (22 ejecutadas/23 al día). APK generado en app/build/outputs/apk/debug/app-debug.apk. El XML de testDebugUnitTest confirma cuatro pruebas JVM superadas, cero omitidas/fallos/errores (0,031 s): rotaciones con offsets de CropBox, orientación/matriz de imagen, normalización de rotación/ancho visual y rechazo de coordenadas no finitas. Estas pruebas validan la conversión matemática, no el PDF exportado/renderizado. Permanecen dos avisos por los métodos RGB enteros obsoletos de PDFBox y el aviso previo del destino futuro de anotación Kotlin; no se cambiaron flags ni versiones para ocultarlos.

También pasaron XML, referencias de recursos y diff; el esquema Room v2 conserva el hash d1562d455bc5e6f647f4bc592a30db54. Una comprobación SQL local usó el CREATE exportado y las consultas reales del DAO para verificar que reabrir A tras B conserva B/A y que un resultado nuevo queda primero sin mover A. No es una prueba Room ni de interfaz. No se instaló la app, no se usó el Huawei ni se ejecutó la batería instrumentada. Pendiente comprobar SAF local, PDF grande, Gmail, WhatsApp, firmas en páginas rotadas/recortadas y mixtas, fecha fijada, dibujo terminado, cancelación, colisiones, permisos Android 9, reapertura/miniatura del resultado, fallos de Room/proveedor/espacio y hash del original intacto. Exportación implementada no equivale a flujo verificado; FIR-001 sigue pendiente de validación real.

## 21. Diagnóstico del tiempo de save y comparación con LectorDocumentos

Esta comparación describe la etapa anterior a EXPORT-003. §23 sustituye la finalización con reapertura y el nombre verifyPublishMs; las métricas de save/búfer siguen vigentes.

Se leyó el proyecto LectorDocumentos sin modificarlo: `core/PdfSignatureExporter.kt`, sus helpers de entrada/salida/seguridad/nombres/publicación, `normalizedPlacementToPdfMatrix` y `nextSignedPdfFileName` de `data/signature/PdfSignatureGeometry.kt`, modelos PdfItem/SavedPdfSignature y la llamada real desde PdfSignatureViewModel. Ambos proyectos declaran PDFBox Android 2.0.27.0. La observación Huawei y el estado de la comparación se registran en PERF-EXPORT-001/002 de BACKLOG, sin deducir una causa a partir de una sola duración.

| Aspecto comprobado | LectorDocumentos | FirmarPDF |
| --- | --- | --- |
| Entrada | readableSourceFile prueba file URI y path ya almacenado en PdfItem; si existe y es legible, load(File). Si no, openInputStream(uri) y load(stream); no copia propia previa del PDF. | Siempre ContentResolver.openInputStream; no se deducen rutas desde content URI ni se copia previamente el origen. |
| load y memoria | load(file/stream, password.orEmpty(), setupTempFileOnly().setTempDir(cacheDir)). | load(stream, setupMixed(32 MiB).setTempDir(cacheDir)); la lectura y buffers internos pueden diferir. No se cambian en este diagnóstico. |
| Incorporación | Firmas agrupadas por página; BitmapFactory desde PNG privado, LosslessFactory con caché por ID y reciclado del bitmap; APPEND, compress=true, resetContext=true; matriz por CropBox/rotación. | Misma configuración de PDPageContentStream y caché por firma; además imágenes de fecha y trazos terminados. No se cambia geometría ni incorporación. |
| save y búfer | save(OutputStream), sin BufferedOutputStream explícito: FileOutputStream a temporal junto al origen si su carpeta permite escritura; de lo contrario MediaStore en API 29+ o Documents legado. No llama save(File). | save(OutputStream) sobre un NUEVO destino público MediaStore o archivo pendiente legado. Antes solo había wrapper de cancelación/conteo; ahora se agrupa la salida en 64 KiB. |
| Cierre/publicación | Después de save registra flush; local además fd.sync con SyncFailedException capturada, rename/FileProvider y solicitud asíncrona de MediaScanner. MediaStore quita IS_PENDING y consulta tamaño. No reabre el resultado para comprobar páginas. | Cierre y vaciado dentro de save; después se mantiene reapertura PDFBox e igual número de páginas antes de publicar. Después registra Room. No se añade fsync ni se cambia el destino. |
| Mediciones existentes | sourceResolutionDurationMs, loadDurationMs, appendDurationMs, saveDurationMs, flushDurationMs, fsyncDurationMs (local), renameDurationMs (local), publishDurationMs y totalExportDurationMs; sourceAccessMode y destination identifican las ramas. | Conserva sourceMs/loadMs/annotateMs/saveMs/writeStageMs/verifyPublishMs/historyMs/totalMs, inputBytes/outputBytes/access/success. Nuevos campos descritos abajo. |

Se inspeccionó también el sources.jar de la dependencia 2.0.27.0 realmente almacenada en Gradle: PDDocument.save(OutputStream) construye COSWriter y lo cierra en finally; COSWriter/COSStandardOutputStream no añaden búfer y reenvían también write(int) y bloques pequeños. Solo la sobrecarga save(File), que ninguno de estos exportadores usa, añade BufferedOutputStream automáticamente. El proveedor y el sistema pueden tener sus propios buffers; no se asume que equivalgan a agrupar las llamadas Java. Las escrituras pequeñas se comprueban en el código del serializador; su volumen y coste reales en Huawei se conocerán con los contadores nuevos. El búfer es una mejora candidata, no una velocidad demostrada ni una diferencia previa a favor de LectorDocumentos.

Como save cierra el stream, los flush/fsync que LectorDocumentos registra después no son automáticamente trabajo de escritura pendiente: el fsync local puede fallar y quedar igualmente medido por su finally. Comparar saveMs con saveDurationMs requiere el mismo PDF, las mismas firmas y la misma categoría de destino. Fuente directa frente a stream y memoria temporal frente a mixta pueden afectar load y lecturas durante save; no se les atribuye una ventaja sin medición. annotateMs incluye más herramientas que appendDurationMs. verifyPublishMs incluye reapertura completa, ausente en publishDurationMs de LectorDocumentos, y el total tampoco incluye las mismas etapas ni Room.

`PdfSaveOutputStream` agrupa escrituras en 64 KiB, comprueba cancelación antes de cada escritura lógica y antes de tocar el destino, y cuenta bytes confirmados por el stream físico también sin diagnóstico. Al cerrar vacía el búfer; al cancelar evita volcar lo pendiente y cierra siempre el destino, incluso si falla flush. El cierre repetido es inocuo. El cierre externo/limpieza de PdfExporter se conserva; solo se descarta la salida nueva pendiente. PublicPdfDestination aporta únicamente una categoría fija de almacenamiento para el log, sin nombre/URI/proveedor. No hay cambios de política: siempre otro PDF, nunca saveIncremental, y verificación posterior obligatoria.

Campos nuevos, solo en el log debug PdfExportTiming:

- `destWriteMs`: suma del tiempo transcurrido en las llamadas write del stream REAL de destino, debajo del búfer; no el coste de cada llamada pequeña de PDFBox.
- `destFlushCloseMs`: suma del tiempo en flush y close del stream real durante save. No incluye de nuevo los write que vacían el búfer, medidos en destWriteMs.
- `pdfBoxOtherMs`: saveMs menos ambos tiempos acumulados. Incluye serialización, lecturas de origen/scratch, copia al búfer, comprobaciones, instrumentación y planificación; no es una medida de CPU pura. Hay redondeo a ms (aproximadamente 1 ms de margen).
- `pdfWriteCalls`/`pdfSmallWrites`: llamadas lógicas no vacías recibidas de PDFBox y cuántas tienen ≤1024 bytes; `destWriteCalls`: llamadas no vacías efectuadas al destino después del búfer. Son intentos; outputBytes cuenta solo bytes cuyo write terminó correctamente. Un fallo de escritura parcial no permite conocer todos los bytes físicos y no se publica.
- `bufferBytes=65536`, `destination=media-store|legacy-file|unopened`: configuración y categoría fija, sin datos del documento. access=stream sigue describiendo solo la ENTRADA.

Los tiempos de I/O se acumulan con elapsedRealtimeNanos para no redondear cada escritura pequeña a cero y se convierten a ms solo al imprimir. saveMs conserva su intervalo exacto alrededor de document.save. Se congelan estos contadores al terminar esa llamada para no atribuirle limpieza exterior. No hay mediciones por escritura ni sus contadores debug en release, excepto el conteo de bytes ya necesario. No se registran contenido, trazos, PNG, nombres, rutas, URI, excepciones ni Firebase.

Reproducción controlada: usar el mismo original (no un resultado ya anotado), el mismo PNG/cantidad/tamaño de colocaciones, sin fechas/dibujos al comparar con el exportador de firmas de LectorDocumentos, y el mismo Huawei/almacenamiento/proveedor. Hacer una primera pasada separada y después varias repeticiones alternadas, anotando estado térmico y evitando otras tareas pesadas. Conservar los logs completos y sus sourceAccessMode/access y destination; comparar medianas y dispersión, sin mezclar local-file con media-store. En FirmarPDF comparar con el registro anterior saveMs/totalMs/bytes, ahora interpretados junto con destWriteMs/destFlushCloseMs/pdfBoxOtherMs y las llamadas antes/después del búfer. Confirmar éxito, páginas, aspecto de anotaciones, reapertura y original intacto. Los tests de streams son comprobaciones de integridad/mecanismo; no miden rendimiento de almacenamiento en Android.

## 22. Firmas con el mismo proceso de LectorDocumentos

Por la incompatibilidad de resultados comunicada por el usuario (EXPORT-002), se adapta de nuevo appendSignatures y normalizedPlacementToPdfMatrix de LectorDocumentos. El proyecto de referencia se consulta solo en lectura. La comparación no demuestra por sí sola la causa de la incompatibilidad; su validación en los visores afectados sigue registrada en BACKLOG.

PdfExporter.appendSignatures agrupa las colocaciones por página y usa un PDPageContentStream propio con APPEND, compress=true y resetContext=true, como la referencia. Carga el PNG privado a tamaño completo mediante SavedSignatureStore.load: internamente BitmapFactory.decodeFile, sin muestreo, preservando la arquitectura del almacén existente. La primera colocación de un ID crea el PDImageXObject con LosslessFactory.createFromImage; el bitmap se recicla en finally y la imagen PDF se reutiliza por ID entre páginas. Se calcula la matriz y se llama directamente drawImage(image, Matrix(a,b,c,d,e,f)). No se añaden addRect, clip, cambios de color, alfa, grosor ni save/restore propios al stream de firmas; los operadores internos de PDPageContentStream/drawImage son los de PDFBox, iguales al proyecto de referencia.

normalizedSignaturePlacementToPdfMatrix en PdfExportGeometry adapta las mismas expresiones explícitas de LectorDocumentos: CropBox con fallback MediaBox, offset inferior izquierdo, dimensiones visuales intercambiadas a 90/270 grados, componentes normalizados acotados a 0–1 y las cuatro matrices de rotación. No se usa para firmas la construcción anterior por resta de puntos de PdfExportPage.image. Esa función y PdfExportPage.point se conservan para fechas y dibujo respectivamente.

Se elimina el recorte previo del contenido exportado. Dibujo, firmas y fechas se incorporan en ese orden por página, con un stream distinto por herramienta y solo si contiene elementos. Dibujo y fechas acotan su estado con saveGraphicsState/restoreGraphicsState en try/finally, además de resetContext del constructor; los trazos conservan su aislamiento individual. Sus operadores no se escriben en el stream de firmas ni dejan cambios gráficos activos que este herede. No cambian el lienzo, estilos, imágenes de fecha fijadas, colocaciones visuales ni interfaz.

La corrección se limita a incorporación/geometría de firmas y aislamiento de herramientas. Se mantienen origen de solo lectura, destino NUEVO Documents/FirmarPdf, document.save normal, búfer de 64 KiB, todos los campos de PdfExportTiming y comprobación del archivo cerrado antes de publicar/registrar Room. No se usa saveIncremental ni se sobrescribe el origen. PdfExportGeometryTest añade un caso pequeño con matrices esperadas para offsets y rotaciones 0/90/180/270; esto valida geometría matemática y no sustituye abrir el resultado en los visores afectados.

## 23. Finalización sin segunda carga del PDF generado

Se releen en LectorDocumentos, solo como referencia, PdfSignatureExporter.kt, exportSignedPdf de PdfSignatureViewModel y getPdfItemFromUri/markPdfAsOpened/rememberPdfState de PdfRepository con sus consultas y transacciones. Su exportador guarda, cierra y publica (MediaStore o archivo local) y devuelve metadatos sin PDDocument.load del resultado. El ViewModel obtiene metadatos con el repositorio o usa los devueltos por el exportador, intenta registrar Recientes y pasa a RESULT; un fallo de registro deja el archivo guardado y un mensaje. El repositorio consulta metadatos, existencia e identidades y coordina Room, sin cargar íntegramente el resultado con PDFBox. No se copian su resolución de rutas, FileProvider, carpetas, identidad de archivos ni monetización.

FirmarPDF retira exclusivamente el bloque que abría target.uri con PDDocument.load después de save, su comparación con expectedPages y la variable asociada. La única carga PDFBox del exportador sigue siendo la inicial del original, con sus controles previos. Se conserva exactamente la secuencia de escritura y cierre con PdfSaveOutputStream/búfer de 64 KiB, conteo de bytes confirmados y cierre del PDDocument. Si no se escribieron bytes se falla antes de publicar. Esta comprobación superficial no analiza la estructura ni garantiza el número de páginas del resultado.

PublicPdfDestination mantiene la reserva de un archivo NUEVO sin colisiones en Documents/FirmarPdf, IS_PENDING=1 hasta terminar y publicación mediante IS_PENDING=0 en API 29+, sin usar una ruta directa para MediaStore. El camino legado tampoco cambia. La cancelación antes de publicar y los errores conservan la limpieza de la salida incompleta. Después de publicar, PdfExportHistory registra el mismo URI en signed_pdfs (NEW_DOCUMENT) y recent_pdfs dentro de una transacción Room v2. Solo consulta metadatos del destino, no carga su PDF. Si falla Room, se conserva el archivo y se devuelve recorded=false para que el visor avise que sí se guardó y falló su registro. El éxito continúa mostrándose después de publicar e intentar registrar, no al regresar de save.

Se mantienen firmas adaptadas de LectorDocumentos (§22), geometría, fecha fijada, dibujos, interfaz y callbacks. Solo se ajustan los textos de progreso/error de publicación para que no sugieran reapertura. Las claves internas VERIFY/pdf_export_verify se conservan para el control de salida no vacía/finalización sin ampliar el cambio de nombres. El original permanece de solo lectura; se sigue usando save normal en otro PDF, nunca saveIncremental.

PdfExportTiming sustituye verifyPublishMs por publishMs: duración de la etapa de salida no vacía/publicación, restando historyMs. Ya no incluye lectura completa ni conteo de páginas. sourceMs, loadMs inicial, annotateMs, saveMs, writeStageMs, historyMs, totalMs, tamaños, access, success y todos los campos del búfer mantienen sus intervalos/semántica. El tiempo retirado pertenecía al trabajo posterior a save, no a saveMs. El nuevo publishMs no es una medición equivalente al antiguo verifyPublishMs; comparar totalMs antes/después con el MISMO original, anotaciones, proveedor/destino y condiciones, conservando los demás campos para detectar variaciones. La diferencia no demuestra por sí sola una mejora del serializador ni predice ahorro exacto en todos los PDF.

El usuario informa que comprobó el PDF de 189,6 MB con firma y fecha en cinco visores de terceros antes de esta modificación. Se registra como evidencia aportada por él, no como prueba ejecutada por el agente ni como resultado de esta nueva compilación. No se conocen aquí sus nombres/versiones ni se generaliza a todos los documentos, todas las páginas, dibujo libre, proveedores o dispositivos. El alcance y estado de las verificaciones se registran en EXPORT-002/003 de BACKLOG.

Para repetir: abrir el mismo ORIGINAL de 189,6 MB, colocar firma/fecha equivalentes, guardar una copia nueva en el mismo destino y enviar la línea COMPLETA de PdfExportTiming, desde sourceMs hasta destination, incluidos publishMs/historyMs/totalMs, inputBytes/outputBytes y todas las métricas del búfer. Confirmar también que el resultado se abre con firma/fecha. Usar un registro previo de ese mismo PDF para comparar; el ejemplo histórico inputBytes=4648044 pertenece a otro tamaño y no sirve de referencia controlada para 189,6 MB. No prometer ahorro de tiempo hasta disponer del registro posterior.

## 24. Copia PdfiumPrueba sin Firebase

La copia `FirmarPDF - PdfiumPrueba` conserva `applicationId = com.zonadev.firmarpdf.pdfiumtest`, con namespace y paquetes Kotlin `com.zonadev.firmarpdf`. Se retiran la aplicación y declaración raíz de los plugins Google Services/Crashlytics, las dependencias Firebase BoM/Analytics/Crashlytics y sus entradas exclusivas del catálogo. Se elimina únicamente su `app/google-services.json`, sin adaptar clientes ni identificadores. También se retiran los metadatos de recopilación de esos SDK del manifiesto.

El arranque existente en `FirmaPdfApplication` inicializa PDFBox y mantiene el contenedor local de forma diferida. El código fuente no contiene inicializaciones, llamadas ni interfaces de Analytics/Crashlytics; por ello no se añaden implementaciones sin operaciones. Sin los SDK no se incorpora su inicialización automática mediante providers. Las referencias anteriores a Firebase describen la integración histórica o decisiones de producto, no una dependencia de esta copia.

En ese incremento se mantuvieron un único módulo `:app`, las versiones de todas las dependencias conservadas, PDFium, PDFBox, Room, navegación, visor, firmas y exportación. La política de guardado permanece igual: PDF nuevo con save normal, sin sobrescribir el origen. La retirada de Firebase no incorporó puente JNI ni guardado incremental; el binding local posterior se describe en §25. El proyecto original queda fuera de esta decisión. El alcance y resultado de la verificación se registran exclusivamente en BUILD-003 de `BACKLOG.md`.

## 25. Exportador experimental de firmas con PDFium

### Biblioteca y alcance

Por petición explícita, esta copia añade el módulo Android de biblioteca `:pdfium-local`
en `third_party/pdfiumandroid`, además de `:app`. Es una excepción acotada a la
regla general de un único módulo, necesaria para modificar el puente JNI existente.
Procede de PdfiumAndroidKt, etiqueta `v1.0.35`, revisión
`2052da7c071002446f1e2dff082b373a17ebadd0`. Se conservan fuentes principales,
headers, licencias y avisos, sin aplicación de ejemplo ni módulos Arrow/publicación.
`README.FirmarPDF.md` documenta origen y modificaciones; `prebuilt/SHA256SUMS.txt`
identifica los cuatro `libpdfium.so` vigentes. Los originales de la etiqueta se
sustituyen por el motor corregido de §27, junto con sus headers públicos/licencias;
se conserva la procedencia del binding y los auxiliares Android include/utils.
La dependencia Maven se sustituye
por el proyecto local: una sola definición de clases y un solo puente compilado.

El anfitrión mantiene AGP 8.9.2, Kotlin 2.2.0, Gradle 8.11.1 y Java/JVM 11,
PDFBox, Room, Compose y navegación. El módulo adapta el build upstream sin adoptar
su toolchain. Usa NDK instalado 26.3.11579264 y CMake 3.22.1 explícitos, frente al
NDK 29.0.13846066 declarado por la etiqueta. Compila únicamente el puente C++17,
con STL estática y opciones de linker para segmentos de 16 KB. CMake conserva
`jnigraphics`, `android` y `log` e importa PDFium precompilado desde `prebuilt`
para enlazar. El sourceSet jniLibs apunta explícitamente a esa carpeta para
empaquetar una sola copia de cada binario junto al puente. No se compila PDFium.

### Inserción y geometría

`PdfPage.insertImage` y `generateContent` respetan `PdfiumCore.lock` y comprueban
que documento/página estén abiertos. El JNI accede a `DocumentFile.pdfDocument`,
no convierte el wrapper en FPDF_DOCUMENT. Acepta Bitmap de software ARGB_8888/sRGB;
el exportador decodifica PNG completos propios y convierte configuraciones no
ARGB_8888 mediante copia, sin prestar ni alterar bitmaps de la UI o del almacén.

La conversión valida dimensiones, formato, alfa y stride. Lee RGBA Android y
escribe BGRA de alfa independiente en un bitmap propiedad de PDFium, respetando
ambos strides. Intercambia R/B una vez; despremultiplica con redondeo/clamp,
conserva alfa parcial y pone RGB a cero con alfa cero. El modo obtenido de
`Bitmap.hasAlpha/isPremultiplied` cubre API 28/29, donde flags aún era reservado;
desde API 30 se contrasta con AndroidBitmapInfo.flags. Cada lock exitoso tiene
unlock mediante RAII; nunca se libera memoria Android ni se utiliza tras unlock.

Se crean bitmap y objeto imagen, se asigna con FPDFImageObj_SetBitmap y se fija
la matriz completa con FPDFImageObj_SetMatrix. La matriz transforma la imagen
unitaria, no sus dimensiones en píxeles. FPDFPage_InsertObject devolvía void con
el motor inicial. Con los headers vigentes de §27 devuelve FPDF_BOOL y consume el
objeto tanto al transferirlo a la página como al liberarlo si falla; se comprueba
su retorno y se anula el propietario RAII en ambos caminos. Se mantienen los
controles de conteo/identidad después del éxito. Los recursos propios se liberan con RAII;
un fallo posterior abandona el documento privado, sin publicar una salida parcial.
`generateContent` se llama una vez por página modificada tras todas sus firmas.

`PdfiumExportGeometry` adapta FPDF_GetPageBoundingBox (intersección efectiva
MediaBox/CropBox en coordenadas PDF, con top mayor que bottom) y la rotación
intrínseca de PDFium (0..3 cuartos de vuelta). Reutiliza
`normalizedSignaturePlacementToPdfMatrix` y `PdfExportPage` para obtener los seis
coeficientes en el espacio PDF sin girar, incluyendo offsets. No usa
`getPageMatrix` del binding, no añade otra inversión vertical ni otra rotación.
Las comprobaciones matemáticas no certifican la posición visual del PDF nativo.

### Recorrido separado y comparación

`PdfiumSignatureExporter` abre un descriptor de solo lectura desde el URI y su
propio PdfDocument. El documento del visor conserva su ciclo de vida y no se
modifica. No hay copia previa del origen, ByteArray del PDF ni llamadas PDFBox
en el recorrido experimental. La inicialización general de PDFBox para el botón
Guardar existente permanece en Application.

Antes de abrir/exportar se rechazan borradores sin firmas o con fechas/dibujos,
sin omitir elementos. Se comprueban número de páginas y restricciones mediante
permisos/cifrado y FPDF_GetSignatureCount. Este control no equivale a validar
estructuralmente todas las variantes de firmas/certificación de PDFs dañados.
Cada PNG se carga completo una vez por exportación; sus colocaciones se agrupan
por página y se insertan todas. La memoria de imágenes distintas puede crecer
con el borrador; una falta de memoria cancela la salida y conserva el editor.

El modo FULL llama `saveAsCopy(callback, FPDF_NO_INCREMENTAL)` explícitamente.
La opción incremental posterior (§26) reutiliza todo este recorrido con
FPDF_INCREMENTAL, sin combinar flags ni cambiar el comportamiento predeterminado.
Reutiliza PublicPdfDestination, PdfSaveOutputStream (64 KiB), estados de progreso,
errores y PdfExportHistory/Room v2. La reserva/escritura es siempre otro PDF en
Documents/FirmarPdf. Un fallo del callback devuelve cero, conserva su excepción
para propagarla y hace fallar el guardado; JNI libera las referencias locales de
cada bloque, comprueba excepciones y no interpreta -1 como éxito. El cierre del
búfer también puede hacer fallar el resultado. El puente elimina además un unlock
duplicado del mutex de inicialización de upstream.

La salida y documento/descriptor/imágenes se cierran antes de publicar; se exige
conteo de bytes mayor que cero. No se reabre el resultado. Cancelación y fallos
anteriores limpian únicamente la salida pendiente. Publicación y registro se
completan sin cancelar entre ambos; un fallo Room conserva el PDF publicado y
avisa. Proveedores bloqueados pueden demorar la cancelación; no se promete
interrupción instantánea de una llamada nativa o apertura del proveedor.

`Guardar` sigue utilizando PdfExporter/PDFBox con firmas, fechas y dibujos.
`Guardar con PDFium` (FULL) y la acción posterior `Guardar con PDFium incremental`
(§26) se muestran solo con BuildConfig.DEBUG y usan el mismo
PdfExportViewModel, borrador y diálogo final. Todos los textos nuevos son recursos
en español. Ambos exportadores comparten un Mutex de AppContainer, además de la
exclusión de doble pulsación del ViewModel; no se ejecutan simultáneamente incluso
desde distintos destinos de navegación. El borrador no se borra al guardar y el
éxito no deshabilita el otro motor. Para comparar se debe elegir «Seguir en el visor»
en el diálogo de resultado y exportar por el otro motor desde el mismo original,
sin mover ni redimensionar las mismas firmas.

### Intervalos de PdfiumExportTiming

Solo debug registra una línea final mediante `Log.d("PdfiumExportTiming", ...)`,
independiente del logger configurable del visor y de Firebase. Se conserva el
resumen existente y sus campos. Se acumulan nanosegundos de
`SystemClock.elapsedRealtimeNanos()` en Kotlin y `clock_gettime(CLOCK_BOOTTIME)`
en el puente nativo, relojes monotónicos de tiempo transcurrido que incluyen
suspensión. Se convierten a milisegundos enteros al
emitir, también con fallo/cancelación si se alcanza finally. Los campos de etapas
originales mantienen sus alcances disjuntos; los contadores nuevos son mediciones
anidadas, sin nombres, rutas, URI, contenido ni excepciones originales:

| Campo | Operaciones incluidas |
| --- | --- |
| openMs | Metadatos de origen, apertura del descriptor/PDFium, número de páginas y restricciones. PDFium puede leer páginas/contenido de forma diferida después. |
| imagesMs | Lectura de PNG completos y preparación de bitmaps propios. |
| insertMs | Apertura/cierre de páginas, geometría, conversión nativa de píxeles, inserción de todas las imágenes y una generación de contenido por página. |
| saveMs | Reserva del destino, apertura de salida, saveAsCopy/callbacks, vaciado del búfer, cierre de salida y coordinación de su cierre al cancelar. |
| closeMs | Cierre normal del documento/descriptor y liberación de imágenes antes de publicar. |
| publishMs | Únicamente PublicPdfDestination.publish, sin Room ni reapertura. |
| historyMs | Metadatos del resultado y transacción Room; no carga el PDF generado. |
| totalMs | Operación completa, incluidos huecos entre etapas y limpieza final también ante error. No se suma otra vez a las etapas. |
| inputBytes/outputBytes | statSize del descriptor (-1 desconocido) / bytes confirmados en el stream físico de salida. |
| success/failure | Éxito de publicación o categoría fija del fallo/cancelación, sin datos del documento. |

Validaciones previas de borrador y BUSY no abren exportación ni emiten esta línea.
`load`/apertura implícita en openMs no significa haber leído todo el archivo:
PDFium puede diferir lecturas al insertar, generar contenido y guardar.

La inspección del recorrido muestra: FPDF_SaveAsCopy → FileWrite::WriteBlockCallback
(NewByteArray y SetByteArrayRegion por bloque) → PdfWriteCallback.WriteBlock Kotlin
→ PdfSaveOutputStream → BufferedOutputStream de 64 KiB → sink → OutputStream de
ContentResolver. El callback sí utiliza ese búfer y no llama flush ni close.
Hay una única apertura de salida, sin acumular el PDF completo en un ByteArray
del exportador. El ByteArray es por bloque; no se deduce el consumo interno de
memoria de PDFium a partir de eso. La búsqueda de clase/método JNI ocurre una vez
por saveAsCopy, mientras la asignación/copia de arrays y comprobaciones JNI se
repiten por bloque. No se modifica ese puente para optimizar un coste no medido.
El cierre del búfer ocurre en checked.use; abortable y raw.use cierran además
el raw como protección normal/cancelación, sin reaperturas ni cierres por callback.
Se conserva esa secuencia y no se añade otra capa de buffering.

| Campo nuevo | Intervalo o contador |
| --- | --- |
| callbackCalls | Entradas en WriteBlock Kotlin, incluso la que falla o solicita cancelar. |
| callbackBytes | Suma de tamaños recibidos, aunque después falle escribirlos; null no aporta bytes. No son bytes confirmados del destino. |
| callbackSmallWrites | Callbacks con ByteArray de menos de 1024 bytes, incluido tamaño cero; null no se cuenta. |
| callbackMs | Tiempo acumulado dentro del callback Kotlin, incluidos contadores, comprobación de cancelación y output.write; no incluye necesariamente asignación/copia del ByteArray hecha antes en C++. |
| destWriteCalls | Intentos de write no vacíos al stream real, debajo del búfer; outputBytes sigue contando solo bytes cuyo write terminó. |
| destWriteMs | Tiempo acumulado en esos write reales, también si lanzan una excepción; incluye los que vacían el búfer después de saveAsCopy. |
| destFlushCloseMs | Tiempo en flush y close del sink real a través del wrapper existente; excluye las escrituras que vacían el búfer, incluidas en destWriteMs, y los cierres defensivos directos de raw.use/abortable. No mide fsync ni garantiza persistencia física. |
| bufferBytes | 65536, sin cambiar el búfer existente. |
| saveOtherMs | saveMs histórico (etapa completa de escritura) menos el tiempo de callbacks que ocurrió dentro de saveAsCopy. Incluye preparación del destino y vaciado/cierre posteriores; no es un tiempo exclusivo del motor. |
| saveCallMs | Solo doc.saveAsCopy y su retorno/excepción, antes de checked.use/flush/close. Incluye espera del lock del binding y trabajo JNI/PDFium/callbacks. |
| saveCallOtherMs | saveCallMs menos callbacks ocurridos dentro de esa misma llamada. Excluye el vaciado/cierre posteriores. No es tiempo de CPU: incluye lecturas internas, serialización, esperas y trabajo JNI antes/después de Kotlin. |
| destination | media-store, legacy-file o unopened; categoría técnica fija. |

Los residuos se calculan en nanosegundos antes de convertir, con mínimo cero.
`callbackMs` está dentro de `saveCallMs` y del `saveMs` histórico. Parte de
`destWriteMs` está dentro de callbackMs y otra puede ocurrir después de saveAsCopy
al vaciar el búfer; no se resta todo destWriteMs ni destFlushCloseMs a saveCallMs.
El flush final escribe los bytes pendientes, contabilizados en destWriteMs,
y luego hace flush/close del sink, contabilizados en destFlushCloseMs. Los tiempos
no se suman como etapas independientes. El resumen sigue emitiéndose una sola vez
después de la limpieza, sin logs por bloque y sin alterar propagación de errores,
cancelación ni publicación para obtener mediciones.

Para conservar la comparación con registros previos, `saveMs` NO cambia de
significado. El saveMs de PDFBox mide solo document.save y se compara con
saveCallMs de PDFium considerando las diferencias de carga/serialización; para
la etapa de salida completa, considerar writeStageMs de PDFBox frente a saveMs
de PDFium, y closeMs por separado según su alcance. No sumar los campos anidados
a totalMs ni atribuir a una sola etapa el trabajo de carga diferida. Alternar motores
con el mismo original, firmas, posiciones, dispositivo/proveedor y condiciones,
separar primera pasada de repeticiones y comparar totalMs, dispersión y etapas.
No se deduce una mejora de rendimiento por compilar ni por una sola observación.

### Lectura completa y diagnóstico nativo por documento

`FPDF_FILEACCESS.m_Param` referencia ahora su `DocumentFile`, con un descriptor
prestado cuya propiedad sigue siendo del ParcelFileDescriptor Kotlin. El contexto
vive hasta terminar FPDF_CloseDocument; no se comparte con el documento del visor.
`getBlock` usa `size_t` para tamaños, `ssize_t` para el retorno y `off64_t/pread64`
para offsets, con comprobaciones de rango antes de convertir o sumar. Un bucle
completa todos los bytes, avanza solo por los bytes realmente leídos, reintenta
EINTR y devuelve cero ante EOF anticipado o error definitivo. No utiliza lseek/read
ni cambia la posición compartida del descriptor. fstat64 evita truncar el tamaño;
se rechazan longitudes no representables en el unsigned long de FPDF_FILEACCESS
(especialmente en ABI de 32 bits), en vez de truncarlas silenciosamente.

Cada DocumentFile tiene su propio estado de diagnóstico, desactivado inicialmente.
Solo el exportador y solo con BuildConfig.DEBUG llama setSaveDiagnosticsEnabled(true).
El puente rechaza la activación si se compila con NDEBUG. La corrección de lectura
se aplica también al visor; el visor no activa ni acumula estas métricas.

`PdfDocument.saveAsCopy` conserva el mismo synchronized(PdfiumCore.lock).
Cronometra la espera antes de entrar y, manteniendo el lock, copia los diez valores
nativos a un LongArray preasignado del documento y crea una instantánea inmutable
`PdfSaveDiagnostics`. La captura en finally también se intenta al fallar saveAsCopy.
El exportador conserva esa instantánea antes del cierre; cerrar/desactivar el
documento limpia sus referencias Kotlin y destruir DocumentFile libera el estado
nativo. No se obtiene un segundo lock para consultar métricas después del guardado.

El puente reinicia los contadores al entrar en nativeSaveAsCopy. Activa el conteo
de lectura justo alrededor de FPDF_SaveAsCopy y lo desactiva con RAII al salir,
incluso ante excepción. Apertura, inserción, espera del lock y cierre del documento
quedan fuera de ese intervalo. No hay contadores globales ni mezcla entre documentos.
La instrumentación de FileWrite mantiene NewByteArray/SetByteArrayRegion por bloque,
DeleteLocalRef, comprobaciones de excepciones y retorno cero ante fallo; no reutiliza
arrays ni cambia el mecanismo de salida. No se emiten logs por lectura/escritura.

Los campos siguientes se añaden a la misma línea PdfiumExportTiming:

| Campo | Alcance |
| --- | --- |
| saveLockWaitMs | Tiempo antes de adquirir PdfiumCore.lock en saveAsCopy, con el pequeño coste de entrada/instrumentación; no sustituye ni elimina el lock. |
| nativeSaveMs | Solo el intervalo C++ alrededor de FPDF_SaveAsCopy, después de configurar el callback y antes de copiar las métricas. Excluye la espera del lock y el vaciado final de salida. |
| sourceReadCalls | Entradas en getBlock durante FPDF_SaveAsCopy de ese documento. |
| sourceReadBytes | Bytes positivos realmente devueltos por pread64, también si una petición falla después de leer parcialmente. Puede superar inputBytes por relecturas; no mide bytes únicos ni lecturas de almacenamiento físico. |
| sourceReadSmallCalls | Peticiones getBlock de menos de 1024 bytes, no el tamaño de cada reintento. |
| sourceReadMs | Tiempo del callback getBlock completo, incluyendo validación, bucle, syscalls/reintentos y contadores; no es una medida exclusiva del dispositivo de almacenamiento. |
| preadCalls | Syscalls reales pread64, incluidos reintentos EINTR y llamadas que devuelven error o EOF. |
| shortReads | Retornos positivos de pread64 menores que el bloque solicitado en esa syscall. EOF cero se registra como readErrors, no aquí. |
| readErrors | Peticiones getBlock fallidas por EOF anticipado, error definitivo o parámetros/rangos inválidos. EINTR recuperado no se considera error; las lecturas parciales recuperadas tampoco. |
| nativeWriteCallbackMs | Tiempo acumulado del callback C++ completo: comprobaciones, preparación del array, llamada a Kotlin, DeleteLocalRef y comprobación final del resultado/excepciones. |
| jniArrayPrepareMs | Subintervalo de NewByteArray y SetByteArrayRegion, incluida su preparación inmediata y caminos de fallo; excluye llamada Kotlin y limpieza posterior. |
| nativeTimingAvailable | Hay instantánea del guardado nativo; false si no se llegó a guardar o el diagnóstico no estaba activo. En ese caso los campos nuevos numéricos se imprimen como cero. |

`saveCallMs` histórico sigue incluyendo espera del lock, preparación/retorno JNI,
nativeSaveMs y la copia final de métricas. Por ello saveLockWaitMs + nativeSaveMs
no tiene por qué ser exactamente saveCallMs. `nativeWriteCallbackMs` contiene
callbackMs Kotlin y jniArrayPrepareMs; no se suman de nuevo. sourceReadMs y
nativeWriteCallbackMs pertenecen a nativeSaveMs. Parte de destWriteMs ya está dentro
del callback y otra parte puede suceder después al vaciar el búfer. Todos son
intervalos anidados, no costes adicionales al total.

Se conservan los residuos anteriores: saveOtherMs = etapa completa menos callbacks
Kotlin, y saveCallOtherMs = llamada pública menos callbacks Kotlin. Este último
todavía incluye espera del lock, lecturas de origen, serialización, preparación
JNI, captura de métricas y otros costes. No equivale a CPU pura ni se recalcula
restándole indiscriminadamente campos solapados. La corrección y la próxima
medición del usuario se registran en PERF-PDFIUM-002 de BACKLOG, sin atribuir de
antemano una causa al tiempo observado.

El resultado de compilación/empaquetado y las comprobaciones manuales de color,
transparencia, posición, rotación, CropBox, varias páginas, visores externos,
cancelación, almacenamiento y páginas de memoria de 16 KB se registran únicamente
en `PDFIUM-EXPORT-001` de BACKLOG. No se instalan APK ni se ejecutan pruebas
instrumentadas como parte de este incremento.

## 26. Modo PDFium incremental experimental hacia otro archivo

El usuario autoriza explícitamente solicitar FPDF_INCREMENTAL solo en esta copia
`FirmarPDF - PdfiumPrueba`, applicationId `com.zonadev.firmarpdf.pdfiumtest`.
Esta decisión amplía el alcance anterior sin incremental (§25 y los incrementos
históricos); no modifica el proyecto original de FirmarPDF ni LectorDocumentos.
La política de destino permanece: origen de solo lectura y PDF NUEVO en
Documents/FirmarPdf, con cierre, publicación e historial actuales.

`PdfiumSaveMode` es una selección tipada con dos valores:

| Acción | Recorrido | Flag solicitado |
| --- | --- | --- |
| Guardar | PdfExporter/PDFBox existente | document.save normal; sin flags PDFium |
| Guardar con PDFium | PdfiumSignatureExporter, FULL predeterminado | PdfDocument.FPDF_NO_INCREMENTAL, valor 2 |
| Guardar con PDFium incremental | Mismo PdfiumSignatureExporter, INCREMENTAL | PdfDocument.FPDF_INCREMENTAL, valor 1 |

Las constantes se comprueban contra fpdf_save.h y PdfDocument del binding local
v1.0.35. Se transmite una sola constante a saveAsCopy; no se utiliza OR, el valor
3 (FPDF_REMOVE_SECURITY), ni conversión a otro modo ante un fallo. Las dos acciones
PDFium son solo debug, conservan la validación de firmas sin fechas/dibujos y los
controles de progreso/error existentes. El modo pendiente del permiso de Android 9
se conserva de forma tipada con rememberSaveable, para no convertir una solicitud
incremental en FULL al responder al permiso o recrear la Activity. PDFBox conserva
su acción separada. Se mantienen el Job único del ViewModel y el Mutex compartido.

Cada intento abre su propio descriptor `r` y un PdfDocument nuevo desde el mismo
URI original. No se reutiliza el documento del visor ni una instancia exportada
anteriormente. La inserción de PNG completos, matrices, colores, transparencia,
CropBox, rotaciones, generación por página y rechazo de protegidos/firmas digitales
son comunes a FULL e INCREMENTAL. El descriptor permanece vivo hasta terminar
saveAsCopy y cerrar el documento nativo.

PublicPdfDestination reserva un destino nuevo y vacío. El callback reenvía TODOS
los bloques emitidos por PDFium al mismo PdfSaveOutputStream de 64 KiB. No descarta
inputBytes, no separa una cola, no copia previamente el original, no utiliza
transferTo ni append, no reutiliza arrays JNI y no añade caché o compresión.
El objetivo es una salida autónoma que conserve las páginas originales; solicitar
el flag y recibir éxito no comprueba por sí solo ese objetivo ni el formato efectivo.
No se reabre la salida ni se añaden hashes/comparaciones completas durante guardar.
No se rechaza un resultado por ser mayor o menor que el origen: se mantiene la
comprobación de bytes escritos positivos y los errores reales del motor/stream.

Se conservan las lecturas completas/EINTR, sincronización global, excepciones JNI,
cancelación/cierre y descarte de destinos incompletos. Después del cierre se
publica/registra como antes; un fallo Room conserva la salida publicada y avisa.
Si falla INCREMENTAL se devuelve el error y se conserva el borrador, sin ejecutar
silenciosamente otra exportación FULL. El mismo borrador permanece disponible
después de cada éxito para elegir el siguiente motor/modo.

PdfiumExportTiming conserva todos sus campos e intervalos y añade únicamente
`requestedSaveMode=full|incremental` y `saveFlags=2|1`, obtenidos del mismo modo
inmutable utilizado en saveAsCopy. No se registra un supuesto modo efectivo.
nativeSaveMs contiene las lecturas y callbacks dentro de FPDF_SaveAsCopy;
nativeWriteCallbackMs incluye callbackMs Kotlin y jniArrayPrepareMs. Parte de
destWriteMs ya pertenece al callback y otra puede suceder al vaciar el búfer.
Estos campos siguen anidados y no se suman como costes independientes. Se emite
una sola línea debug al finalizar, incluido fallo/cancelación cuando sea posible,
sin nombres, rutas, URI ni contenido privado.

La hipótesis del incremental es evitar parte de la reescritura completa. Producir
otro archivo autónomo sigue teniendo el coste de emitir una salida que conserve
el documento original. Los objetos y bytes emitidos dependen del motor y del PDF;
no se promete que sean solo la imagen o unos KB, ni un tiempo objetivo.
La medición anterior de FULL no identifica por sí sola el coste interno exacto.

Para comparar, abrir el mismo ORIGINAL, colocar las mismas firmas en las mismas
posiciones y exportar mediante Guardar/PDFBox, Guardar con PDFium/FULL y Guardar
con PDFium incremental. Tras cada éxito elegir «Seguir en el visor», sin mover
ni redimensionar las colocaciones y sin abrir un resultado como nuevo origen.
Mantener dispositivo, proveedor/destino y condiciones, separar la primera pasada
de repeticiones y conservar líneas completas de PdfExportTiming/PdfiumExportTiming
con tamaños, requestedSaveMode, flags, success y failure. Los saveMs históricos
de PDFBox y PDFium tienen distinto alcance (§25); comparar totalMs y los
subintervalos documentados, sin deducir velocidad de una sola observación.

El estado de compilación y las comprobaciones pendientes de salida autónoma,
apertura externa, aspecto de firmas, páginas originales, integridad del original,
cancelación/fallos, tiempos y tamaños se registran en PDFIUM-INCREMENTAL-001 de
BACKLOG. Compilar no verifica formato incremental efectivo ni compatibilidad.

## 27. Motor PDFium con corrección de persistencia incremental

El caso comunicado por el usuario produce salida sin firma aunque el motor
devuelva éxito: inputBytes=198855860, outputBytes=198856032,
requestedSaveMode=incremental, saveFlags=1, success=true. La revisión binaria
arm64/x86_64 que él aporta encuentra la lógica antigua de
CPDF_Creator::InitNewObjNumOffsets. Las referencias oficiales e697afe4fa90045e56f7bda475bab18575ce06ea
y a25c77d7de63737089dacc4e38d41ce241575845 muestran la diferencia: antes se omite
todo objeto si is_incremental_ es verdadero; después se omiten objetos anteriores
solo en el modo no incremental. El cambio corrector real es
`2ddec2c6db6a368f166b5dd1039560dc322cb59b` del 10/09/2025, con prueba upstream
IncrementalSaveWithModifications. a25c77d... es una revisión posterior de flags,
no el primer arreglo. Las pruebas upstream se consultan como evidencia de fuente,
no se declaran ejecutadas aquí ni prueban esta combinación Android/JNI.

Se selecciona el precompilado Android sin V8/XFA **PDFium 158.0.8111.0** publicado
por bblanchon/pdfium-binaries, tag fijo chromium/8111, release inmutable ID
417611707 del 19/10/2026, constructor en
`2de99cdab0cd84493538ce141342515b95ebb15c`. Se evita una descarga flotante latest
y una migración de binding. La revisión de fuente resuelta de chromium/8111 es
`a50f1a5953264de698f398fe3a2ef6153677fafa`, coincidente con el último commit de
fuente enumerado por la publicación; su creador mantiene la corrección. Los
headers edit/save y fpdfview original se contrastan con esa revisión oficial.
La atestación publicada fija el constructor, no el checkout transitivo del motor;
se preserva sin afirmar verificación criptográfica Sigstore no ejecutada.

`provenance/chromium-8111/ENGINE.json` distingue repositorios/revisiones de binding,
motor y constructor, base de esa identificación, hashes de archivo tgz y .so por
ABI, hashes de headers y límites de la evidencia. Junto a él quedan la información
de release, atestación/payload, configuración args.gn por ABI, scripts/parches del
constructor y el informe de compatibilidad. Los hashes de archivos descargados
se contrastan con los digests de la publicación inmutable y sus subjects.
Se conservan las licencias/avisos del distribuidor, PDFium y dependencias.

| Comprobación | Decisión y alcance |
| --- | --- |
| ABI | Mantener armeabi-v7a, arm64-v8a, x86 y x86_64, con una única libpdfium.so por ABI. |
| API mínima | args.gn y nota ELF declaran 23; el anfitrión permanece en minSdk 28. Todas las importaciones fuertes se contrastan con stubs Android API 28 por ABI. |
| Runtime | ELF requiere solo libdl.so/libm.so/libc.so, sin libc++_shared.so externo. Motor con runtime C++ interno; puente con c++_static del NDK 26.3.11579264. La interfaz compartida es C pública/handles opacos, no objetos C++. |
| Herramientas | El motor precompilado identifica NDK r30 build 16248370; no se instala ese toolchain ni se compila PDFium. Kotlin 2.2.0, AGP 8.9.2, Gradle 8.11.1, Java/JVM 11, NDK r26d y CMake 3.22.1 del anfitrión/puente se conservan. |
| Alineación | LOAD de 16 KB en arm64-v8a/x86_64 del motor; 4 KB en sus ABI de 32 bits. Puente propio de 16 KB y comprobación de alineación ZIP/ELF del APK; dispositivo de 16 KB sigue siendo otra verificación. |
| Símbolos y headers | Los 80 símbolos PDFium requeridos por el puente están exportados en las cuatro ABI. Headers públicos idénticos entre paquetes y usados para recompilar el puente; auxiliares Android utils conservados. |

Se sustituye únicamente el motor y sus headers públicos/licencias, conservando la
base Kotlin/JNI v1.0.35 y sus extensiones. La comparación de declaraciones usadas
encuentra el cambio de retorno de FPDFPage_InsertObject y el nombre de un parámetro
de FPDF_SaveAsCopy (sin cambio de ABI de esta última). El contrato vigente de
InsertObject exige comprobar FPDF_BOOL y no liberar dos veces la imagen: éxito
transfiere propiedad a la página y fallo libera el objeto. signature_export.inc
se adapta a ambos caminos, con el mismo conteo/identidad después del éxito.

No se cambia selección FULL/INCREMENTAL, callback, flags, lecturas completas/EINTR,
sincronización, descriptor original de solo lectura, búfer 64 KiB, errores,
cancelación, limpieza, publicación ni historial. PDFBox permanece. No se introducen
parches hexadecimales, offsets de estructuras internas, APIs privadas, manejo
manual de cola ni fallback silencioso. El proyecto original y LectorDocumentos
quedan fuera del cambio.

Comprobación mínima fuera del flujo de guardado: abrir un PDF pequeño ORIGINAL,
colocar una firma, exportar mediante Guardar con PDFium incremental y, una vez
terminado, reabrir el archivo NUEVO en un visor externo para confirmar persistencia
de la firma y conservación de páginas/contenido original. Comprobar además
posición, tamaño, transparencia y original intacto. No se incorpora esa reapertura
al botón Guardar ni se usa para modificar el intervalo de métricas. Después repetir
el PDF grande y comparar FULL/INCREMENTAL/PDFBox con el mismo original y borrador;
FULL debe medirse también con este motor nuevo para que la comparación sea controlada.

El fallo anterior y las verificaciones realmente ejecutadas/pendientes se registran
en PDFIUM-ENGINE-001 de BACKLOG. Que compile o saveAsCopy devuelva true no demuestra
la persistencia de la firma ni cierra la comprobación externa.

Referencias de procedencia:

- Antes: https://pdfium.googlesource.com/pdfium/+/e697afe4fa90045e56f7bda475bab18575ce06ea/core/fpdfapi/edit/cpdf_creator.cpp
- Después: https://pdfium.googlesource.com/pdfium/+/a25c77d7de63737089dacc4e38d41ce241575845/core/fpdfapi/edit/cpdf_creator.cpp
- Arreglo: https://pdfium.googlesource.com/pdfium/+/2ddec2c6db6a368f166b5dd1039560dc322cb59b
- Distribución fija: https://github.com/bblanchon/pdfium-binaries/releases/tag/chromium/8111
- Fuente seleccionada: https://pdfium.googlesource.com/pdfium/+/a50f1a5953264de698f398fe3a2ef6153677fafa/core/fpdfapi/edit/cpdf_creator.cpp

## 28. Incremental 8111: revisión añadida frente a actualización mínima

Se distingue el formato incremental (original seguido de otra revisión enlazada
por xref y `/Prev`) de una actualización mínima (solo las definiciones necesarias).
El flag `FPDF_INCREMENTAL=1` no promete esta segunda propiedad. La igualdad entre
longitud añadida y un FULL histórico no demuestra identidad de objetos ni de bytes;
la reproducción y cifras aportadas se registran en PDFIUM-GROWTH-001 de BACKLOG.

La revisión estática usa exclusivamente la fuente resuelta en ENGINE.json,
`a50f1a5953264de698f398fe3a2ef6153677fafa`. El manifiesto
`third_party/pdfiumandroid/provenance/chromium-8111/incremental-growth-source-audit.json`
registra URLs y SHA-256 de los archivos consultados y los límites de la evidencia.
`engine-creator.cpp` es una evidencia de procedencia: CMake compila el puente
pdfiumandroid.cpp con signature_export.inc y enlaza el motor precompilado importado;
editar esa evidencia no altera libpdfium.so.

### Recorrido que carga objetos

| Llamada del exportador/binding | Implementación en la revisión exacta y alcance |
| --- | --- |
| nativeOpenDocument | Crea FPDF_FILEACCESS sobre el descriptor original de solo lectura y llama FPDF_LoadCustomDocument → LoadDocumentImpl → CPDF_Document::LoadDoc/CPDF_Parser. TryInit carga raíz y LoadPages. CountPages usa /Count válido o recorre el árbol si falta/es inválido; el parser puede reconstruir xref en entradas defectuosas. Abrir no implica leer/decodificar todo el PDF. |
| getPageCount, dos consultas | FPDF_GetPageCount → CPDF_Document::GetPageCount devuelve el tamaño de page_list_ ya preparado. No recorre contenido ni reinserta imágenes. No se retira la validación del borrador. |
| getSignatureExportRestriction | FPDF_GetSignatureCount → CollectSignatures consulta Root/AcroForm/Fields y sus diccionarios de campo. No recorre todas las páginas ni sus streams gráficos. Permisos y revisión del security handler consultan el parser. Se conservan ambos controles. |
| openPage, solo índices con colocaciones | FPDF_LoadPage obtiene el diccionario, construye CPDF_Page y ParseContent. La página modificada carga contenido/recursos referenciados, incluidas imágenes o formularios XObject preexistentes; no se abre cada página desde Kotlin. La navegación al índice puede cargar nodos/diccionarios previos del árbol de páginas. |
| getPageBoundingBox/getPageRotation y PdfiumExportGeometry | Consultan bbox/rotación de la página abierta y calculan la matriz. No clonan streams ni abren otras páginas. |
| insertImage | Crea imagen y Bitmap solo de cada colocación de firma, con los PNG completos de SavedSignatureStore. SetBitmap genera sus streams imagen/máscara; InsertObject marca ese objeto gráfico dirty. Conteo e identidad de objetos gráficos de la página no equivalen al inventario de objetos indirectos del documento. No existe un bucle del exportador que vuelva a insertar imágenes originales. |
| generateContent, una vez por página modificada | FPDFPage_GenerateContent → CPDF_PageContentGenerator::GenerateContent. GenerateModifiedStreams selecciona streams dirty y UpdateContentStreams construye CPDF_PageContentManager para añadir el del objeto sin stream previo; su constructor recuenta referencias globalmente. Después UpdateResourcesDict examina recursos compartidos de nuevo. No es evidencia de regeneración indiscriminada de todos los contenidos originales. |
| close de la página | PdfPage.close/FPDF_ClosePage liberan el handle y la vista. No vacían indirect_objs_ del documento. Los objetos cargados pueden seguir incluidos en el guardado después de cerrar la página. |
| saveAsCopy, una vez | Se conserva PdfiumCore.lock y una llamada JNI a FPDF_SaveAsCopy. El modo incremental copia el original desde CPDF_Creator::WriteDoc_Stage1 mediante parser_->WriteToArchive; después serializa la revisión. Esa copia pertenece al motor/formato, no a una copia manual adicional del exportador. |

### Amplificación identificada dentro del motor

El punto más directo es **UpdateContentStreams → CPDF_PageContentManager**: su
constructor inicializa incondicionalmente
`objects_with_multi_refs_(GetObjectsWithMultipleReferences(document_))`, incluso
para añadir el stream nuevo de una firma. Protege arrays y streams de contenido
compartidos, pero obtiene el recuento recorriendo el documento. No depende de
que los recursos de esa página sean indirectos. AddStream conserva el stream
anterior mediante referencia al formar un array de Contents y añade el nuevo;
no copia por ese motivo el payload de la imagen original.

Después, UpdateResourcesDict llama IsPageResourceShared. Si el diccionario de recursos
es indirecto, este llama **GetObjectsWithMultipleReferences(doc)** antes de
comprobar el recurso concreto. Además, el camino de reserva inspecciona los
diccionarios de todas las páginas para detectar recursos inline compartidos,
sin hacer ParseContent de cada una. CloneResourcesDictEntries también puede
llamar GetObjectsWithMultipleReferences cuando encuentra entradas indirectas
ExtGState/Font/XObject, incluso si finalmente no necesita clonarlas.

GetObjectsWithMultipleReferences crea ObjectTreeTraverser: parte del trailer o
raíz, recorre arrays/diccionarios y resuelve referencias normales con GetDirect.
CPDF_Reference::GetDirectInternal llama GetOrParseIndirectObjectInternal, que
conserva el objeto parseado en indirect_objs_. El recorrido de un stream visita
las referencias de su diccionario, no sus píxeles ni sus operadores gráficos.
Puede materializar objetos indirectos alcanzables de páginas ajenas a la firma,
incluidos wrappers de streams de imagen, contenido, fuentes y recursos; esto no
implica decodificar anticipadamente todos sus bytes. No demuestra que entren
objetos huérfanos o todas las entradas xref del archivo.

InitNewObjNumOffsets itera los objetos indirectos cargados del documento. En modo
incremental incluye todos los de número válido sin comprobar cambios individuales;
WriteNewObjs los serializa después. La combinación entre recorrido amplio para
compartición y selección por carga explica una revisión potencialmente grande,
aunque solo se añada una firma. Cuáles y cuántos streams grandes se repiten en
el archivo concreto requiere comparar las dos estructuras, no contarlos como
objetos gráficos ni inferirlo del tamaño total.

Clonar Resources o sus subdiccionarios es una protección de recursos compartidos.
CPDF_Dictionary::Clone usa CloneNonCyclic(false) y CPDF_Reference::CloneNonCyclic
preserva referencias indirectas. Clonar el diccionario no demuestra clonar los
payloads de todas sus imágenes; la nueva serialización de streams cargados es
un mecanismo diferente. Los hashes externos permiten separar esas posibilidades.

Se conserva el recorrido actual: no hay una llamada prescindible demostrada del
binding que evite esa selección interna. La API pública utilizada no ofrece
evicción del inventario indirecto ni filtrado de objetos modificados para guardar.
Omitir GenerateContent perdería las modificaciones; omitir controles de seguridad
o el análisis de compartición no es una corrección válida. No se cambia el motor
ni se modifica su evidencia de fuente. Una futura corrección del motor debe
abordar el recorrido y/o el seguimiento de modificaciones con diagnóstico concreto,
preservando recursos compartidos y persistencia; no basta volver a excluir objetos
originales indiscriminadamente.

### Inspección externa y comparación

`scripts/analyze_pdf_incremental.py` usa pypdf fijado, xref/trailers y un parser de
objetos/streams que admite xref streams, híbridos y ObjStm. Compara prefijo exacto
por bloques, cadena /Prev, números/generaciones nuevos o redefinidos, tipos,
tamaños y SHA-256 de payloads codificados. Opcionalmente compara datos Flate
decodificados con predictores y límite, sin equipararlos a píxeles renderizados.
Ordena los streams que más aportan y agrupa imagen/contenido/fuentes/otros. No
emite contenido privado y libera las cachés después de cada objeto. Los límites,
ámbito de revisiones, errores e instalación/comandos están en
`scripts/README_pdf_diagnostics.md`; no se escanea con regex buscando objetos en
bytes binarios ni se incorporan estas lecturas al botón Guardar.

Para la comparación se usa el mismo motor 8111, el mismo original y borrador
(PNG, páginas, posiciones, tamaño y alfa). Ejecutar FULL e INCREMENTAL como
intentos independientes desde el original, cada uno a un destino nuevo, sin usar
la salida de otro intento como entrada. Registrar los dos resúmenes actuales,
comparar tamaños y analizar original/incremental; reabrir los resultados fuera
del flujo normal para verificar persistencia/aspecto. PDFBox se conserva como
tercera referencia. El FULL histórico no sustituye esa pareja controlada con 8111.
sourceReadMs incluye también la lectura del prefijo que copia el motor durante
save; nativeWriteCallbackMs incluye callback Kotlin y parte de destWriteMs. Ambos
pertenecen a nativeSaveMs: no se suman como costes adicionales independientes.

Fuentes oficiales, todas bajo la revisión exacta indicada:

- Gestor de contenido, constructor: https://pdfium.googlesource.com/pdfium/+/a50f1a5953264de698f398fe3a2ef6153677fafa/core/fpdfapi/edit/cpdf_pagecontentmanager.cpp
- Generación/recursos: https://pdfium.googlesource.com/pdfium/+/a50f1a5953264de698f398fe3a2ef6153677fafa/core/fpdfapi/edit/cpdf_pagecontentgenerator.cpp
- Recorrido de referencias: https://pdfium.googlesource.com/pdfium/+/a50f1a5953264de698f398fe3a2ef6153677fafa/core/fpdfapi/parser/object_tree_traversal_util.cpp
- Resolución/caché: https://pdfium.googlesource.com/pdfium/+/a50f1a5953264de698f398fe3a2ef6153677fafa/core/fpdfapi/parser/cpdf_indirect_object_holder.cpp
- Creador incremental: https://pdfium.googlesource.com/pdfium/+/a50f1a5953264de698f398fe3a2ef6153677fafa/core/fpdfapi/edit/cpdf_creator.cpp

## 29. Experimento de escritorio: EmbedPDF y guardado selectivo

`experiments/embedpdf-selective-save` aísla la evaluación nativa Windows x64.
No participa en Gradle, no cambia app/ ni third_party/pdfiumandroid/, y no implica
una decisión de migrar el binding Android. El usuario autoriza explícitamente
un PNG sintético transparente con trazos de firma al no disponer de una firma
personal; ambos modos usan sus mismos píxeles, página y matriz, con el PDF real
original de 198855860 bytes. El antecedente aportado del incremental Android
8111 redefine 2978 objetos y conserva el original como prefijo: es contexto,
no una comparación de tiempos controlada entre Windows y teléfono.

Se fija la publicación oficial de embedpdf/runtime
`runtime-5f7608ba4a634c94cd718067d6b678a45ba89088`, revisión
`5f7608ba4a634c94cd718067d6b678a45ba89088` (upstream
`7b3f6f93625e6df97a7d9e677137a558df8648c4`). El paquete win32-x64 y su DLL
están fijados por SHA-256, con headers, licencias, metadata, tag y configuración
en provenance/ENGINE.json del experimento. Los headers consultados se contrastan
con la misma revisión. La publicación no está marcada inmutable: el digest fijo
evita aceptar sustituciones; no se afirma una atestación criptográfica no verificada.
El PE AMD64 necesita solo KERNEL32/ADVAPI32/GDI32/USER32 según sus importaciones.
No se compila ni modifica el motor. El adaptador usa ctypes: exports stdcall,
callbacks C y tipos/layout Windows x64 comprobados, sin estructuras internas.

### Recorrido que se compara

Cada intento abre su propio descriptor de solo lectura, carga base mediante
EPDF_LoadBaseDocument, abre una capa vacía EPDFLayer_OpenLayer, inicia transacción,
inserta un Bitmap BGRA con alfa recta, genera contenido y confirma la transacción.
Se conserva el descriptor/callback hasta liberar el documento base; primero se
cierra la capa. Como el paquete habilita embedpdf_thread_local_globals, todos los
handles y operaciones pertenecen al mismo hilo entre EPDF_InitThread y ShutdownThread.
El callback posicional completa lecturas en un descriptor dedicado con seek/readinto
bajo lock; nunca se precarga el PDF completo en memoria desde el adaptador.

FULL llama EPDF_SaveAsCopyEx con FPDF_NO_INCREMENTAL=2 sobre esa capa editada y
produce un PDF independiente. Selectivo llama EPDFLayer_SaveDelta sobre otra
base/capa independiente. SaveDeltaImpl activa internamente kIncremental y
kIncrementalAppendOnly: escribe una cola PDF con offsets absolutos desde la
longitud de la base, sin copiar el prefijo. Su contrato permite materializar
original + cola. El experimento copia todos los bytes originales a un archivo
nuevo y añade todos los callbacks de delta, usando búfer de salida de 64 KiB.
No se descarta ningún byte del callback, no se guarda un artefacto de capa como
PDF, y no se reutiliza FULL como entrada del selectivo ni existe fallback.
Esta concatenación responde al contrato explícito de SaveDelta y a la autorización
del experimento; no altera la política de los exportadores Android existentes.

La selectividad real procede de CPDF_Creator::PrepareIncrementalObjects de esta
variante: compara candidatos de la vista efectiva con gemelos originales/cargados
mediante CPDF_SameEffectiveValue (incluida generación), excluye iguales y exige
alcanzabilidad desde raíces guardadas. CPDF_SaveObjectReader usa caché acotada
para consultar originales sin convertirlos en modificaciones de la capa. No se
atribuye ese comportamiento a cambiar solo FPDF_INCREMENTAL en el motor 8111.

El análisis de esta salida real selectiva identifica solo el objeto de página
`5 0` (primera página) como redefinido, cuatro objetos nuevos (imagen RGB y máscara 480×150, un diccionario
y contenido de página), sin streams originales reescritos. La cola mide 16573 B;
sus payloads codificados suman 15202 B y el resto son diccionarios/sintaxis/xref.
El resultado es un PDF convencional de 198872433 B cuyo prefijo exacto es el
original y cuyo /Prev enlaza su xref. FULL, de 191733234 B, es una reescritura sin
/Prev: su conteo de 2978 números originales serializados no describe otra revisión
incremental. El tamaño FULL menor no contradice que el selectivo escriba menos
datos nuevos, porque este último conserva todos los bytes originales.

### Medición y alcance de la evidencia

Se acumulan nanosegundos monotónicos por apertura, bitmap, capa/transacción,
inserción/contenido, copia del original, llamada nativa save, vaciado/cierre y
liberación de handles. save incluye callbacks/escrituras durante la llamada;
writeStage contiene copia, save, vaciado/cierre y preparación de salida. TotalToPdf
incluye materialización completa y cierre de salida; total añade cierre de recursos.
No se suman intervalos anidados ni se presenta el tiempo de cola como tiempo
de PDF terminado. No hay fsync/FlushFileBuffers ni promesa de persistencia física.
Las medianas de tres repeticiones se calculan por columna y no son aditivas.

Fuera del intervalo medido se abren los resultados con el cargador PDF ordinario
sin capas y se renderiza la página firmada. Se contrasta contribución de color/alfa
con composición esperada del PNG, preservación fuera de la firma e igualdad
FULL/selectivo; se comprueban conteo de páginas, hashes de entradas intactas y
estructura con pypdf. Son comprobaciones distintas del código de éxito de save.
Se renderiza solo la página firmada, con el mismo motor; no todas las páginas
ni con un segundo renderer. Las pruebas no cubren errores/cancelación, cifrado,
certificación, >4 GiB ni comportamiento/rendimiento Android.

La primera pareja válida habilita tres repeticiones por modo. Los datos individuales,
medianas, tamaños y evidencia técnica están en measurements.json del experimento;
los PDFs, renders e informes grandes quedan localmente en _local/results, ignorados
por Git. Resultado y verificaciones pendientes: EMBEDPDF-DESKTOP-001 en BACKLOG.
No se purgan cachés del SO; los tiempos Windows no prueban una mejora en Android.

### Incremento Android mínimo propuesto, sin implementarlo

1. Obtener primero un runtime EmbedPDF Android verificable para las cuatro ABI,
   minSdk 28 y alineación 16 KiB aplicable. Esta publicación consultada no incluye
   Android; no se supone compatible por funcionar su DLL Windows ni se inicia
   automáticamente una compilación del motor.
2. Añadir un puente experimental aislado para handles base/capa, transacciones,
   bitmap/generación y SaveDelta, conservando las rutas actuales. El JNI 8111
   actual no expone esas APIs y necesita adaptación. Verificar también convivencia
   de bibliotecas/símbolos y tipos/layout Android, sin trasladar el unsigned long
   Windows de 32 bits como si fuera igual en todas las ABI.
3. Ejecutar todas las operaciones y cierres de cada base/capa en un worker dedicado:
   un lock global no basta para satisfacer el confinamiento de hilo de este runtime.
   Mantener callbacks/descriptores vivos, comprobar propiedad de imágenes y preservar
   errores, cancelación, limpieza, original intacto y destino nuevo.
4. Materializar original + delta según contrato, medir copia y cola por separado y
   total completo antes de publicación/historial, manteniendo búfer de 64 KiB.
   Verificar primero PDF pequeño y después este original mediante reapertura/render
   y análisis externos. No añadir la reapertura al botón Guardar ni retirar
   PDFBox/FULL/INCREMENTAL durante la evaluación.

Referencias de la misma revisión:

- Capa/transacciones: https://github.com/embedpdf/runtime/blob/5f7608ba4a634c94cd718067d6b678a45ba89088/public/epdf_layer.h
- Guardado: https://github.com/embedpdf/runtime/blob/5f7608ba4a634c94cd718067d6b678a45ba89088/public/epdf_save.h
- SaveDeltaImpl: https://github.com/embedpdf/runtime/blob/5f7608ba4a634c94cd718067d6b678a45ba89088/fpdfsdk/epdf_layer.cpp
- Selección: https://github.com/embedpdf/runtime/blob/5f7608ba4a634c94cd718067d6b678a45ba89088/core/fpdfapi/edit/cpdf_creator.cpp

## 30. Exportador Android selectivo EmbedPDF, separado del visor

Por autorización posterior se adapta el recorrido de §29 a Android ARM64, con
la misma revisión `5f7608ba4a634c94cd718067d6b678a45ba89088`. Las cuatro
bibliotecas del visor y los recorridos PDFBox/PDFium FULL/INCREMENTAL se conservan.
Las comprobaciones y su estado real se registran en `EMBEDPDF-ANDROID-001` del
BACKLOG; la evidencia Windows no demuestra funcionamiento ni rendimiento Android.

### Construcción y aislamiento

Se usa el runner Ubuntu 24.04 de GitHub Actions en el repositorio experimental
autorizado, sin instalar WSL/Docker. La receta fija runtime, depot_tools y DEPS,
con Ninja -j2, Android API 28, archivo completo no-thin, sin V8/XFA/renderer Skia,
y `embedpdf_thread_local_globals=true`. El fork requiere descargar Skia de DEPS
para redaction_pathops incluso con renderer desactivado. No se parchea el algoritmo.
El parche GN de la receta corrige el ámbito de configs del invocador skia_opts
y condiciona el objetivo del renderer completo a pdf_use_skia para evitar su
dependencia Expat ausente del DEPS; conserva el objetivo de geometría y las
opciones de optimización que ya aplica su plantilla. El diff acompaña al paquete.
La configuración efectiva, revisiones, headers, licencias, compilador y hashes
acompañan al artefacto. El toolchain NDK fijado por DEPS para el motor se distingue
del NDK r26d que conserva el anfitrión Android. El motor también entrega los
builtins Android de Clang fijados en DEPS: libyuv SME requiere
`__arm_get_current_vg` y `__arm_tpidr2_save`, ausentes en compiler-rt de r26d.
Se enlazan estáticamente y se ocultan en el mismo puente; no se inventan stubs
ni se desactivan optimizaciones para ocultar el error. El manifiesto registra
URL/generación/hash del paquete y licencia LLVM correspondiente a su etiqueta.
Receta: `tools/embedpdf-android/README.md`.

El CMake del binding existente añade `libembedpdf_signature.so` exclusivamente en
arm64-v8a cuando está presente el archivo estático importado. Enlaza motor y runtime
C++ de forma privada, con version script, `--exclude-libs=ALL` y `-Bsymbolic`.
Solo se exporta la entrada JNI; no debe importar/exportar símbolos FPDF/EPDF ni
depender del PDFium del visor. No se intercambian documentos, páginas, bitmaps ni
punteros entre motores. La auditoría estática exige estas propiedades, imports
resolubles en API 28, ausencia de TEXTREL y alineación ELF/ZIP de 16 KiB.
El único marcador débil fuera de API 28 admitido tras revisión es memfd_create
de compiler-rt: el resolver FMV solo comprueba su presencia como señal de API 30
y retorna si falta. Se contrastaron el código fijado y el resolver/GOT del
binario; no se exige esa API para cargar el puente en Android 9.

### Ciclo de vida y salida

Un ejecutor de un único hilo físico ejecuta una operación JNI síncrona completa:
InitThread → base → capa vacía → begin → firmas/contenido por página → commit →
copia del original → SaveDelta → cierre → ShutdownThread. Las cargas suspendidas
de PNG completos desde SavedSignatureStore ocurren antes de crear handles.
La geometría usa PdfiumExportGeometry y la conversión RGBA/BGRA existente, con
stride y alfa recta. Fechas/dibujos se rechazan antes de crear destino.

El descriptor de origen permanece vivo hasta después del cierre nativo. La lectura
posicional completa peticiones mediante pread64, reintenta EINTR y detecta EOF y
rangos inválidos. No altera la posición compartida ni precarga el documento completo.
La propiedad de FPDFPage_InsertObject se respeta también cuando devuelve fallo.

Solo después de commit se crea PublicPdfDestination. Se copian los bytes originales
exactos y se añade íntegramente SaveDelta al MISMO PdfSaveOutputStream de 64 KiB.
La cola contiene offsets relativos a la longitud de la base; no constituye un PDF
independiente. No se usa SaveLayerArtifact, descarte de callbacks, fallback ni
reapertura del resultado en Guardar. Antes de publicar se exigen cierre correcto,
copiedOriginalBytes=inputBytes, deltaBytes>0 y outputBytes=inputBytes+deltaBytes.
Esta materialización siempre crea otro PDF; jamás modifica el original.

Las excepciones JNI se conservan mientras se liberan recursos y se relanzan tras
ShutdownThread. Se destruye el objeto pendiente, se cierra la página, se aborta
la transacción incompleta y se cierra capa antes de liberar base. La cancelación
se comprueba entre lecturas/escrituras y filas de bitmap; no cierra handles ni
descriptores desde otro hilo. No promete interrupción inmediata de llamadas sin
puntos de comprobación. El destino no publicado se elimina al fallar/cancelar;
una salida ya publicada no se borra. Se comparte el mutex con los otros exportadores.

AppContainer y ViewModel conectan el botón debug `Guardar selectivo (EmbedPDF)` a
permisos, progreso y resultados existentes. Se oculta sin artefacto o fuera de un
proceso ARM64. La biblioteca solo se carga al usar el exportador, nunca al iniciar.

### Diagnóstico

Una línea debug `EmbedPdfExportTiming` acumula nanosegundos monotónicos y los
convierte al final a ms, incluidos fallos/cancelaciones que alcanzan el worker.
openMs mide metadatos/descriptor e InitThread/apertura de base, sin afirmar lectura
completa. imagesMs incluye PNG y preparación nativa BGRA/SetBitmap. layerTransactionMs
incluye apertura/controles/begin/commit; insertContentMs agrupa página, geometría,
inserción y generación, excluyendo preparación de imágenes. copyOriginalMs incluye
lecturas y callbacks de copia; deltaSaveMs incluye SaveDelta y sus callbacks.
flushCloseMs mide el vaciado restante/cierre, sin fsync. nativeCloseMs mide limpieza
de handles/ShutdownThread; publishMs/historyMs miden publicación/registro.
totalMs incluye todo el recorrido, espera del worker, carga inicial de biblioteca,
destino y limpieza. No se suman intervalos anidados ni se presenta deltaSaveMs como
el tiempo del PDF completo. Los cuatro contadores de bytes se contrastan antes
de publicar; en fallos pueden diferir por bytes aún en el búfer. El resumen solo
incluye categorías técnicas, nunca nombres, rutas, URI ni contenido.

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
