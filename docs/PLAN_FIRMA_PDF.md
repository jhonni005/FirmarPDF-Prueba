# Plan inicial de Firma PDF — Firmar documentos

Fecha: 21 de octubre de 2026.
Estado: decisiones de producto y arquitectura inicial acordadas; el proyecto Android base `com.zonadev.firmarpdf` ya compila y se ejecuta en Huawei. Todavía no se ha implementado ni medido el flujo de firma en la app nueva. Véase `ARQUITECTURA_FIRMA_PDF.md`.

## 1. Producto y decisión recomendada

App Android enfocada en abrir un PDF, colocar una firma manuscrita y guardar o compartir el resultado. El procesamiento del documento será local y funcionará sin conexión. Reutilizar los componentes de PDF y firma de LectorDocumentos que puedan separarse con un coste razonable.

La primera beta sirve para verificar el recorrido completo sin anuncios ni compra obligatoria. La exportación básica debe mantenerse gratuita, sin marca de agua ni cuota oculta. Después se comprobará por separado si una compra única por comodidad resulta viable. Una beta gratuita por sí sola no valida ingresos.

Usuario inicial: persona que recibe un PDF ya preparado por WhatsApp, correo o archivos, necesita firmarlo o añadir nombre/fecha y devolverlo. Es una hipótesis de segmento que debe contrastarse con usuarios; no se presupone un uso diario.

Propuesta de valor: completar el trámite con pocos pasos, control local del documento y condiciones claras desde el principio. La gratuidad y el funcionamiento local ya existen en competidores; la ventaja debe demostrarse también en facilidad y fiabilidad.

## 2. Evidencia revisada y límites

Se revisaron las fichas públicas accesibles de las seis apps indicadas por Jhonni, las reseñas que esas páginas mostraron, el texto del Word adjunto y sus ocho capturas. No se instalaron las apps durante esta investigación. Los precios y los recorridos de compra observados en dispositivos proceden de las pruebas de Jhonni. Las fichas y reseñas visibles son una muestra, varían por región y pueden reflejar distintas versiones; no permiten calcular ingresos, conversiones ni la proporción total de usuarios afectados.

| App | Evidencia pública | Prueba de Jhonni e interpretación |
| --- | --- | --- |
| Artmvstd, com.artmvstd.signDocuments | Su ficha reserva exportaciones ilimitadas y varias firmas para Pro. No se obtuvo una muestra útil de reseñas en la página accesible. | Paywall al guardar/compartir; S/124.99 único o prueba de tres días seguida de S/13.99 por semana. Precios no comprobados independientemente. |
| pdfFiller, com.pdffiller | Suite amplia con edición, formularios y flujos para varias personas. Una reseña del 15/06/2025 critica pagar para guardar; el desarrollador responde que ofrece una prueba para nuevos usuarios. En septiembre/octubre de 2026 aparecen quejas por exportación y renovación. | Planes anuales observados: S/339, S/424 y S/640. No tomar su amplitud funcional como alcance del MVP. |
| Signature Master, com.fumaomao.signature | Reseñas del 08/07/2026 y 27/07/2026 mencionan problemas para guardar/acceder/compartir; no demuestran por sí mismas que todos esos casos sean paywalls. | Paywall de salida; S/169.99 único y oferta observada de S/0.30 por tres días seguida de unos S/67 semanales. |
| Signature On Docs & Photos, Battery Stats Saver | Reseña del 09/07/2026 denuncia anuncios tras cada toque y dificultad para localizar el resultado. Otra de enero de 2025 describe firma desplazada. La ficha anuncia firma gratuita. | Guardar/compartir disponible, recorrido tedioso; oferta de tres días seguida de unos S/63 por semana. Problemas de permisos en Huawei Android 10 y visibilidad del archivo reportados por Jhonni, sin diagnóstico confirmado. |
| PDF Sign & Editor, Diferenciart, sign.pdf.document | Competidor directo establecido: la ficha muestra 1M+ descargas y anuncia procesamiento local. Reseña del 16/07/2026 menciona anuncios a pantalla completa; otras de junio/agosto de 2026 describen problemas de trazo y cierre del cuadro de firma. | Exportación gratuita con intersticiales frecuentes. Nota de pago único 29.99 con 59.99 tachado; moneda no especificada en esa nota. Las capturas muestran una suite con editor, escáner y Drive. |
| eSign, EclixTech, com.esign.pdf.sign.documents.files | Ficha enfocada en firma, texto y procesamiento local. Las reseñas visibles obtenidas fueron breves y poco informativas. | Tres PDF gratis, luego paywall; captura con S/33.99 mensual y S/95.99 anual. Es una prueba limitada según esta observación, no exportación gratuita ilimitada. |

