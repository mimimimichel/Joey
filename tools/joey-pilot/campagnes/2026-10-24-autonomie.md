# Campagne du 24/10/2026 : valider l'autonomie (v0.69)

Michael à côté de Joey. S8+ sur la nacelle, Roomba branché, base posée contre un mur, chemin dégagé autour.
Chaque essai : ce qu'on fait, ce qu'on attend, ce qui s'est passé (feed + images), verdict. Au moindre doute : `cmd stop`.

## 0. Préparation
- `state` : version 0.69, roues branchées, batteries > 50 %. Noter `autonomy` : s'il est à true, `cmd autonomy` le coupe
  (sinon il part explorer de lui-même entre deux essais).
- `frame` : où est-il ? (l'image arrive fraîche depuis la 0.65 ; écran noir pendant les essais).

## 1. Capteurs du Roomba (sans rouler)
- `tool capteurs '{"secondes":5}'` face au vide : valeurs de repos des 6 détecteurs de proximité.
- Michael met la main à 5 cm puis 15 cm devant le côté gauche, le centre, le côté droit : les valeurs montent-elles ?
  (répond à : le Roomba 616 a-t-il des détecteurs de proximité utilisables ?)
- Joey à 0,5 m, 1 m et 2 m devant sa base : `tool capteurs` -> codes infrarouges vus (161, 164, 168, 172, 173 ?).

## 2. Profondeur (MiDaS) en roulant
- Premier `tool avancer '{"metres":0.3}'` : le feed doit dire « 🧠 Profondeur (MiDaS) : N ms par image » (attendu 200-500).
- Face à une chaise à ~1 m (comme le 24/10 à 00:25) : `tool avancer '{"metres":1.5}'`.
  Attendu : « obstacle vu devant », arrêté AVANT le pied (le 24/10 : touché après 0,76 m).
- Sur le tapis berbère, voie libre : `tool avancer '{"metres":1.0}'`. Attendu : il avance tout du long (pas d'obstacle
  imaginaire). Sinon : `cmd profondeur on=0`, refaire l'essai 2 pour comparer, et le noter.

## 3. Passage entre deux chaises (aller-retour)
- Deux chaises écartées de ~50 cm, Joey à 1,5 m devant. `tool aller_a` n'existe que pour un lieu nommé : utiliser
  `tool explorer '{"secondes":60}'` en direction du passage, puis `tool revenir`.
  Attendu : il passe sans toucher, ne s'engage jamais sous une assise, et revient (le 24/10 : « pas de chemin »).

## 4. Exploration et pièces
- `tool explorer '{"secondes":180}'`, puis `map` : la carte grandit ; les zones (pièces) s'affichent sous la carte.
- `tool revenir`.

## 5. Accostage
- Joey à 2-3 m de sa base (carte partie de la base : le poser d'abord sur la base, puis l'en faire sortir).
- `cmd dock`, puis `state` toutes les 20 s : « sur sa base » en moins de 5 min ?

## 6. Mode autonome (Michael hors de la vue de Joey 10 min, sinon il vient le saluer)
- `cmd autonomy` (le rallume). Attendu dans les 10 min : « 🤖 Autonome : j'explore 180 s (ma carte n'est pas finie) »,
  exploration, relevé d'une pièce nouvelle (« 🗺 Pièce nouvelle : je la relève »), puis retour vérifié à la base
  (« 🏠 Je suis sur ma base »).
- Michael revient dans le champ : il doit aller le saluer (« je vais voir qui est là »), au plus une fois par 20 min.

## Rapport
`rapports/2026-10-24-HHMM.md`, images à côté ; une `demander_amelioration` par problème n'est plus utile (le
développement se fait sur ce PC) : corriger directement dans `~/projets/joey-dev`.
