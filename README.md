# EpubFold

Lector de EPUB para Android pensado para el Galaxy Z Fold (probado en diseño para el Fold 8 Ultra).

- **Animación de libro real**: la esquina de la hoja se levanta siguiendo el dedo, se dobla sobre una línea de pliegue, muestra el reverso del papel y proyecta sombras. También pasa con toques en los bordes o teclas de volumen.
- **Aprovecha el Fold**: con el teléfono abierto muestra **doble página** como un libro, con el lomo en la bisagra; cerrado (pantalla exterior) muestra una página. Al plegar/desplegar conserva tu posición sin recargar. En postura "libro" (medio abierto en vertical) fuerza doble página.
- **Modos Claro, Sepia y Oscuro**.
- Tipografía Serif/Sans, tamaño de letra, interlineado, texto justificado con guiones.
- Índice (TOC), barra para saltar de página, progreso guardado por libro.
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
