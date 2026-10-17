# EpubFold

Lector de EPUB para Android pensado para el Galaxy Z Fold (probado en diseño para el Fold 8 Ultra).

- **Animación de libro real**: la esquina de la hoja se levanta siguiendo el dedo, se dobla sobre una línea de pliegue, muestra el reverso del papel y proyecta sombras. También pasa con toques en los bordes o teclas de volumen.
- **Aprovecha el Fold**: con el teléfono abierto muestra **doble página** como un libro, con el lomo en la bisagra; cerrado (pantalla exterior) muestra una página. Al plegar/desplegar conserva tu posición sin recargar. En postura "libro" (medio abierto en vertical) fuerza doble página.
- **7 temas** estilo Apple Books: Original, Calma, Papel, Silencio, Noche, Negro OLED y Ámbar (lectura nocturna), con botón rápido sol/luna.
- **Tres animaciones**: curvatura de papel, deslizar (iOS) o ninguna.
- **Marcadores** con cinta en la esquina de la página, **búsqueda** en todo el libro (sin distinguir acentos) con resaltado del resultado.
- **Temas y ajustes**: brillo dentro de la app, tamaño de letra, Serif/Sans, interlineado, márgenes, justificado, pantalla siempre encendida.
- **Tiempo restante** del capítulo según tu velocidad de lectura.
- Biblioteca con título grande, «Seguir leyendo», orden por recientes/título/autor y menú contextual.
- **Subrayados en 5 colores y notas**: mantén pulsado el texto, ajusta con las asas y elige color, nota, copiar, definir, traducir, escuchar o compartir. Exporta tus notas en Markdown.
- **Diccionario** (Wikcionario) y **traductor sin conexión** (ML Kit) al seleccionar texto.
- **Lectura en voz alta** con resaltado de la frase y paso de página automático; velocidad 0,8×–2×.
- **Panel lateral en el Fold abierto**: índice, marcadores o notas fijos en una mitad mientras lees en la otra.
- **Estadísticas y metas**: minutos al día, racha, últimos 7 días y libros terminados en el año.
- **Tema automático** según el sistema o por horario; **textura de papel**, tema **Pergamino** y tema **Personal** con tus colores.
- **Fuentes** Literata, Atkinson Hyperlegible y OpenDyslexic (licencia OFL, ver `licenses/`).
- **Colecciones** (Leyendo, Quiero leer, Terminados) y búsqueda en la biblioteca.
- **Copia de seguridad** (ZIP con libros o JSON sólo con progreso) y **cuenta en la nube** con Firebase
  (email y contraseña o **cuenta de Google**).
- **PDF y cómics (CBZ/CBR)**: lector de páginas con zoom (pellizcar o doble toque), doble página con el Fold
  abierto (portada sola) y modo **manga** de derecha a izquierda. Los CBR se convierten a CBZ al importarlos.
- **Importación masiva**: varios archivos a la vez, una **carpeta completa** o **catálogos OPDS**
  (Project Gutenberg, Feedbooks o tu servidor de Calibre/Kavita/Komga, con usuario y contraseña).
- **Series** leídas del EPUB (Calibre o EPUB3) o de ComicInfo.xml, editables, con orden «Serie»;
  **portadas automáticas** desde Open Library para los libros que no traen una.
- **Recuerdo del día**: un subrayado de tus libros distinto cada día en la biblioteca, lista de todos tus
  subrayados y **widget** para la pantalla de inicio.
- **Widget «Seguir leyendo»** con portada y progreso (también en la pantalla exterior del Fold).
- **Lectura enfocada**: **lectura rápida** palabra a palabra (150–900 ppm) y **regla de lectura** que resalta
  unas líneas (Ajustes → Lectura enfocada).
- **Voz alta mejorada**: temporizador (5 min a 1 h o fin del capítulo) y controles en la notificación,
  la pantalla de bloqueo y los auriculares; sigue leyendo con la pantalla apagada.
- **Cuaderno del libro**: notas largas (se sincronizan) y hoja para escribir a mano con el **S Pen**
  (presión, goma con el botón del lápiz, ignora la palma). Con el Fold abierto, en el panel lateral
  mientras lees en la otra mitad.
- **Aviso de actualizaciones** dentro de la app: descarga e instala la versión nueva.
- **Traducción en paralelo** (pestaña «Traducir» del panel lateral, o Ajustes › Traducción de la página):
  la página traducida párrafo a párrafo sin conexión mientras lees el original.
