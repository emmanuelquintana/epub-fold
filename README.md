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