El hecho de que el contador de EclixTech persista al reinstalar no demuestra almacenamiento en servidor. También habría que descartar restauración de datos u otros mecanismos.

Adobe documenta que Acrobat móvil permite rellenar, firmar y compartir sin suscripción. Esto refuerza que «firmar gratis» no basta como diferenciación.

## 3. Alcance por etapa

### Beta técnica

- Abrir PDF local con el selector del sistema y recibirlo desde otra app.
- Visualizar varias páginas con zoom.
- Dibujar una firma, borrar/deshacer trazos y conservar varias firmas reutilizables en almacenamiento privado.
- Insertar una o varias colocaciones de esa firma en las páginas necesarias; mover, redimensionar y eliminar antes de exportar.
- Firmar y exportar PDF ilimitados sin paywall. Para documentos entrantes desde Gmail/WhatsApp y fuentes de solo lectura, guardar un archivo nuevo en `Documents/FirmarPdf/` y permitir compartirlo.
- Mostrar en Home una lista de PDF firmados; Room conserva URI y metadatos de resultado, no los bytes del PDF. El resultado debe poder reabrirse tras reiniciar la app.
- Para PDF elegidos con SAF, considerar actualización del original solo después de demostrar guardado recuperable ante errores; si no, generar resultado nuevo. No usar `saveIncremental` ni un diálogo para elegir modo.
- Mostrar errores recuperables y una confirmación que permita abrir el resultado.
- Flujo completo con archivos locales en modo avión, sin anuncios, cuenta ni compra.
- Crashlytics y eventos mínimos, con recopilación opcional y separada del procesamiento local.

### Primera versión pública

Todo lo anterior, probado con usuarios y documentos reales. Texto básico, fecha y marca de verificación quedan como ampliación condicionada a una necesidad observada; no son un requisito para cerrar la beta de firma.

La publicación depende de verificar el resultado exportado y el recorrido con usuarios. Si los usuarios necesitan texto o fecha para completar el trámite, priorizarlos en una versión posterior según la frecuencia observada.

### Fuera de la primera versión

Conversión Word/Excel, OCR/escáner, edición de contenido original, compresión, combinación de PDF, sincronización propia, cuentas, solicitud de firmas a terceros, certificados digitales, IA y exploración de todos los archivos del teléfono.

La función inicial añade una representación de firma manuscrita al PDF. No atribuirle capacidades de firma criptográfica con certificado ni prometer aceptación universal. Detectar y rechazar la edición de documentos ya firmados con certificado en el MVP.

## 4. Flujo y pantallas

1. **Inicio:** acción principal «Firmar un PDF», historial Room de resultados firmados, acceso discreto a firma guardada y ajustes. El usuario que comparte un archivo desde otra app entra directamente al editor si el documento es válido.
2. **Editor:** documento como protagonista, controles de página/zoom y herramientas de firma. Acción «Guardar» sin diálogo de modo; informar tras terminar si se actualizó el original o dónde se creó el archivo nuevo.
3. **Crear firma:** pantalla vertical con colores, grosor, lienzo, borrar/deshacer y guardado permanente de cada firma. No existe «Usar solo esta vez».
4. **Resultado:** confirmar solo cuando termine la escritura; abrir, compartir o volver a firmar. Evitar obligar a guardar dos veces para compartir.

