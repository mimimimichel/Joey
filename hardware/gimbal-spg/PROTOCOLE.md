# Protocole Bluetooth de la Feiyu SPG (décodé)

Source : capture HCI du 2026-10-21 (Galaxy S10 + app Feiyu ON, `com.feiyutech.gimbalCamera`).
Les échanges avec la nacelle sont dans [`captures/session1_2026-10-21.txt`](captures/session1_2026-10-21.txt).
**Les 5 111 trames de la capture passent le contrôle** : `python3 spg.py verifier captures/session1_2026-10-21.txt`.

## Liaison

- Bluetooth basse consommation (BLE). Nom annoncé : **`FY_Spg_82`** (adresse aléatoire statique `F3:18:93:00:30:82`).
- Pas d'appairage, pas de code : l'app se connecte, active les notifications, et envoie.
- Écriture **sans réponse** sur la caractéristique de handle `0x002F` ; notifications sur `0x002C`
  (son descripteur `0x002D` reçoit `01 00`). Les UUID ne sont pas dans la capture (le téléphone les avait en cache) :
  dans Joey, prendre le service non standard qui a une caractéristique « écriture sans réponse » et une « notification ».
  Le code les affichera au premier essai pour qu'on les fige.

## Trame

```
A5 5A | dest | cmd | n | n octets de données | CRC bas | CRC haut
```

- `dest` : `00` (et `02`) = vers la nacelle ; `03` = réponse de la nacelle.
- CRC-16 **XMODEM** (polynôme 0x1021, départ 0x0000) sur `dest … données` (tout sauf A5 5A).
- Il existe aussi des trames `55 AA …` (CRC-16 départ 0xFFFF) : une seule, `55AA0110011FF861`, répétée 10 fois à la
  connexion par l'app. Rôle inconnu, pas nécessaire a priori.

## Commandes trouvées

| Trame (données) | Sens | Effet | Certitude |
|---|---|---|---|
| `00 10 05` `FF` pan(int16) tilt(int16) | → nacelle | **Joystick** : vitesse de rotation. pan + = droite, tilt + = haut. Envoyé ~20×/s pendant le geste, puis `0,0` plusieurs fois | Sûr (mouvement vu dans les angles) |
| `00 40 02` `05 02` | → nacelle | **Recentrer** : la tête revient tout droit en moins d'1 s | Probable |
| `00 40 01` `06` | → nacelle | Interrogation régulière ; réponse `03 40 05` `06 01 00 28 4B` (modèle / version ?) | Sûr pour l'échange, sens inconnu |
| `00 40 02` `04 01` | → nacelle | Envoyé en même temps que l'ouverture de l'appareil photo de l'app ; pas d'effet visible | Inconnu |
| `00/02 06 01` `id` | → nacelle | **Lecture d'un réglage** ; réponse `03 06 02` valeur(int16). `71` → 100 (batterie % ?), `72` → 20, `66` → 1 (mode ?), `7F` → 160 | Échange sûr, sens à deviner |

## Ce que la nacelle envoie

| Trame | Fréquence | Contenu |
|---|---|---|
| `03 10 07` `01` tilt roulis pan (int16, en 1/100 de degré) | ~10×/s | **Angles de la tête.** Tête droite : tilt ≈ ±180 (repère retourné), roulis ≈ 0. Pan dérive lentement (~0,3°/s) : pas de boussole |
| `00 10 05` `FF` pan tilt | ~4×/s | **Joystick physique du manche** (même format). Vu jusqu'à ±314 |

Vitesses mesurées avec le joystick physique : pan −314 → ~−40°/s, pan +256 → ~+30°/s, tilt +254 → ~+17°/s,
tilt −314 → ~−20°/s. Petites valeurs (~100) : presque rien (courbe progressive).

## À vérifier au prochain essai (sur le S8+, avec Joey)

1. Envoyer `joystick(200, 0)` pendant 2 s puis `STOP` : la tête tourne à droite et s'arrête ? Faut-il répéter la trame
   (sinon s'arrête-t-elle seule) ?
2. `RECENTER` recentre bien ?
3. Lire `0x71` en vidant la batterie : c'est le pourcentage ?
4. Noter les UUID du service et des deux caractéristiques.
