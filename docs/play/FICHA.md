# Ficha de Google Play y respuestas a los formularios

Copia y pega cada bloque en Play Console. Todo está en español (idioma principal: **Español – es-419** o **es-ES**, el que prefieras).

---

## 1. Detalles de la app

**Nombre de la app** (máx. 30)
```
EpubFold: lector EPUB y cómics
```

**Descripción breve** (máx. 80)
```
Lee EPUB, PDF y cómics con páginas que pasan como en papel. Ideal en plegables.
```

**Descripción completa** (máx. 4000)
```
EpubFold es un lector de libros y cómics pensado para disfrutar de la lectura como en papel, y para sacar todo el partido a los teléfonos plegables.

PASA PÁGINA COMO EN UN LIBRO DE VERDAD
• Animación de página que se dobla siguiendo tu dedo, con sombra y textura de papel.
• Vista de doble página al abrir el plegable, como un libro abierto.
• Temas claro, sepia, oscuro y negro puro, o cambio automático según la hora.

TUS LIBROS, A TU MANERA
• EPUB, PDF y cómics (CBZ).
• Tipografías, tamaño, interlineado y márgenes ajustables. Respeta el estilo original del libro si lo prefieres.
• Lectura biónica opcional para leer más rápido.
• Notas al pie en una ventana, imágenes a pantalla completa.
• Subrayados de colores, notas y marcadores, con exportación.
• Diccionario y traducción sin salir del libro (la traducción funciona en el propio teléfono).
• Lectura en voz alta, también con la pantalla apagada.

CÓMICS Y MANGA
• Lectura viñeta a viñeta, modo webtoon con desplazamiento continuo y lectura de derecha a izquierda.
• Recorte automático de márgenes, páginas panorámicas y mejora de imagen.
• Zoom suave con dos dedos y doble toque.

UNA BIBLIOTECA BONITA
• Estanterías, colecciones, series y búsqueda.
• Cambia portadas: desde las páginas del libro, sugerencias de internet o tu galería.
• Temas de estantería generados con los colores de tus portadas.
• Sinopsis, géneros y número de páginas automáticos.

PENSADA PARA PLEGABLES
• Panel lateral con índice, notas y diccionario cuando la pantalla está abierta.
• Modo semiplegado (Flex): lee en la mitad superior y controla desde la inferior.
• Arrastra libros desde otras apps en multiventana.

TU PROGRESO EN TODOS TUS DISPOSITIVOS (OPCIONAL)
• Crea una cuenta para sincronizar progreso, notas, marcadores y estadísticas.
• Clubes de lectura para leer a la vez con amigos.
• Estadísticas, rachas y objetivos de lectura.
• Copia de seguridad completa en un archivo.

ASISTENTE DE LECTURA CON IA (OPCIONAL)
• Resumen de lo leído, lista de personajes y preguntas sobre el libro, sin destripes: solo usa hasta tu página actual.
• Funciona con tu propia clave de API de Anthropic (Claude).

Sin anuncios. Tus libros se quedan en tu teléfono.
```

**Categoría:** Libros y obras de consulta
**Etiquetas sugeridas:** Lector de libros electrónicos, Cómics, Libros
**Correo de contacto:** info@kodaura.online (se muestra públicamente)
**Sitio web:** https://kodaura.online (opcional, si ya tienes la web)
**Política de privacidad:** https://emmanuelquintana.github.io/epub-fold-releases/privacy.html

## 2. Gráficos

| Recurso | Archivo / requisito |
|---|---|
| Icono 512×512 | `docs/play/graphics/icon-512.png` |
| Gráfico de funciones 1024×500 | `docs/play/graphics/feature-1024x500.png` |
| Capturas de teléfono | 4–8 capturas de tu Fold **cerrado** (pantalla exterior). PNG/JPG, lado corto ≥ 320 px |
| Capturas de tablet 7" y 10" | 4–8 capturas con el Fold **abierto** (sirven para ambas). Recomendado: así la app aparece como apta para tablets/plegables |

Capturas sugeridas (en este orden): biblioteca con portadas · página pasando a mitad de animación · doble página con el Fold abierto · cómic en modo viñeta · panel lateral con subrayados · estadísticas · tema oscuro.

---

## 3. Contenido de la app (Política → Contenido de la app)

### Política de privacidad
`https://emmanuelquintana.github.io/epub-fold-releases/privacy.html`