Estados que hay que dibujar antes de programar: carga, PDF vacío o ilegible, contraseña no admitida o incorrecta, permiso perdido, cancelación del selector, exportación en curso, falta de espacio, éxito y error con reintento.

No se necesita una navegación inferior con seis secciones. El historial pequeño en Home sí es parte del MVP; no se convierte en un gestor completo de archivos.

## 5. Reutilización y arquitectura propuesta

El documento de arquitectura de LectorDocumentos describe PDFium, dibujo y geometría de firma, biblioteca local y un exportador con PDFBox. Son candidatos a reutilizar, no una garantía de extracción inmediata: falta inspeccionar sus dependencias reales.

Primer trabajo técnico en el proyecto ya creado: lograr que un PDF de prueba entre, reciba una firma y salga como archivo nuevo legible. Medir memoria y tiempo de exportación además del renderizado; que PDFium abra un archivo grande rápidamente no demuestra que PDFBox lo exporte igual de rápido. Comprobar después, por separado, la posibilidad de actualizar un original de forma segura.

| Responsabilidad | Propuesta inicial |
| --- | --- |
| Navegación | Solo destinos, argumentos y conexión de pantallas. |
| Estado de edición | ViewModel con documento resoluble, colocaciones de firma y estado de exportación. |
| Firma guardada | Repositorio local pequeño con eliminación explícita. |
| Visualización | Controlador del motor PDF con ciclo de vida definido. |
| Exportación | Coordinador independiente: destino nuevo para adjuntos/solo lectura; actualización del original únicamente después de una prueba segura. |
| Historial | Repositorio Room de URI y metadatos del resultado, observable desde Home. |
| Acceso a documentos | URI y permisos del selector del sistema, sin indexación global en esta primera versión. |
| Telemetría | Registro de eventos y fallos sin contenido de documentos, aislado del flujo de firma. |

Mantener inicialmente un módulo si simplifica el trabajo. Evitar trasladar AppRoot, los visores Office, toda la base de metadatos o los gestores de anuncios/recompensas. Si conviene compartir un módulo con LectorDocumentos se decidirá después de inspeccionar el acoplamiento; no condicionar la beta a una refactorización del producto existente.

Para abrir, usar `ACTION_OPEN_DOCUMENT` y aceptar `ACTION_VIEW`/`ACTION_SEND` con permisos temporales. SAF no exige permiso general para el PDF seleccionado. Para resultados nuevos, comprobar el guardado público en `Documents/FirmarPdf/` con las APIs adecuadas y verificar `IS_PENDING = 0` cuando corresponda. Android 9 requiere una ruta de escritura pública específica. Compartir el URI final con permiso de lectura para el destinatario. Stack actual: Kotlin 2.2.0, AGP 8.9.2, minSdk 28, compile/targetSdk 36, PDFium 1.0.35, PDFBox Android 2.0.27.0; consultar el documento de arquitectura para límites y pruebas.

El fallo observado en Battery Stats Saver no está diagnosticado. IS_PENDING, MIME, permisos de URI, indexación y compatibilidad del PDF son hipótesis distintas.

## 6. Monetización propuesta y validación

### Etapa A — demostrar utilidad

Beta y lanzamiento inicial acotado sin anuncios ni paywall. Objetivo: identificar problemas de comprensión, colocación y exportación sin añadir otra fuente de abandono.

Punto de revisión orientativo: tras unas 100–200 sesiones reales de firma de usuarios externos, con una mezcla de dispositivos y documentos. No es un umbral estadístico ni prueba de demanda o rentabilidad. Revisar antes si aparece un fallo importante.

### Etapa B — comprobar ingresos

Preferencia inicial: pago único opcional por comodidad para usuarios frecuentes. Candidatos a investigar: sellos guardados, conjuntos de nombre/fecha/cargo reutilizables y colocar una firma en varias páginas de una vez con vista previa. Varias firmas manuscritas guardadas forman parte del MVP gratuito. Seleccionar uno o dos beneficios pagados respaldados por solicitudes, en vez de construirlos todos.

