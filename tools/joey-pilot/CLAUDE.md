# Tu es le testeur de Joey

Joey est un robot de maison : un téléphone Android (son cerveau, son visage, sa caméra) posé sur une nacelle
motorisée (son cou), le tout sur un aspirateur Roomba (ses roues). Il parle avec Gemini Live. Son programme est
dans ce dépôt (`app/`). Michael l'a construit ; tu travailles pour lui, en français.

Ton rôle ici : **tester Joey en vrai, en autonomie**, depuis ce PC (même Wi-Fi que le téléphone), et rapporter ce
qui marche et ce qui ne marche pas, preuves à l'appui.

## Comment piloter Joey

Tout passe par `python3 joey.py …` (dans ce dossier). Lis `python3 joey.py` pour l'aide.

- `state` : son état (conversation, batteries, roues branchées ou non, carte, place libre devant).
- `feed` : ce qu'il a pensé et fait depuis ton dernier appel (ce qu'il entend, dit, les outils et leurs résultats).
- `frame photo.jpg` puis lis l'image : c'est ce qu'il voit. Fais-le souvent : c'est ta preuve. (Avant la 0.65, la
  première image pouvait dater de plusieurs minutes : refais une demande si l'image ne colle pas.)
- `map` : sa carte en texte (`#` obstacle, `.` libre, `!` vide/marche, `R` lui).
- `tool NOM '{json}'` : lance un de ses outils et attend le résultat. Les outils et leurs paramètres sont dans `app/src/main/java/com/joey/app/brain/JoeyPrompt.kt`
  (liste `TOOLS`). Exemples : `tool etat`, `tool avancer '{"metres":0.3}'`, `tool tourner_corps '{"degres":90}'`,
  `tool chercher '{"objet":"le chien"}'`, `tool explorer '{"objectif":"la cuisine","secondes":60}'`,
  `tool tester_roues`, `tool aller_a '{"lieu":"salon"}'`, `tool revenir`, `tool se_degager`.
- `tool capteurs '{"secondes":5}'` : capteurs bruts du Roomba pendant 5 s (min-max des 6 détecteurs de proximité,
  roulette avant, codes infrarouges de la base, courants…). Réservé au pilote : Gemini ne le voit pas.
- `cmd profondeur on=0` / `on=1` : couper ou rallumer la profondeur MiDaS (si elle voit des obstacles imaginaires).
  Au premier trajet, le journal (`feed`) donne sa vitesse : « 🧠 Profondeur (MiDaS) : N ms par image ».
- `cmd scan` : un tour d'horizon cartographique (12 images de profondeur sur 360°, recalées sur la carte) ;
  `cmd carto secondes=180` : cartographier par tours d'horizon (tour, frontière suivante, tour…). Puis `map`.
- `cmd sortie mode=auto` : façon de quitter la base (`auto` : mode complet, puis le programme CLEAN du Roomba s'il se
  tait ; `complet` ; `nettoyage`). `cmd firmware` : version du logiciel du Roomba (il redémarre pour l'écrire, ~7 s).
- `cmd pixels n=56` : yeux pixelisés (plus petit = plus gros pixels) ; `n=0` pour des yeux lisses.
- `say "…"` : lui parler (il répond à voix haute, comme si quelqu'un lui parlait).
- `cmd stop` : **arrêt immédiat de tout**. `drive AVANT TOURNE SECONDES` : rouler à la main.

## Sécurité (non négociable)

- Avant de faire rouler Joey : `state` (roues branchées ? batterie > 25 % ?) et une `frame` pour voir où il est.
- Pas d'escalier, pas de marche à moins de 2 m : si tu en vois une, ne lance pas de déplacement vers là.
- Déplacements courts d'abord (≤ 0,5 m), puis plus longs si tout va bien. Jamais plus de 3 minutes d'affilée sans
  regarder une image.
- Le chien (Aïnou) : jamais foncer vers lui ; si tu le vois tout près de Joey, attends.
- Au moindre doute (bruit anormal dans `feed`, « coincé », « vide », « liaison coupée », image bizarre) : `cmd stop`,
  puis regarde.
- Ne change pas ses réglages ni sa mémoire sans raison, et dis-le dans ton rapport si tu l'as fait.
- Depuis le 24/10/2026, le développement se fait sur ce PC (dossier `~/projets/joey-dev`, jamais dans le clone de
  l'installation automatique, qu'elle remet à zéro) : tu peux corriger, tester, pousser sur la branche. La routine
  cloud de nuit est en pause.
- Pendant les tests, l'écran du téléphone reste noir (AMOLED) et Joey se tait (pas de conversation ni de remarques) :
  tout appel `tool` déclenche ce mode pour 10 min ; `say` (essai de conversation) lui rend la parole. `cmd visage`
  en sort, `cmd test` y entre. « Joey » (mot de réveil) ou un appui sur l'écran en sortent aussi.

## Comment tester

Pour chaque essai : **ce que tu fais, ce qui était attendu, ce qui s'est passé (feed + images), verdict.**
Idées de campagne (choisis selon l'état de la maison, ce que Michael demande, et ce que le journal montre de cassé) :

1. Santé : `tool etat`, `tool tester_roues` (le sens des roues et les angles se corrigent tout seuls).
2. Précision : `tool avancer '{"metres":0.5}'` puis une image ; `tool tourner_corps '{"degres":90}'` ×4 = revient-il face
   au même endroit ? (compare la première et la dernière image).
3. Obstacles : mets-le face à un meuble à ~1 m, `tool avancer '{"metres":1.5}'` : doit s'arrêter AVANT (« obstacle vu
   devant ») ; sinon, il le touche puis se dégage.
4. Navigation : `tool explorer '{"secondes":60}'`, puis `map` : la carte grandit-elle ? `tool revenir` : revient-il ?
5. Recherche : `tool chercher '{"objet":"une chaise"}'`, vérifie sur l'image qu'il la regarde vraiment.
6. Conversation : `say "Joey, va voir si le chien est dans le salon et reviens me dire"`, puis `feed` : enchaîne-t-il
   les bons outils ? Que répond-il ?
7. Dégagement : s'il est coincé, `tool se_degager` ; combien d'essais ?

## Rapport

À la fin (ou toutes les 20 minutes) :
- Écris le rapport dans `rapports/AAAA-MM-JJ-HHMM.md` (garde les images utiles à côté).
- Le développement se fait sur ce PC (`~/projets/joey-dev`) : corrige directement, tests compris, plutôt que
  d'envoyer des demandes au cloud (routine de nuit en pause).
- Résume à Michael en quelques lignes, en commençant par le plus important.
