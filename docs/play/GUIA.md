# Publicar EpubFold en Google Play: paso a paso

Desarrollador: **Kodaura** · Paquete: `com.emmanuel.epubfold` (no se puede cambiar después de subir el primer AAB).

## 0. Lo que ya está preparado en el repo
- Dos variantes: **full** (APK para instalar a mano, con escaneo de todo el almacenamiento y actualizador propio) y **play** (AAB para Google Play, sin permisos que Play rechaza: sin "acceso a todos los archivos", sin "instalar apps" y sin actualizador propio).
- Target API 36, R8, Crashlytics, botón **Eliminar cuenta**, botón **Reportar respuesta** en el asistente de IA y enlace a la política de privacidad en *Cuenta → Aplicación*.
- Cada build de GitHub Actions publica `EpubFold-N.apk` y **`EpubFold-play-N.aab`** en la release `build-N`.
- Política de privacidad y página de eliminación de cuenta: `docs/play/privacy.html` y `docs/play/delete-account.html` (se copian al repo público `epub-fold-releases`).
- Textos de la ficha y respuestas de formularios: `docs/play/FICHA.md`. Icono y gráfico: `docs/play/graphics/`.

## 1. Correo de soporte
Ya configurado: **info@kodaura.online** (botón "Reportar respuesta", política de privacidad y eliminación de cuenta). Úsalo también como correo de contacto en Play Console. Para cambiarlo, edita `SUPPORT_EMAIL` en `app/build.gradle.kts` y los dos `.html` de `docs/play/`.

## 2. Activa GitHub Pages para la política de privacidad (2 min)
1. GitHub → repo **epub-fold-releases** → *Settings* → *Pages*.
2. *Source*: **Deploy from a branch** → Branch **main** / **(root)** → Save.
3. En 1–2 min estarán en línea:
   - https://emmanuelquintana.github.io/epub-fold-releases/privacy.html
   - https://emmanuelquintana.github.io/epub-fold-releases/delete-account.html
Ábrelas en el navegador para comprobarlo antes de seguir.

## 3. Crea la app en Play Console
1. https://play.google.com/console → **Crear app**.
2. Nombre: `EpubFold: lector EPUB y cómics` · Idioma predeterminado: Español · **App** · **Gratis**.
3. Acepta las declaraciones → *Crear app*.

> Gratis no se puede cambiar a de pago más tarde.

## 4. Completa "Configurar tu app" (panel principal)
Ve tarea por tarea y copia las respuestas de `FICHA.md`, sección 3:
1. Política de privacidad
2. Acceso a la app (crea antes una cuenta de prueba en la app)
3. Anuncios → No
4. Clasificación de contenido
5. Público objetivo → 18+
6. Apps de noticias, Gobierno, Financieras, Salud → No
7. Seguridad de los datos
8. Ficha de Play Store principal: textos, icono, gráfico y capturas

## 5. Firma de apps de Google Play
Usa el keystore que ya tienes en los secretos de GitHub como **clave de subida**. Google guarda la clave final:
1. *Prueba y lanzamiento → Configuración → Integridad de la app → Firma de apps*.
2. Deja la opción por defecto: **"Google genera y protege la clave de firma"**.
3. Al subir el primer AAB se activa sola; tu keystore queda registrado como clave de subida.

**Importante:** no pierdas el keystore ni su contraseña. Guárdalo también fuera de GitHub (en un USB o en un gestor de contraseñas). Si lo pierdes, puedes pedir a Google que lo restablezca, pero tarda días.

## 6. Sube el AAB a "Prueba interna" (para probarlo tú)
1. GitHub → repo **epub-fold** → *Releases* → última `build-N` → descarga **`EpubFold-play-N.aab`**.
2. Play Console → *Prueba y lanzamiento → Pruebas → Prueba interna* → *Crear versión*.
3. Sube el `.aab`. Notas de la versión: copia la sección 4 de `FICHA.md`.
4. *Testers*: crea una lista con tu correo → guarda → copia el **enlace de participación** → ábrelo en el Fold e instala desde Play.

Si tienes instalada la versión APK, desinstálala antes: tiene otra firma, así que Play no puede actualizarla. Haz antes una copia en *Cuenta → Exportar copia completa*.

## 7. Arregla el inicio de sesión con Google (obligatorio tras el paso 6)
Google vuelve a firmar la app con su propia clave, así que Firebase no la reconoce hasta que añadas su huella:
1. Play Console → *Integridad de la app → Firma de apps* → copia **SHA-1** y **SHA-256** de la *clave de firma de apps*.
2. Firebase Console → proyecto → ⚙ *Configuración del proyecto* → app Android `com.emmanuel.epubfold` → **Agregar huella digital** → pega las dos.
3. No hace falta volver a compilar; espera unos 5 min y prueba "Continuar con Google".

## 8. Prueba cerrada: 12 testers durante 14 días (obligatorio en cuentas personales nuevas)
Las cuentas personales creadas después de nov. 2023 deben hacer una prueba cerrada antes de publicar en producción:
1. *Pruebas → Prueba cerrada* → *Crear canal* (o usa "Alpha") → *Crear versión* → sube el mismo AAB (o uno más nuevo).
2. *Testers*: añade **al menos 12 correos de Gmail** (amigos, familia, comunidades de testers). Cada uno debe:
   - aceptar el enlace de participación,
   - instalar la app desde Play,
   - **mantenerla instalada 14 días seguidos** (si abren la app de vez en cuando y te dan opiniones, mejor).
3. *Países*: selecciona los que quieras (p. ej. México, España, Latinoamérica, EE. UU.).
4. Envía la versión a revisión (la primera revisión puede tardar de 1 a 7 días).
5. Mientras tanto sube versiones nuevas cuando quieras: cada build de GitHub genera un AAB nuevo con un `versionCode` mayor.

## 9. Solicita acceso a producción
Pasados los 14 días con 12 testers o más, en el panel aparece **"Solicitar acceso a producción"**. Responde el cuestionario: cómo reclutaste testers, qué opinaron y qué cambiaste. Sé concreto. Google contesta en unos 7 días.

## 10. Publica en producción
1. *Producción* → *Países/regiones* → añade los países.
2. *Crear versión* → **Añadir desde biblioteca** (el AAB ya probado) → notas de la versión.
3. Lanzamiento escalonado sugerido: 20 % → 50 % → 100 % en unos días, vigilando *Android vitals* y Crashlytics.
4. *Revisar versión* → **Iniciar lanzamiento**.

## 11. Para siguientes versiones
1. Haz los cambios → push → GitHub genera `build-N` con el AAB nuevo (el `versionCode` sube solo).
2. Play Console → Producción (o prueba) → *Crear versión* → sube `EpubFold-play-N.aab`.
3. El APK `full` sigue funcionando por el canal de actualización propio para quien no use Play.

## Rechazos frecuentes y cómo evitarlos
| Motivo | Prevención |
|---|---|
| Política de privacidad caída o genérica | Paso 2; la URL debe abrir sin iniciar sesión |
| Falta la eliminación de cuenta | Ya existe en la app y en la web; pon la URL en Seguridad de los datos |
| Servicio en primer plano sin justificar | Declaración y vídeo (FICHA.md, sección 3) |
| Credenciales de prueba que no funcionan | Comprueba la cuenta de prueba antes de enviar |
| Capturas que no muestran la app real | Usa capturas reales del Fold, sin marcos engañosos |
| Uso de marcas de terceros | No uses portadas de cómics con copyright en las capturas: usa libros de dominio público (Proyecto Gutenberg) |