La firma básica, varias firmas manuscritas guardadas, colocación manual en las páginas necesarias, guardado y compartir permanecen disponibles. Mostrar cualquier precio antes de usar una función de pago; permitir seguir trabajando con las funciones básicas.

No fijar precio definitivo antes de probar el producto y la propuesta pagada. Un intervalo exploratorio de S/19.90–29.90 podría servir como hipótesis local, no como estimación de disposición a pagar ni recomendación basada en ingresos observados. No copiar las suscripciones semanales de competidores como señal de rentabilidad.

No incluir banners, intersticiales ni anuncios recompensados en este producto. Cambiar una variable cada vez y comparar finalización, tiempo, errores, ingresos y comentarios.

Antes de invertir de forma relevante en adquisición, medir coste por usuario que completa una exportación y, cuando haya monetización, ingreso neto por usuario adquirido. Las instalaciones gratuitas validan adquisición; las compras reales validan disposición a pagar.

## 7. Medición y privacidad

Recomendación: incorporar Crashlytics y Analytics desde las pruebas, sin que su inicialización/envío condicione abrir, firmar o exportar. Permitir control de recopilación. Firebase admite desactivar la recopilación automática y habilitarla después.

«Procesamiento local y firma sin conexión» describe la función. «La app nunca se conecta ni envía datos» sería incompatible con telemetría remota habilitada. Si se elige cero comunicaciones, usar diagnósticos locales exportados voluntariamente en vez de Firebase remoto. El envío por WhatsApp/correo y un archivo elegido en un proveedor de nube dependen, por separado, de esos servicios.

No registrar nombres de archivo, URI/rutas completas, texto del PDF, firmas, contraseñas, DNI ni correos. Revisar también mensajes de excepciones y logs. Usar rangos de tamaño/número de páginas, duración, versión y categorías de error. Desactivar identificadores publicitarios si no se necesitan y reflejar el comportamiento real en la información de privacidad.

Revisar copias de seguridad del sistema y archivos temporales para que la promesa sobre documentos/firmas locales coincida con la implementación.

| Evento propuesto | Qué indica |
| --- | --- |
| document_import_started | Se abrió un intento de importación. |
| document_open_success / document_open_error | El documento quedó listo o falló; separar cancelación voluntaria. |
| signature_placed | Se colocó al menos una firma; evitar contar cada movimiento. |
| export_started | Comenzó la escritura de una salida solicitada. |
| export_success / export_error | Resultado de la escritura; disparar éxito una sola vez por operación completada. |
| share_sheet_opened | Se abrió la hoja para compartir; no demuestra entrega al destinatario. |
| premium_viewed / purchase_success | Solo cuando exista una oferta real. |

Medidas prioritarias:

- Finalización por sesión de firma: sesiones con al menos una salida correcta / sesiones con PDF abierto correctamente.
- Éxito técnico de exportación: operaciones completadas / operaciones iniciadas, con cancelaciones diferenciadas.
- Tiempo hasta la primera salida correcta, mediana y percentil 90, segmentado por tamaño y páginas.
- Fallos por Android, dispositivo y versión de app.
- Frecuencia de nuevos trámites a 7/30 días como contexto, sin exigir uso diario a una herramienta ocasional.

La ausencia de exportación no demuestra por sí sola frustración: puede ser un abandono voluntario. Complementar eventos con observación de usuarios y preguntas breves. Los usuarios sin telemetría o que no vuelven a conectarse quedan subrepresentados.

Objetivos iniciales propuestos, no referencias de mercado: que al menos 8 de 10 personas nuevas completen sin ayuda el caso sencillo durante una prueba observada; resolver todos los fallos reproducidos de pérdida/corrupción antes de publicar; perseguir más de 99 % de exportaciones técnicas correctas en archivos admitidos una vez haya muestra suficiente, informando siempre del tamaño de la muestra. La telemetría de escritura exitosa no sustituye la verificación visual del PDF.

## 8. Trabajo pendiente

