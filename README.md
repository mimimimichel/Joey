# Joey — un petit robot compagnon

Un Roomba (série 600) + un vieux téléphone Android posé dessus = un petit robot de maison avec un visage,
une voix, une personnalité et de la mémoire. Inspiré de Joey, le robot de *Beneath a Steel Sky* :
une petite intelligence avec du caractère, qui commente, se souvient, et se déplace dans la maison.

> État : **version 0.1 de l'app** (`app/`) : visage, conversation Gemini Live avec la voix de robot, tête sur la nacelle SPG.
> Le Roomba n'est pas encore branché. Le pilote du Roomba, la voix et la vision existent déjà
> (et sont testés) dans le projet drone [`mimimimichel/AiDrone`](https://github.com/mimimimichel/AiDrone) :
> une copie est dans [`reuse/aidrone/`](reuse/aidrone/) pour démarrer.

## L'idée

- **Un visage** : l'écran du téléphone, tourné vers l'avant, affiche des yeux animés (écoute, réfléchit, parle, étonné, boude…).
  La **caméra avant** (côté écran) sert à voir : il regarde les gens qui lui parlent.
- **Un cou** : le téléphone est sur une nacelle Feiyu SPG, Joey tourne et lève la tête sans bouger les roues.
- **Une voix et une personnalité** : conversation en direct avec Gemini, un caractère à lui (pince-sans-rire),
  une mémoire de la maison et des gens.
- **Il vit dans la maison** : vient quand on l'appelle, suit, salue quand quelqu'un rentre, fait des rondes,
  raconte ce qu'il a vu, va se recharger seul sur sa base.
- **Il s'exprime aussi avec le Roomba** : petites mélodies, voyants, petits mouvements (joie, hésitation).
- **À distance** : on peut lui parler et voir par ses yeux depuis son téléphone, hors de la maison (Tailscale).

## L'app (version 0.2)

- **Visage** plein écran (yeux animés : clignements, expressions, regard qui suit les mouvements de tête, bouche qui bouge avec la voix).
- **Conversation** : bouton 🎤 → Gemini Live (même client et même voix de robot que Bebop IA), personnalité de Joey,
  une image de la caméra par seconde, mémoire entre les conversations.
- **Suivi du visage** : la caméra avant cherche les visages (ML Kit, sur le téléphone) ; la tête suit la personne et les yeux
  la regardent. Le sens de rotation se règle tout seul au premier essai.
- **Tête** : se connecte seule à la nacelle SPG (`FY_Spg_…`) ; Joey peut regarder à gauche/droite/haut/bas, balayer la pièce,
  faire oui/non. Panneau 🦒 : joystick à l'écran, centrer, journal (avec les UUID Bluetooth à noter).
- **Gens de la maison** : reconnaissance des visages sur le téléphone (MobileFaceNet, `mobile_face_net.tflite`, repris de
  [estebanuri/face_recognition](https://github.com/estebanuri/face_recognition)) ; seulement les personnes qui le
  demandent ; seules des empreintes (192 nombres) sont gardées, jamais de photo. Seuil 0,50 mesuré sur LFW.
- **Mémoire** : souvenirs sur le téléphone, notés par Joey et extraits automatiquement des conversations.
- **Vue casque Quest** : 🦒 → « Vue dans le casque », https://<téléphone>:8443, la tête suit la tienne.
- **Mot de réveil « Joey »** (reconnaissance vocale du téléphone), fin de conversation seule après 45 s de silence.
- **Recherche Google** (météo, actualités…) dans la conversation ; passage au modèle suivant si un quota est épuisé.
- **Photographe** (compte à rebours sur le visage, galerie Images/Joey), **danse** sur le rythme entendu.
- **Vie propre** : coup d'œil vers ce qui bouge, ennui, sommeil après 10 min, réveil.
- **Mode garde** : journal avec photos, rondes du regard, personne inconnue / bêtise du chien (jugée par Gemini) ->
  alerte sur le téléphone de Michael par [ntfy](https://ntfy.sh) ; page à distance (Tailscale) avec flèches et journal.
- **Carte de la maison** (Gemini Robotics-ER) : « cartographie ici, c'est le salon » -> tour du regard en 6 arrêts, éléments
  pointés avec cap boussole et distance ; « où est la télécommande ? » (la tête se tourne si c'est la pièce actuelle),
  « où suis-je ? ». Positions (x, y) prévues pour l'odométrie du Roomba.
- Clé Gemini : réglages ⚙ (ou `-PgeminiKey=...` à la compilation). Compiler : `./gradlew assembleDebug`.

## Documents

| Fichier | Contenu |
|---|---|
| [`docs/BRIEF.md`](docs/BRIEF.md) | **À lire en premier** (et à donner à Claude au début d'une conversation) : décisions, contraintes, architecture, étapes |
| [`docs/MATERIEL.md`](docs/MATERIEL.md) | Ce qu'il faut acheter, câblage de la prise du Roomba, alimentation, sécurité |
| [`docs/NACELLE.md`](docs/NACELLE.md) | La tête : nacelle Feiyu SPG, enregistrer et décoder son Bluetooth, montage |
| [`docs/PERSONNALITE.md`](docs/PERSONNALITE.md) | Premier jet du caractère de Joey, de sa voix et de ses comportements |
| [`reuse/aidrone/`](reuse/aidrone/) | Code repris du projet drone (pilote Roomba testé, Gemini en direct, vision sur le téléphone…) |
| [`hardware/gimbal-spg/`](hardware/gimbal-spg/) | Protocole Bluetooth décodé de la nacelle, code Kotlin, capture |
| [`hardware/roomba-mount/`](hardware/roomba-mount/) | Support imprimable en 3D (téléphone + carte USB) — à retourner pour l'écran vers l'avant |

## Matériel (résumé)

Roomba 616 · Samsung Galaxy S8+ · nacelle Feiyu SPG · carte USB-série FT232 (5 V) · fils Dupont mâle-femelle · câble USB-C <-> USB-C (données).
Détails et liens : [`docs/MATERIEL.md`](docs/MATERIEL.md).
