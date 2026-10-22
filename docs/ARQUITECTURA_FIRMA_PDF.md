# Firma PDF — Arquitectura v0.1

Estado: base de implementación preparada el 21 de octubre de 2026: navegación mínima, historial Room observable e integración Firebase con recopilación desactivada. El flujo de firma sigue pendiente. Las secciones de flujo describen el diseño objetivo; la sección 10 distingue el código actual. Este documento es la referencia para decidir cambios de arquitectura; se actualiza cuando el código real difiera.

## 1. Objetivo y límites

Abrir un PDF, dibujar o reutilizar una firma manuscrita, colocarla en una o varias páginas, guardar el resultado y compartirlo. El trabajo con el PDF funciona sin conexión. La versión básica ofrece firmas y exportaciones ilimitadas, una firma reutilizable guardada, sin anuncios, suscripciones ni pagos en el MVP. Una posible compra única futura añadiría comodidades sin bloquear la salida básica. No habrá cuentas, OCR, Office, compresión, nube propia ni firma criptográfica.

## 2. Base técnica ya elegida

- `namespace` y `applicationId`: `com.zonadev.firmarpdf`; `minSdk 28`, `compileSdk 36`, `targetSdk 36`.
- Kotlin `2.0.21`, AGP `8.9.2`, Java/JVM `11`, Compose BOM `2024.09.00`. Mantener lo que ya compila; no actualizar todo por imitar LectorDocumentos, que usa Kotlin `2.2.0`.
- PDFium `io.legere:pdfiumandroid:1.0.35` para visualizar; PDFBox Android `2.0.27.0` para producir la firma gráfica. Inicializar `PDFBoxResourceLoader` con el contexto de aplicación antes de usar PDFBox.
- Base incorporada: Lifecycle Compose `2.9.4`, Navigation Compose `2.8.0`, Room `2.8.2` + KSP `2.0.21-1.0.28` con KSP2, Firebase BoM `34.4.0`, Google Services `4.4.4` y plugin Crashlytics `3.0.6`. PDFium y PDFBox se centralizan en el catálogo sin cambiar sus versiones. DataStore queda pendiente del consentimiento persistente; no se añade una dependencia sin uso. Evitar Paging, Hilt, Koin y más módulos hasta que un problema concreto los justifique.
- Confirmar compatibilidad de todo el paquete nativo con páginas de 16 KB en el APK/AAB y con un emulador de 16 KB. No equivale a mirar únicamente la dependencia de PDFium.

## 3. Responsabilidades y dependencias