Los requisitos, bugs, prioridades y estados se mantienen exclusivamente en [BACKLOG.md](../BACKLOG.md). Se trasladaron las tareas del plan inicial conservando sus IDs o indicando su agrupación. El orden acordado es: bocetos, firma local, entrada Gmail/WhatsApp, guardado público, historial, prueba de original y beta.

La preparación de navegación, Room y Firebase no completa el flujo de firma. El alcance implementado y las decisiones actuales están en la sección 10 de [ARQUITECTURA_FIRMA_PDF.md](ARQUITECTURA_FIRMA_PDF.md).

## 9. Criterios de aceptación prioritarios

- Un PDF local admitido puede firmarse y guardarse en modo avión; la ausencia de Firebase/red no bloquea el recorrido.
- Para entradas desde Gmail/WhatsApp/solo lectura, el original permanece intacto; la salida nueva se abre fuera de la app, al menos en dos lectores con motores diferentes. Si se habilita actualizar el original, la operación debe ser recuperable ante fallos y la salida debe abrirse igual de bien.
- Firma en el lugar correcto tras exportar: primera y última página, una sola página, páginas giradas, distintos tamaños y recortes de página, varios niveles de zoom.
- Conservar texto y calidad del documento; no rasterizar todas las páginas como atajo para insertar una firma.
- No perder el trazo por tocar fuera del cuadro ni crear documentos duplicados por pulsaciones rápidas.
- Exportaciones canceladas o fallidas no se anuncian como éxito; reintentar sin volver a dibujar.
- Contraseñas, cifrado y documentos previamente certificados tienen una política definida antes de editar. Lo no admitido se comunica temprano.
- Las firmas guardadas pueden borrarse; su uso y respaldo son explícitos.
- La ruta de Compartir tiene permiso de lectura para el destinatario y no elimina el temporal antes de que se utilice.
- El flujo de permisos se verifica en Huawei Android 10 y Android 15, además de la matriz de versiones que finalmente se decida soportar.

## 10. Próximo paso concreto

Copiar este plan y `ARQUITECTURA_FIRMA_PDF.md` al proyecto nuevo. Dibujar el boceto de Home/editor/Crear firma, revisar el código reutilizable y hacer EXP-001. Luego abordar EXP-002 como prueba técnica separada. Mantener un `BACKLOG.md` único para requisitos y bugs, y reglas breves en `AGENTS.md` para que la IA actualice las decisiones cuando cambie el código. No hace falta reestructurar LectorDocumentos para empezar.

## Fuentes

Consultadas el 21/10/2026. Las reseñas citadas son testimonios publicados por usuarios y se presentan como tales.

1. Artmvstd: https://play.google.com/store/apps/details?id=com.artmvstd.signDocuments&hl=en
2. pdfFiller: https://play.google.com/store/apps/details?id=com.pdffiller
3. Reseña de pdfFiller y respuesta sobre prueba gratuita: https://play.google.com/store/apps/details/pdfFiller_Edit_fill_sign_PDF?hl=no&id=com.pdffiller
4. Signature Master: https://play.google.com/store/apps/details?id=com.fumaomao.signature
5. Battery Stats Saver: https://play.google.com/store/apps/details?id=com.SignMaker.signature.pdf.docs
6. Diferenciart: https://play.google.com/store/apps/details?id=sign.pdf.document
7. EclixTech: https://play.google.com/store/apps/details?id=com.esign.pdf.sign.documents.files&hl=en
8. Adobe, funciones gratuitas móviles: https://helpx.adobe.com/acrobat/mobile/subscription-refunds/about-subscriptions.html
9. Android, Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
10. Android, IS_PENDING: https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_PENDING
11. Firebase Crashlytics: https://firebase.google.com/docs/crashlytics/android/customize-crash-reports
12. Firebase Analytics: https://firebase.google.com/docs/analytics/android/configure-data-collection
13. Firebase, datos técnicos: https://firebase.google.com/support/privacy
14. Fuentes facilitadas por Jhonni: Word «APLICACIONES PARA FIRMAR DOCUMENTOS PDF», observaciones del mensaje y ARQUITECTURA.md de LectorDocumentos.
