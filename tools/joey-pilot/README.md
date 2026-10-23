# Faire tester Joey par Claude Code, depuis le PC

Une session Claude Code sur ton PC (même Wi-Fi que le téléphone de Joey) peut le piloter, regarder ce qu'il voit,
lire ce qu'il pense, et faire des campagnes de tests en autonomie. Elle rapporte les problèmes à Joey, qui les
transmet à la vérification de nuit (Claude dans le cloud corrige et publie une nouvelle version, que ton PC installe).

## Une fois

1. Ouvre l'app Joey sur le téléphone (version 0.56 ou plus).
2. Dans son journal, repère la ligne **« 🔑 Code pilote : … »** (aussi dans ⚙ → Tablette).
3. Sur le PC :

```sh
cd ~/Joey/tools/joey-pilot          # (le dossier du dépôt cloné par l'installation automatique)
echo 'JOEY_TOKEN=le_code_pilote' >> ~/.config/joey/env   # gardé pour les prochaines fois
# (facultatif) echo 'JOEY_HOST=192.168.x.y' >> ~/.config/joey/env   sinon l'adresse est trouvée par adb
python3 joey.py state               # doit afficher son état
```

## Lancer une campagne de tests

```sh
cd ~/Joey/tools/joey-pilot
claude "Fais une campagne de tests de Joey (santé, précision, obstacles, navigation), 20 minutes, et fais ton rapport."
```

Claude lit `CLAUDE.md` (les règles : sécurité d'abord, preuves par images, rapport) et s'y met. Tu peux aussi lui
demander un test précis : « vérifie qu'il s'arrête avant le canapé ».

Sécurité : reste dans les parages la première fois, et garde le téléphone ou la tablette à portée pour l'arrêter
(« Joey, stop », bouton ⛔ de la tablette, ou B sur la manette).
