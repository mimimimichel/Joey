# Brief de projet — Joey (à donner à Claude au début d'une conversation)

## Qui, quoi

- Michael (parle **français**, travaille surtout depuis son **téléphone** : réponses courtes, résultat d'abord,
  APK envoyé directement à installer). Il a déjà construit avec Claude **Bebop IA** (`mimimimichel/AiDrone`),
  une app Android qui pilote un drone Parrot Bebop 2 et un DJI Mavic Mini avec Gemini (voix, vision, casque Meta Quest).
- **Joey** est un projet séparé : un **robot compagnon** = Roomba 616 + Galaxy S8+ posé dessus.
  Personnalité inspirée de Joey (*Beneath a Steel Sky*). Voir `PERSONNALITE.md`.

## Décisions déjà prises

- **Téléphone = cerveau** (pas d'ESP32) : caméra, micro, haut-parleur, écran, IA sur place. Galaxy S8+ (Android 9).
- **Écran vers l'avant = visage** (yeux animés) ; **caméra avant** pour voir (les gens qui lui parlent sont en face).
- **Liaison** : carte USB-série FT232 (5 V) sur la prise USB-C du téléphone, fils Dupont dans la prise mini-DIN du Roomba
  (TX->3, RX->4, RTS->5 réveil, GND->6 ; **jamais 1 et 2**). Voir `MATERIEL.md`.
- **IA** : Gemini en direct (Live API, audio natif) pour la conversation et les décisions ; vision rapide sur le
  téléphone (MediaPipe : détection d'objets EfficientDet-Lite2, gestes, pose) ; Gemini (REST) pour comprendre une image
  au besoin (modèles robotique d'abord : `gemini-robotics-er-2-preview`, puis flash).
- **À distance** : Tailscale sur le S8+ et le téléphone de Michael (pas de port ouvert sur Internet).
- **Nacelle Feiyu SPG (premier modèle) = le cou de Joey** : le téléphone est dessus, elle tourne/incline la tête.
  Elle se pilote en Bluetooth (BLE, nom `FY_Spg_82`) ; protocole **décodé** : `hardware/gimbal-spg/PROTOCOLE.md`,
  code prêt `hardware/gimbal-spg/SpgProtocol.kt`. Voir `NACELLE.md`.

## Ce qui existe déjà (copié dans `reuse/aidrone/`, testé dans le projet drone)

| Fichier | Quoi | État |
|---|---|---|
| `core/roomba/RoombaOI.kt` | Protocole Open Interface : commandes, lecture de 12 capteurs (pare-chocs, vide, batterie, base, mode, roues…), odométrie | Testé (tests unitaires) |
| `core/roomba/RoombaDrone.kt` | Pilote complet : connexion (115200/57600), réveil BRC, mode sécurité, sortie de base, tourner/avancer en boucle fermée sur les roues, pare-chocs, vide, retour base, vitesses continues | Testé contre un faux Roomba ; **dépend de `DroneBase` du projet drone** (à remplacer par une classe `Robot` propre à Joey) |
| `android/UsbSerial.kt` | Ouverture de la carte USB-série (bibliothèque `com.github.mik3y:usb-serial-for-android:3.8.1`, dépôt JitPack), autorisation USB, ligne de réveil sur RTS et DTR | Compile ; pas encore essayé sur le vrai matériel |
| `res/xml/usb_serial_filter.xml` | Filtre USB (FTDI, CH340, CP210x, PL2303) pour le manifeste | — |
| `test/RoombaTest.kt` | Faux Roomba (physique des roues, modes, vide, base) + 8 tests | Passe |
| `android/GeminiLive.kt` | Client Gemini Live (WebSocket, audio 16 kHz/24 kHz, outils/fonctions, image envoyée) | En service dans le drone |
| `android/VoiceIO.kt`, `core/audio/RobotVoice.kt` | Micro, synthèse vocale, filtre « voix de robot » | En service |
| `android/GeminiVision.kt` | Gemini REST pour analyser une image (trouver, décrire) | En service |
| `android/LocalDetector.kt`, `core/vision/FastDetector.kt` | Détection d'objets sur le téléphone (MediaPipe, 80 classes COCO, noms français) | En service |
| `android/GestureWatcher.kt`, `core/vision/Gestures.kt` | Gestes de la main et du corps (MediaPipe) | En service |
| `core/vision/TargetTracker.kt`, `Looming.kt`, `ObstacleGuard.kt`, `Marks.kt` | Suivi d'une cible image par image, détection d'obstacle qui approche, cadres à l'écran | En service |
| `core/ai/Memory.kt` | Mémoire persistante (notes) donnée à l'IA au début de chaque conversation | En service |
| `android/FrameSources.kt` | Images en gris / JPEG depuis la caméra du téléphone (CameraX `PreviewView`) | En service |

Les modèles MediaPipe (`efficientdet_lite2.tflite`, `gesture_recognizer.task`, `pose_landmarker_lite.task`) sont dans
`app/src/main/assets/` du dépôt AiDrone (non copiés ici : ~26 Mo). Pour voir le reste du code drone, ajouter le dépôt
`mimimimichel/AiDrone` à la session.

## Contraintes et habitudes

- **Secrets** : la clé Gemini et la clé DJI ne doivent **jamais** être écrites dans le code ni commitées (dépôt potentiellement public).
  Clé Gemini : injectée à la compilation (`-PgeminiKey=...` -> `BuildConfig.GEMINI_KEY`) ou saisie dans les réglages de l'app.
  Vérifier le diff avant chaque commit.
- Pile : Kotlin, Jetpack Compose, AGP 8.7, Kotlin 2.0, minSdk 26, targetSdk 35. Signature debug fixe (même clé pour toutes les
  versions, pour pouvoir mettre à jour sans désinstaller).
- Tests unitaires pour la logique (faux Roomba), test de lancement Robolectric (le projet drone a eu un plantage au démarrage
  dû à l'ordre d'initialisation : `init {}` du ViewModel en dernier).
- Commentaires et messages de l'app en français simple.
- Envoyer l'APK à Michael après chaque version (il installe depuis son téléphone).

## Architecture proposée

```
app/
  robot/      Robot (interface), RoombaRobot (pilote), FakeRobot (tests, simulation sans Roomba), Gimbal (nacelle BLE)
  brain/      Conversation (Gemini Live + outils), Personnalité (prompt système), Mémoire, Comportements
  senses/     Caméra avant, détection de personnes/objets, gestes, suivi, obstacles
  face/       Visage : yeux animés en Compose (états : repos, écoute, réfléchit, parle, émotions)
  remote/     Accès à distance (page web servie par le téléphone, via Tailscale)
```

Outils donnés à Gemini (premiers) : `avancer(m)`, `tourner(deg)`, `venir_vers_personne`, `suivre(qui)`, `arreter`,
`rentrer_a_la_base`, `exprimer(emotion)` (visage + son), `retenir(note)`, `regarder(direction)` et `regarder_autour` (nacelle).

## Étapes

1. **Squelette** : app Compose, visage (yeux animés), connexion Roomba (bouton « il chante ? »), conduite manuelle à l'écran.
2. **Il parle** : Gemini Live avec la personnalité, outils de conduite, expressions (visage + mélodies).
3. **Il voit** : caméra avant, détection des personnes, les yeux suivent, « viens ici », « suis-moi ».
4. **Il se souvient et vit seul** : mémoire, rondes, accueil, recharge autonome, mot de réveil.
5. **À distance** : page de contrôle via Tailscale.
6. **La tête** : nacelle SPG pilotée en Bluetooth (en parallèle dès qu'on a l'enregistrement), support imprimé pour son manche.
7. Plus tard : alimentation par la batterie du Roomba.
