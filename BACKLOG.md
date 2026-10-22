# Backlog de Firma PDF

Fuente única de requisitos, bugs y estados. El plan de producto está en `docs/PLAN_FIRMA_PDF.md` y las decisiones técnicas en `docs/ARQUITECTURA_FIRMA_PDF.md`.

## Próximas tareas, en orden

| Orden / ID | Tarea y resultado esperado | Prioridad | Estado |
| --- | --- | --- | --- |
| 1 / UX-001 | Bocetos de inicio, editor, crear firma y resultado; incluir carga, errores, cancelación, permisos y exportación en curso. | Alta | Pendiente |
| 2 / FIR-001 | Firma local: SAF → visor PDFium → dibujar y conservar un PNG privado → colocar/mover/redimensionar/eliminar → PDFBox produce un archivo nuevo de prueba. Reabrir en dos motores; medir memoria y tiempos. Incluye INV-001, EXP-001, REQ-002 y REQ-003 del plan inicial. | Alta | Pendiente |
| 3 / REQ-001 | Entrada real Gmail/WhatsApp mediante VIEW/SEND y selector SAF: URI, ClipData, permisos temporales/persistentes y cancelación. Declarar intent filters solo junto con el procesamiento. | Alta | Pendiente |
| 4 / REQ-004 | Guardado público y compartir: crear salida nueva sin colisiones en Documents/FirmarPdf; publicar/verificar antes de mostrar éxito; gestionar espacio y errores. Solicitar WRITE_EXTERNAL_STORAGE únicamente en Android 9, al guardar. Implementar Compartir y FileProvider solo si el URI final lo requiere. | Alta | Pendiente |
| 5 / REQ-007 | Completar historial: registrar únicamente exportaciones válidas, reabrir después de reiniciar, compartir el URI final y quitar referencias a archivos desaparecidos. La entidad, DAO, repositorio, esquema v1 y observación desde Home son solo la base. | Alta | Pendiente |
| 6 / EXP-002 | Prueba técnica de original elegido con SAF: demostrar reemplazo recuperable con varios proveedores, poco espacio y cierre durante escritura. Sin saveIncremental; habilitar solo al preservar el origen ante fallos. | Alta | Pendiente |
| 7 / QA-001 | Beta en Android 9/10/15 y Huawei: modo avión, documentos variados, páginas giradas/recortadas, primera/última página, pulsación doble, cancelación, falta de espacio y compatibilidad nativa de 16 KB. Incluye QA-002 del plan inicial. | Alta | Pendiente |

## Requisitos complementarios

| ID | Resultado esperado | Prioridad | Estado |
| --- | --- | --- | --- |
| REQ-005 | Conservar edición y trazo al rotar y recuperar estado tras recreación cuando corresponda. | Alta | Pendiente |
| MET-001 | Ajustes con consentimiento separado para Analytics y Crashlytics, persistencia y revocación. Hoy ambos SDK están integrados con recopilación desactivada; no hay controles ni DataStore. Revisar logs, excepciones y reporte de prueba voluntario antes de habilitar envío. | Alta | Pendiente |
| PDF-001 | Definir/rechazar PDFs no admitidos, incluidos certificados; probar contraseña conocida y comunicar límites antes de editar. | Alta | Pendiente |
| VAL-001 | Observar personas nuevas completar el flujo y comparar facilidad con Diferenciart. | Alta | Pendiente |
| REQ-006 | Evaluar texto, fecha y marca según necesidades observadas, después de validar firma básica. | Media | Pendiente |
| NEG-001 | Investigar una posible compra única por comodidades después de la beta; sin anuncios, cuentas, cuotas ni bloqueo de exportación en el MVP. | Media | Pendiente |

## Bugs

| ID | Reproducción y resultado esperado | Prioridad | Estado |
| --- | --- | --- | --- |
| BUILD-001 | PDFium 1.0.35 como `implementation` con Kotlin 2.0.21 falla por metadatos 2.2.0 en KSP1 y en compileDebugKotlin. En esta base, sin llamadas al visor, se conserva como `runtimeOnly`, empaquetado en el APK. Antes de implementar el visor, resolver el acceso a su API mediante una prueba de compatibilidad y documentar cualquier cambio mínimo de versión necesario. No omitir comprobaciones del compilador. | Alta | Pendiente para firma local |
| SEC-001 | `:app:lintDebug` señala `TrustAllX509TrustManager` en Bouncy Castle `bcpkix-jdk15to18:1.72`, transitivo de PDFBox. Comprobar alcance/uso real antes de beta; no introducir ni usar clientes TLS que acepten cualquier certificado. El aviso estático por sí solo no demuestra tráfico inseguro de esta app. | Alta | Pendiente de revisión |

No hay bugs funcionales reproducidos registrados. Las funciones de firma aún no existen.