- **Vocabulario**: las palabras que defines o traduces se guardan con su frase y se repasan como tarjetas
  (repaso espaciado). Más › Vocabulario.
- **Voces y audio**: elige la voz (las de mayor calidad primero) y guarda un capítulo como audio M4A en
  Música/EpubFold (botón ⋯ del reproductor).
- **Pasar página sin tocar**: pasa la mano sobre el sensor de proximidad (mantenla para volver) o inclina el
  teléfono. Funciona también en PDF y cómics.
- **Modo continuo**: desplazamiento vertical en lugar de páginas (Ajustes › Modo de lectura).
- **Modo Flex**: con el Fold medio plegado sobre la mesa, el libro arriba y los controles abajo.
- **Al plegar o desplegar** se resalta un momento la línea por la que ibas.
- **Arrastrar citas**: con el Fold abierto, arrastra un subrayado del panel lateral a Notas, Samsung Notes…
- **Estanterías propias** (chips en la biblioteca; mantén pulsado un libro › Estanterías…), sincronizadas.
- **Qué leer después**: siguiente de tus series y sugerencias de Open Library por temas.
- **Carpeta de entrada**: importa sola los libros nuevos de una carpeta (al abrir y cada 6 horas).
- **Exportar notas** a Obsidian (un .md por libro en tu bóveda), Readwise y Notion, con sincronización automática.
- **Tarjetas para compartir**: un subrayado convertido en imagen con la portada («Tarjeta» al seleccionar).
- **Buscar en todas tus notas**, subrayados y cuadernos a la vez.
- **Recordatorio diario** con tu racha y lo que te falta para la meta.
- **Tu año en lectura**: resumen tipo «Wrapped» (horas, libros, autor, mes, frase) para compartir.
- **Clubes de lectura**: leed el mismo libro con amigos, con el progreso de cada uno y comentarios por capítulo
  que se ocultan si aún no has llegado. Requiere cuenta; las reglas están en `firestore.rules`.
- Abre EPUB, PDF y cómics desde el explorador de archivos o "Compartir".

## Descargar el APK

Cada push compila el APK con GitHub Actions y lo publica en **Releases** (`EpubFold-N.apk`).
Descárgalo en el teléfono, ábrelo y permite "instalar apps desconocidas".

## Compilar localmente

```
./gradlew assembleRelease
```

Requiere el Android SDK (API 35) y JDK 17.

### Firma propia (opcional)

Sin configuración, el APK se firma con una clave de depuración generada en cada build (para actualizar
puede que tengas que desinstalar la versión anterior). Para firmar siempre con la misma clave, añade en
los *secrets* del repositorio: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Registra además su SHA-1 en Firebase (Configuración del proyecto → tu app Android) para que funcione
«Continuar con Google».

### Canal de actualizaciones

Si existe el secret `RELEASES_DEPLOY_KEY` (clave SSH con escritura en el repo público
`emmanuelquintana/epub-fold-releases`), cada build publica allí `EpubFold.apk` y `latest.json`
(un único commit que se reemplaza). La app lo consulta cada 6 horas y desde **Cuenta → Buscar actualizaciones**.

## Activar cuentas en la nube (Firebase, plan gratuito)

1. Entra en <https://console.firebase.google.com> y crea un proyecto (puedes desactivar Analytics).
2. **Añadir app → Android** con el paquete `com.emmanuel.epubfold`. Descarga `google-services.json`.
3. **Authentication → Comenzar → Correo electrónico/contraseña → Habilitar**.
4. **Firestore Database → Crear base de datos** (modo producción) y en **Reglas** pega:

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /users/{uid}/{document=**} {
         allow read, write: if request.auth != null && request.auth.uid == uid;
       }
     }
   }
   ```

5. En GitHub: **Settings → Secrets and variables → Actions → New repository secret**, nombre
   `GOOGLE_SERVICES_JSON`, y pega el contenido completo del archivo `google-services.json`.
6. Vuelve a ejecutar el workflow **Build APK**. La pantalla **Cuenta** (icono de persona en la biblioteca)
   permitirá crear cuenta e iniciar sesión.

Se sincronizan progreso, colecciones, marcadores, subrayados, notas y estadísticas. Los EPUB no se suben:
al añadir el mismo libro en otro dispositivo se recupera todo. Para mover también los libros usa
**Cuenta → Exportar copia completa**.