```text
ui/home, ui/editor, ui/signature, ui/components
           ↓
HomeViewModel / EditorViewModel
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
- `EditorUiState`: documento, página/zoom, firma cargada, colocaciones, progreso, error y resultado. Una pulsación doble en Guardar no inicia dos exportaciones. No cargar bytes PDF en argumentos de navegación.
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
- `navigation/FirmaPdfApp` conecta Inicio, Editor y Crear firma. Las pantallas de preparación usan recursos en español y no ofrecen guardar/firmar como acciones operativas. No hay visor, dibujo, selección SAF, exportación, resultado ni ajustes todavía. Se conservan los archivos `ui.theme` generados.
- Home observa `StateFlow` de `HomeViewModel` con recolección vinculada al ciclo de vida; muestra carga, vacío, lista o error recuperable. El repositorio expone el `Flow` de Room, sin datos de muestra. Cada fila contiene solo `id`, `resultUri`, `displayName`, `signedAt` (Unix en milisegundos) y `resultKind`; Room convierte el enum a texto. Índice único por URI y orden `signedAt DESC, id DESC`. La inserción rechaza duplicados sin eliminar filas; la futura actualización de original necesitará una operación explícita que conserve el ID y cambie su fecha, antes de activar esa función.
- Esquema v1 exportado por KSP a `app/schemas/`, destinado al control de versiones. No se usa migración destructiva. La firma reutilizable futura será un PNG privado; no hay tabla ni repositorio ficticio para esa imagen, ni capas PDF vacías.
- La prueba inicial con KSP1 `2.0.21-1.0.28` falla porque PDFium `1.0.35` declara metadatos Kotlin `2.2.0`. KSP2 genera Room, pero `compileDebugKotlin` confirma la misma incompatibilidad del motor. Se conserva KSP2 y PDFium se declara `runtimeOnly`: el motor sigue empaquetado, sin exponer sus metadatos al compilador mientras no hay llamadas al visor. No se cambia Kotlin, AGP, Compose o PDFium ni se omiten comprobaciones del compilador. `BUILD-001` exige resolver acceso a la API del visor antes de implementarlo; esta preparación no demuestra compatibilidad del visor con Kotlin 2.0.21.
- Se conserva el `app/google-services.json` existente, cuyo cliente coincide con `com.zonadev.firmarpdf`. Se integran Analytics/Crashlytics mediante BoM, módulos principales (sin KTX) y plugins oficiales. La recopilación global y de ambos productos está desactivada por metadatos desde el inicio. No hay eventos propios ni llamadas que habiliten envío. Crashlytics puede conservar informes locales aunque el envío esté desactivado; no prometer que Firebase nunca inicializa ni que no existe ninguna comunicación de red. El procesamiento PDF futuro no dependerá de estos SDK.
- La pantalla de consentimiento, persistencia, revocación y comprobación de un informe voluntario en consola siguen en `MET-001`. No se incorpora DataStore todavía. No registrar nombres, URI ni contenido del PDF/firma en telemetría. Se desactiva la recopilación del identificador publicitario y se eliminan del Manifest combinado AD_ID y permisos de AdServices.
- Guardado previsto: archivo nuevo público en `Documents/FirmarPdf/` para adjuntos externos. Para Android 9 se elige la futura escritura pública con `WRITE_EXTERNAL_STORAGE` limitado a `maxSdkVersion=28`; se declara ahora y solo se solicitará al implementar guardado. Android 10+ usará APIs de almacenamiento apropiadas y deberá verificar publicación y ruta. No hay permiso general de lectura, gestión de almacenamiento, medios ni notificaciones; tampoco VIEW/SEND ni FileProvider.
- `allowBackup=false`: no se restauran datos privados de la app. `backup_rules.xml` excluye todos los dominios para Android 9–11; `data_extraction_rules.xml` los excluye tanto de nube como de transferencia entre dispositivos en Android 12+, incluido almacenamiento protegido del dispositivo. Esto cubre firmas PNG futuras, bases Room, preferencias y archivos privados; caché y `noBackupFilesDir` ya quedan fuera por el sistema. Una reinstalación pierde firma, historial y preferencias. Los PDF públicos futuros permanecerán en Documents y estas reglas no gestionan las copias personales que el usuario haga fuera de la app.
- Manifest combinado: además de INTERNET y escritura solo hasta API 28, las dependencias aportan ACCESS_NETWORK_STATE, WAKE_LOCK y BIND_GET_INSTALL_REFERRER_SERVICE. AndroidX aporta un permiso propio de protección `signature` para receptores internos. No requieren solicitudes de permisos peligrosos en esta tarea.
- La carpeta recibida no contiene `.git`; no se inicializa ni se inventa historial Git. Los esquemas y documentos quedan listos para añadir al repositorio cuando se configure.

### Verificación de esta preparación

Ejecutado en PowerShell con el wrapper existente:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Resultado: `BUILD SUCCESSFUL`. APK en `app/build/outputs/apk/debug/app-debug.apk`. Se ejecutó la prueba unitaria existente de la plantilla (1, sin fallos); se compilaron las pruebas instrumentadas de Room sobre base aislada en memoria (vacío, orden/lectura del enum y rechazo de URI repetido). Las pruebas instrumentadas no se ejecutaron ni se instaló esta compilación en el Huawei. Navegación, reinicio y comportamiento de Firebase en dispositivo siguen necesitando comprobación manual.

Lint: 0 errores y 53 avisos, incluidos avisos repetidos de versiones más nuevas, recursos originales sin uso/etiqueta redundante y `TrustAllX509TrustManager` de Bouncy Castle transitivo de PDFBox (`SEC-001`). No se cambian versiones por esos avisos ni se ocultan verificaciones. Se revisó el Manifest combinado, el esquema generado y el diff contra la copia temporal de los archivos iniciales; no quedan Greeting, Hello Android, textos nuevos de interfaz en Kotlin, recursos de texto nuevos sin uso ni declaraciones duplicadas de PDFium/PDFBox.

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