### Acceso a la app
"Todas las funciones están disponibles sin restricciones de acceso" → **No**, hay funciones con inicio de sesión. Añade instrucciones:
- Nombre: `Cuenta de prueba`
- Usuario / contraseña: crea una cuenta de prueba en la app (p. ej. `revisor.epubfold@...`) y pégala aquí.
- Nota: `La cuenta es opcional; sirve para sincronizar y clubes de lectura. El asistente de IA requiere una clave de API propia del usuario y no es necesario para revisar la app.`

### Anuncios
**No**, la app no contiene anuncios.

### Clasificación de contenido (cuestionario IARC)
- Categoría: **Referencia, noticias o educación** (o "Todas las demás apps").
- Violencia, sexo, lenguaje, drogas, apuestas: **No** en todo.
- ¿Los usuarios pueden interactuar o intercambiar contenido? **Sí** (clubes de lectura con comentarios).
- ¿Comparte la ubicación del usuario? **No**. ¿Compras digitales? **No**.
- ¿Genera contenido con IA? **Sí** (asistente de lectura; texto).
Resultado esperado: PEGI 3 / Para todos (puede salir 12 por la interacción entre usuarios; es normal).

### Público objetivo
- Edades: **18 y más** (más sencillo; evita requisitos de la política de familias). Si quieres, 13+ también vale.
- ¿Atrae a niños? **No**.

### Apps de noticias: **No**. Apps de salud: **No**. Apps financieras: **No**. Gobierno: **No**.

### Seguridad de los datos (Data safety)
- ¿Recopila o comparte datos? **Sí**.
- ¿Todos los datos se cifran en tránsito? **Sí**.
- ¿Ofreces una forma de solicitar la eliminación? **Sí** → URL: `https://emmanuelquintana.github.io/epub-fold-releases/delete-account.html`
- ¿La creación de cuenta es obligatoria? **No** (opcional) — métodos: usuario y contraseña, Google (OAuth).

| Tipo de dato | Recopilado | Compartido | Opcional | Finalidad |
|---|---|---|---|---|
| Información personal → **Correo electrónico** | Sí | No | Sí (solo con cuenta) | Gestión de la cuenta, funcionalidad de la app |
| Información personal → **Nombre** (nombre visible en clubes) | Sí | No | Sí | Funcionalidad de la app |
| Información personal → **ID de usuario** | Sí | No | Sí | Gestión de la cuenta, funcionalidad |
| Actividad en apps → **Otro contenido generado por el usuario** (notas, subrayados, comentarios de club) | Sí | No | Sí | Funcionalidad de la app |
| Actividad en apps → **Interacciones en la app** (progreso y estadísticas de lectura) | Sí | No | Sí | Funcionalidad de la app |
| Información y rendimiento de la app → **Registros de fallos** | Sí | No | Sí (se puede desactivar) | Analíticas |
| Información y rendimiento de la app → **Diagnóstico** | Sí | No | Sí | Analíticas |
| Identificadores de dispositivo u otros (ID de instalación de Crashlytics) | Sí | No | Sí | Analíticas |

"Compartido" = No: Firebase actúa como proveedor de servicios (no cuenta como compartir). El texto enviado a Anthropic lo envía el usuario con su propia clave, a petición suya; Google permite no declararlo como "compartido" cuando es una acción iniciada por el usuario hacia un servicio que él elige, pero si quieres ser más conservador marca **Actividad en apps → Otro contenido generado por el usuario → Compartido: Sí**, finalidad "Funcionalidad de la app".

### Servicios en primer plano
Permiso `FOREGROUND_SERVICE_MEDIA_PLAYBACK` → tipo **Reproducción multimedia**.
- Descripción: `La lectura en voz alta (texto a voz) del libro continúa cuando el usuario apaga la pantalla o cambia de app, con controles en la notificación.`
- Vídeo: graba con el móvil (≈20 s): abrir libro → Leer en voz alta → apagar pantalla → se oye → notificación con pausa. Súbelo a YouTube como **No listado** y pega el enlace.

### Funciones de IA generativa
Declara que la app genera contenido con IA (texto). Mecanismo de reporte: en cada respuesta del asistente hay un botón **"Reportar respuesta"** que abre un correo al desarrollador.

### Eliminación de cuenta
- En la app: Cuenta → **Eliminar cuenta**.
- Web: `https://emmanuelquintana.github.io/epub-fold-releases/delete-account.html`

---

## 4. Notas de la versión (primer lanzamiento)
```
<es-419>
Primera versión de EpubFold en Google Play:
• Lector de EPUB, PDF y cómics con paso de página realista
• Doble página y panel lateral en plegables
• Subrayados, notas, diccionario, traducción y lectura en voz alta
• Sincronización opcional y clubes de lectura
</es-419>
```
