# Matériel

## Liste

| Pièce | Rôle | Remarques |
|---|---|---|
| Roomba **616** (série 600) | Le corps : roues, pare-chocs, capteurs de vide, haut-parleur, base de charge | Prise de commande « Open Interface » sous le capot du dessus (se déclipse) |
| Samsung **Galaxy S8+** (Android 9) | Le cerveau : caméra, micro, haut-parleur, écran (visage), IA | Accepte les appareils USB (OTG). Désactiver la mise en veille et l'optimisation de batterie pour l'app |
| Carte USB-série **FT232RL**, USB-C, réglable 3,3 V / 5 V | Fait parler le téléphone au Roomba | Choisie : SEENGREAT FT232RL USB-C (interrupteur 5V/3V3, broche RTS sortie). **Interrupteur sur 5V** |
| Fils **Dupont mâle-femelle** | Bout femelle sur la carte, bout mâle enfoncé dans la prise ronde du Roomba | Pas de soudure. Un point de colle chaude ou du scotch pour qu'ils tiennent |
| Câble **USB-C <-> USB-C qui transmet les données** | Carte <-> téléphone | Un câble « charge seule » ne marche pas |
| Nacelle **Feiyu SPG** (déjà là) | Le cou : tourner et lever la tête, image stable | Batterie à elle (~8 h). Voir `NACELLE.md` |
| (plus tard) convertisseur 8-35 V -> USB 5 V 3 A + fusible 2 A | Alimenter le téléphone par la batterie du Roomba | La tension monte vers ~21 V sur la base : prendre une entrée qui monte au moins à 30 V |

Liens trouvés (à vérifier, les annonces changent) :
- Câble mini-DIN 7 broches à fils nus (option plus propre) : [Adafruit #2438](https://www.adafruit.com/product/2438)
- Convertisseur 12/24 V -> USB 5 V 3 A : [Amazon.fr B07Q8KGQ3B](https://www.amazon.fr/dp/B07Q8KGQ3B)
- Fils Dupont : [ELEGOO 120 fils](https://www.amazon.fr/dp/B01JD5WCG2)
- Fusibles + porte-fusibles : [QLOUNI](https://www.amazon.fr/dp/B076CFS2M6)
- Accès à distance : [Tailscale pour Android](https://tailscale.com/docs/install/android)

## Câblage de la prise du Roomba (mini-DIN 7 broches)

| Carte FT232 | Prise du Roomba |
|---|---|
| TXD | broche 3 (réception du Roomba) |
| RXD | broche 4 (émission du Roomba) |
| RTS (ou DTR) | broche 5 (BRC : réveil) |
| GND | broche 6 (ou 7) |
| VCCIO, CTS | rien |

⚠️ **Broches 1 et 2 = batterie du Roomba en direct (~14 V, jusqu'à ~21 V sur la base). Jamais sur la carte.**
Elles ne servent qu'à l'entrée d'un convertisseur (avec fusible) pour alimenter le téléphone.

Le schéma de la prise est dans la doc officielle :
[iRobot Roomba 600 / Create 2 Open Interface Spec](https://cdn.hackaday.io/files/1835247851890816/2021-04-13_iRobot_Roomba_600_Open_Interface_Spec-3.pdf)
(page « Serial Port »). Le plus sûr pour s'orienter : un multimètre — les deux trous côte à côte à ~14-16 V sont 1 et 2.

## Liaison

- 115 200 bauds (série 600), 57 600 pour certains 500 : le pilote essaie les deux.
- Réveil : impulsion basse sur BRC (broche 5). En mode passif il s'endort après 5 min : une impulsion par minute le garde joignable.
- Mode « sécurité » (safe) pour rouler : il s'arrête seul au bord d'une marche ou s'il est soulevé (il repasse alors en passif).
- Sur sa base, le mode sécurité le rend passif aussitôt : le pilote recule d'abord en mode « complet » (full) puis passe en sécurité.

## Support imprimé

[`hardware/roomba-mount/`](../hardware/roomba-mount/) : support pour le S8+ (paysage) et la carte FT232, à coller au Velcro devant
le bouton CLEAN. **Il a été dessiné pour l'écran vers l'arrière** (caméra arrière vers l'avant, projet drone).
Pour Joey (écran = visage vers l'avant, caméra avant), il faut retourner le berceau : voir les paramètres du `.scad`.
À mesurer avant d'imprimer : la carte FT232 (supposée 52 × 26 mm), la coque du téléphone, la zone plate sur le Roomba.

## Nacelle Feiyu SPG

Le téléphone est posé sur la nacelle, qui sert de cou à Joey. Pilotage Bluetooth, montage, câble et plan B :
voir [`NACELLE.md`](NACELLE.md).
