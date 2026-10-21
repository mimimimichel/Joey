# Joey — un petit robot compagnon

Un Roomba (série 600) + un vieux téléphone Android posé dessus = un petit robot de maison avec un visage,
une voix, une personnalité et de la mémoire. Inspiré de Joey, le robot de *Beneath a Steel Sky* :
une petite intelligence avec du caractère, qui commente, se souvient, et se déplace dans la maison.

> État : **démarrage**. Rien n'est encore codé ici. Le pilote du Roomba, la voix et la vision existent déjà
> (et sont testés) dans le projet drone [`mimimimichel/AiDrone`](https://github.com/mimimimichel/AiDrone) :
> une copie est dans [`reuse/aidrone/`](reuse/aidrone/) pour démarrer.

## L'idée

- **Un visage** : l'écran du téléphone, tourné vers l'avant, affiche des yeux animés (écoute, réfléchit, parle, étonné, boude…).
  La **caméra avant** (côté écran) sert à voir : il regarde les gens qui lui parlent.
- **Une voix et une personnalité** : conversation en direct avec Gemini, un caractère à lui (pince-sans-rire),
  une mémoire de la maison et des gens.
- **Il vit dans la maison** : vient quand on l'appelle, suit, salue quand quelqu'un rentre, fait des rondes,
  raconte ce qu'il a vu, va se recharger seul sur sa base.
- **Il s'exprime aussi avec le Roomba** : petites mélodies, voyants, petits mouvements (joie, hésitation).
- **À distance** : on peut lui parler et voir par ses yeux depuis son téléphone, hors de la maison (Tailscale).

## Documents

| Fichier | Contenu |
|---|---|
| [`docs/BRIEF.md`](docs/BRIEF.md) | **À lire en premier** (et à donner à Claude au début d'une conversation) : décisions, contraintes, architecture, étapes |
| [`docs/MATERIEL.md`](docs/MATERIEL.md) | Ce qu'il faut acheter, câblage de la prise du Roomba, alimentation, sécurité |
| [`docs/PERSONNALITE.md`](docs/PERSONNALITE.md) | Premier jet du caractère de Joey, de sa voix et de ses comportements |
| [`reuse/aidrone/`](reuse/aidrone/) | Code repris du projet drone (pilote Roomba testé, Gemini en direct, vision sur le téléphone…) |
| [`hardware/roomba-mount/`](hardware/roomba-mount/) | Support imprimable en 3D (téléphone + carte USB) — à retourner pour l'écran vers l'avant |

## Matériel (résumé)

Roomba 616 · Samsung Galaxy S8+ · carte USB-série FT232 (5 V) · fils Dupont mâle-femelle · câble USB-C <-> USB-C (données).
Détails et liens : [`docs/MATERIEL.md`](docs/MATERIEL.md).
