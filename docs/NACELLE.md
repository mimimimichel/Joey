# Nacelle Feiyu SPG — la tête de Joey

La nacelle 3 axes **Feiyu SPG** (premier modèle, 2016) tient le Galaxy S8+ et sert de **cou** à Joey :
il tourne et lève la tête sans bouger les roues, et l'image de la caméra reste stable quand il roule.

## Ce qu'on sait

- Téléphones acceptés : taille iPhone 7 Plus -> le S8+ (159,5 × 73,4 mm, 173 g) rentre.
- Rotation 360°, inclinaison 320°, roulis 320°. Vitesses : rotation 3 à 150°/s, inclinaison 2 à 75°/s.
- Batterie 22650 interne, ~8 h. À recharger à part (pas reliée au Roomba).
- **Pilotable depuis le téléphone** : l'app **Feiyu ON** la trouve en Bluetooth et la dirige (testé par Michael).
  Le protocole n'est pas public : enregistré puis **décodé** (voir `hardware/gimbal-spg/PROTOCOLE.md`).
- Travaux voisins (autre modèle, Mini 3D, en série et non en Bluetooth) :
  [scanlime](https://scanlime.org/2017/06/feiyu-gimbal-serial-hack-scanlime021/),
  [heroineworshiper/gimbal](https://github.com/heroineworshiper/gimbal). Les trames peuvent ressembler.

## Étape 1 — enregistrer les échanges Bluetooth (sur le S8+)

1. Réglages -> À propos du téléphone -> Informations sur le logiciel -> taper 7 fois sur « Numéro de version »
   (active les options de développement).
2. Réglages -> Options de développement -> activer **« Journal HCI Bluetooth »** (Bluetooth HCI snoop log).
3. Éteindre puis rallumer le Bluetooth (le journal démarre à ce moment).
4. Allumer la nacelle, ouvrir **Feiyu ON**, se connecter. Puis faire la séance ci-dessous, **lentement**,
   en notant l'heure de début (montre à la seconde) :

   | Temps | Action |
   |---|---|
   | 0 s | Connecté, ne rien toucher |
   | 10 s | Joystick **à gauche** à fond, 3 s, puis lâcher |
   | 20 s | Joystick **à droite** à fond, 3 s, puis lâcher |
   | 30 s | Joystick **en haut** à fond, 3 s, puis lâcher |
   | 40 s | Joystick **en bas** à fond, 3 s, puis lâcher |
   | 50 s | Joystick **à gauche à moitié**, 3 s (pour voir comment la vitesse est codée) |
   | 60 s | Recentrer (bouton ou double appui, selon l'app) |
   | 70 s | Changer de mode (suivi / verrouillé) si l'app le permet, chaque mode 10 s |
   | après | Déconnecter dans l'app, puis éteindre la nacelle |

   Recommencer une deuxième fois la même séance aide beaucoup à confirmer les trames.
5. Récupérer le journal : Options de développement -> **« Créer un rapport de bug »** -> rapport complet.
   Le fichier `.zip` contient `FS/data/log/bt/btsnoop_hci.log` (ou un nom proche).
   Le mettre sur Google Drive (ou dans le dépôt, dossier `captures/`) et le donner à Claude.
6. Désactiver ensuite le journal HCI (il grossit et enregistre tout le Bluetooth).

## Étape 2 — décoder (Claude) ✅ fait le 2026-10-21

Résultat : [`hardware/gimbal-spg/PROTOCOLE.md`](../hardware/gimbal-spg/PROTOCOLE.md) (joystick, recentrage, angles,
CRC) + `SpgProtocol.kt` prêt pour l'app + `spg.py` pour vérifier une capture.

Méthode utilisée :

- Lire le journal (Wireshark / `btsnoop` en Python), trouver l'appareil Feiyu, son service et ses caractéristiques
  BLE (UUID), puis les écritures envoyées pendant chaque action.
- Repérer : en-tête, numéro de commande, valeurs des axes (vitesse ou angle), somme de contrôle.
- Noter aussi ce que la nacelle renvoie (angles actuels, batterie, mode) : utile pour savoir où Joey regarde.

## Étape 3 — dans l'app Joey

- `robot/Gimbal.kt` : connexion BLE (Android `BluetoothGatt`), commandes `tourner(vitesse)`, `incliner(vitesse)`,
  `recentrer()`, `mode(...)`, lecture des angles et de la batterie. Plus un `FakeGimbal` pour les tests.
- Outils pour Gemini : `regarder(direction)` (gauche, droite, haut, bas, devant), `regarder_autour` (balayage
  lent, la vision cherche pendant ce temps).
- Le suivi des personnes passe d'abord par la nacelle (rapide, silencieux), puis par les roues si la tête
  est trop tournée (au-delà de ~60°, le corps se réaligne et la tête revient au centre).

## Montage sur le Roomba (à dessiner)

- Nouveau support imprimé qui serre le **manche** de la SPG, **bas et centré** (le Roomba freine sec :
  il ne faut pas que ça bascule). Mesurer le diamètre du manche et la hauteur totale avec le téléphone.
- Le support actuel (`hardware/roomba-mount/`) garde son bac pour la carte FT232.
- **Câble USB-C** téléphone <-> carte FT232 : souple, assez long, avec une boucle de mou, pour ne pas freiner
  les moteurs quand la tête tourne. Vérifier à la main que la tête fait tout son tour sans tirer.
  Piste plus tard : liaison sans fil vers le Roomba (module Bluetooth série) pour supprimer ce câble.
- Joey devient plus haut : il ne passera plus sous les meubles (le mode aspirateur classique reste possible
  en enlevant la nacelle).

## Plan B (si le décodage échoue)

- La nacelle en mode « suivi horizontal » sans pilotage : Joey tourne la tête avec ses roues, la nacelle lisse.
- Inclinaison par un petit servomoteur.
