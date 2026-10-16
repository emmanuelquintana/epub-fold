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
- **Copia de seguridad** (ZIP con libros o JSON sólo con progreso) y **cuenta en la nube** con Firebase.
- Abre EPUB desde el explorador de archivos o "Compartir".

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
