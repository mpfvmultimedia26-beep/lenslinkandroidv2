# LensLink Android

App Android que se hace pasar por la app de iPhone de LensLink: escucha en TCP **9979**,
habla el protocolo `OBSC` v1 (`docs/PROTOCOL.md` del repo original) y se anuncia por Bonjour
como `_lenslink._tcp`. El **plugin de OBS no se toca**: lo ve como un teléfono más.

## Qué implementa
- HELLO / VIDEO_CONFIG / VIDEO (Annex B, H.264 o HEVC, parameter sets delante de cada keyframe) / PING
- TIMESYNC (reloj = el de los timestamps de la cámara) y STATE (< 1 KiB, con recorte)
- CONTROL: zoom, exposure_bias, focus, flashlight, flip, selectLens, white_balance, lock, exposure manual,
  stabilization, set_format (720p/1080p/4K, 30/60 fps, h264/hevc), set_quality, start/stop/pause/resume, tally, identify
- Inicio remoto armado (`standby` + `armed` en el HELLO), igual que en iOS
- Backpressure: si el Wi-Fi no da abasto se tira lo pendiente, se espera keyframe y baja el bitrate (sube de nuevo solo)
- Bitrates iguales a la tabla de iOS (4K = 30 Mbps H.264 / 18 Mbps HEVC; «maximum» ×1.5)

## Interfaz (v0.2)
- Siempre horizontal, oscura y minimalista (acento ámbar). Vista previa 16:9 a pantalla completa.
- **Pellizcar** = zoom · **tocar** = enfocar y medir luz · **arrastrar arriba/abajo tras enfocar** = exposición ·
  **mantener pulsado** = bloquear/liberar AE y balance de blancos.
- Columna derecha: lentes (0.5 / 1 / 2 / 3 según el móvil), linterna, cambiar de cámara.
- Botón central inferior: **look** (Natural, Contraste, Intenso, Cine, B/N; solo los que el móvil soporte).
- Botón redondo: iniciar/detener emisión. «⋯»: resolución, fps, códec, calidad, inicio remoto, servidor.

## Ahorro de batería
- Tras **5 s sin tocar**: pantalla negra con brillo mínimo mostrando solo el % de batería (se desplaza cada 30 s
  para no marcar OLED). Un toque la despierta. La emisión a OBS **no se interrumpe**.
- Si no se está emitiendo, al atenuar se cierra la cámara. En espera no se mantiene el wake lock de CPU.
- Sin emitir, la vista previa va a 30 fps.

## Compilar el APK
**Opción A – GitHub (sin instalar nada):** sube esta carpeta a un repo; Actions → *Build APK*
→ descarga el artefacto `LensLinkAndroid-apk` (`app-debug.apk`).

**Opción B – Android Studio:** abre la carpeta, deja que sincronice Gradle, *Build → Build APK(s)*.

**Opción C – terminal** (JDK 17 + Android SDK 34 + Gradle 8.7): `gradle assembleDebug`.

## Usar con OBS
1. Instala el APK, abre la app, **Iniciar servidor** (acepta permisos de cámara/notificaciones).
2. En OBS: fuente **LensLink Camera** → conexión **Wi-Fi** → elige el teléfono en la lista (Bonjour)
   o escribe la IP que muestra la app. Teléfono y PC en la misma red (mejor Wi-Fi 5 GHz / 6 GHz).
3. **Iniciar cámara** en el teléfono, o activa «Armar inicio remoto» y usa *Start camera on the phone* en OBS.
4. **USB:** el modo USB del plugin usa usbmuxd (solo iPhone). En Android: `adb forward tcp:9979 tcp:9979`
   y en la fuente usa Wi-Fi con teléfono `127.0.0.1`.

## Límites conocidos (v0.1)
- **No se compiló ni probó en un teléfono** al escribirlo: puede pedir algún ajuste menor al primer build.
- 4K60 depende del móvil: la app solo ofrece combinaciones que cámara **y** encoder declaran soportar.
- Lentes: se cambian con el zoom ratio de la cámara lógica (Android 11+); en Android 8–10 solo hay zoom digital.
- Exposición manual solo si ISO **y** obturador son manuales (Camera2 no permite fijar uno solo).
- Sin HLG / Apple Log, sin pantalla verde, sin audio del micrófono.
- «Pausa» deja de mandar frames (OBS conserva el último); iOS dibuja una imagen de pausa.
- Los timestamps del encoder vienen de la cámara; si algún fabricante los reescribe, el cálculo de latencia puede salir raro (el vídeo funciona igual).

## Licencia
Implementación independiente del protocolo. El repo LensLink original es GPL-2.0; si reutilizas
código suyo, respeta esa licencia.
