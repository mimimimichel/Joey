# Code repris du projet drone (AiDrone / Bebop IA)

Copie telle quelle (paquet `com.bebopia.app…`) depuis https://github.com/mimimimichel/AiDrone (version 3.5),
pour démarrer Joey sans repartir de zéro. **Ce dossier ne compile pas seul** : à intégrer dans l'app Joey en changeant
le paquet et en remplaçant les dépendances au drone.

Dépendances à remplacer :
- `RoombaDrone` hérite de `com.bebopia.app.core.DroneBase` (limites, position estimée, annulation, détecteur d'obstacles) :
  en faire une classe `RoombaRobot` autonome (la logique du Roomba elle-même est dans `RoombaOI.kt` / `RoombaDrone.kt`).
- `GeminiVision` utilise `ViewReport`, `ViewItem`, `PathResult`, `LandingView` du drone : garder seulement `find`/`analyzeView`.
- `LocalDetector` et `FastDetector` utilisent `ObjectDetector`, `Detection` (petites interfaces de `core/ai`) : à recopier ou simplifier.
- `VoiceIO` utilise `Speaker` (interface d'une ligne).
- `UsbSerial` a besoin de `com.github.mik3y:usb-serial-for-android:3.8.1` (dépôt `https://jitpack.io`, limité au groupe `com.github.mik3y`)
  et du filtre `res/xml/usb_serial_filter.xml` dans le manifeste (`USB_DEVICE_ATTACHED`).

Voir `../../docs/BRIEF.md` pour le rôle de chaque fichier.
