# Recuérdame Aquí

App de Android para guardar notas por lugar. Cuando pasas cerca de ese lugar, el celular vibra y te muestra lo que tenías pendiente, aunque la app esté cerrada.

## Qué hace

- Agregas un lugar tocando el mapa o con el botón **Estoy aquí**.
- Eliges a qué distancia avisarte: 100, 200 o 500 metros.
- Escribes notas en cada lugar (ej: "comprar cable UTP").
- Al entrar a la zona llega una notificación con la lista y un botón **Listo, ya lo hice**.
- Marcas notas como hechas, las borras con pulsación larga, o borras el lugar.
- Todo se guarda en el teléfono. No usa cuenta ni servidor. El mapa es OpenStreetMap (gratis, sin tarjeta).

## Cómo pasarla a tu Samsung

### 1. Instalar Android Studio (una sola vez)
1. Descárgalo gratis de https://developer.android.com/studio e instálalo con las opciones por defecto.
2. Al abrirlo por primera vez, deja que descargue el "Android SDK" (tarda un rato).

### 2. Abrir el proyecto
1. Descomprime `RecuerdameAqui.zip` en una carpeta, por ejemplo `Documentos\RecuerdameAqui`.
2. En Android Studio: **File → Open** y elige la carpeta `RecuerdameAqui` (la que tiene `settings.gradle.kts`).
3. Espera a que termine "Gradle sync" (abajo verás una barra de progreso). La primera vez descarga cosas y puede tardar varios minutos.
4. Si te sale un aviso de "Upgrade Assistant" o de actualizar el plugin de Gradle, puedes ignorarlo.

### 3. Preparar el teléfono
1. En el Samsung: **Ajustes → Acerca del teléfono → Información de software** y toca **Número de compilación** 7 veces. Se activa el "Modo desarrollador".
2. Vuelve a **Ajustes → Opciones de desarrollador** y activa **Depuración USB**.
3. Conecta el teléfono a la PC con el cable USB y acepta el mensaje "¿Permitir depuración USB?".

### 4. Instalar
1. En Android Studio, arriba aparecerá el nombre de tu Samsung.
2. Pulsa el botón verde ▶ (**Run**). La app se instala y se abre sola.

**Alternativa sin cable:** menú **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. Cuando termine, toca "locate" para ver el archivo `app-debug.apk`. Pásalo al teléfono por WhatsApp, Drive o cable y ábrelo; Android pedirá permitir "instalar apps desconocidas".

## Primera vez en el teléfono

1. Permite **Ubicación** y **Notificaciones**.
2. Cuando la app lo pida, en Ubicación elige **Permitir todo el tiempo**. Sin esto solo avisa con la app abierta.
3. Muy importante en Samsung: **Ajustes → Aplicaciones → Recuérdame Aquí → Batería → Sin restricciones**. Si no, Samsung puede dormir la app y los avisos se retrasan o no llegan.
4. Deja el GPS (Ubicación) encendido.

## Cosas a saber

- El aviso puede tardar entre unos segundos y un par de minutos según la señal y el GPS. Si pasas muy rápido en vehículo, usa 500 m.
- Si creas el lugar estando ahí mismo, no avisa al momento: avisará la próxima vez que llegues.
- Para probar: crea un lugar a unas cuadras de tu casa, camina hacia allá y espera el aviso.
- Android permite hasta 100 lugares con avisos activos a la vez.

## Archivos principales

| Archivo | Para qué sirve |
|---|---|
| `MainActivity.kt` | Pantalla principal: lista de lugares, notas y permisos |
| `MapPickerActivity.kt` | Pantalla del mapa para escoger el lugar |
| `GeofenceHelper.kt` | Crea las zonas de aviso (geovallas) en Android |
| `Receivers.kt` | Recibe el aviso al entrar a la zona, el botón "Listo" y el reinicio del teléfono |
| `Notifier.kt` | Arma la notificación |
| `Store.kt` | Guarda lugares y notas en el teléfono |

## Si algo falla al compilar

Copia el error rojo completo de la pestaña **Build** de Android Studio y pégalo en el chat con Claude.
