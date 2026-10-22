# Installation automatique de Joey (PC toujours allumé)

À chaque nouvelle version poussée sur la branche, le PC la compile et l'installe sur le téléphone **par le Wi-Fi**,
puis relance Joey. Plus besoin de télécharger et d'installer les APK à la main.

Le PC compile lui-même (les clés restent sur le PC, jamais dans le dépôt) avec la même clé de signature que les
versions envoyées jusqu'ici : la mise à jour se fait par-dessus, sans rien perdre (mémoire, visages, réglages).

## 1. Sur le PC (Linux)

Tu peux demander à Claude Code sur le PC : « lis tools/auto-install/README.md et installe l'installation
automatique de Joey », ou faire à la main :

```bash
git clone https://github.com/mimimimichel/Joey.git ~/Joey   # (dépôt privé : connecte-toi avec `gh auth login` avant)
cd ~/Joey && git checkout claude/roomba-joey-upgrade-o87jto
tools/auto-install/setup.sh
```

Puis remplis `~/.config/joey/env` :

```
GEMINI_API_KEY=ta clé Gemini
SUPABASE_URL=https://dquuddtrokuudnbtrtel.supabase.co
SUPABASE_KEY=la clé publique du projet Supabase « joey » (sb_publishable_…)
```

et relance : `systemctl --user restart joey-auto-install`.

## 2. Sur le téléphone (une seule fois)

**Galaxy S10 (Android 12) : sans fil**
1. Paramètres > À propos du téléphone > Informations sur le logiciel : tape 7 fois sur « Numéro de version »
   (le mode développeur s'active).
2. Paramètres > Options de développement > **Débogage sans fil** : active-le (même Wi-Fi que le PC).
3. Touche « Débogage sans fil » > **Associer l'appareil avec un code d'association** : un code et une adresse
   s'affichent. Sur le PC : `~/android-sdk/platform-tools/adb pair ADRESSE:PORT CODE`.
4. C'est tout : le script retrouve le téléphone tout seul ensuite.

Après un redémarrage du téléphone, Android coupe parfois le débogage sans fil : il suffit de le réactiver (pas
besoin de réassocier).

**Galaxy S8+ (Android 9)** : pas de débogage sans fil intégré. Une fois après chaque redémarrage du téléphone,
branche-le en USB au PC et lance `adb tcpip 5555`, débranche, puis mets `JOEY_PHONE=IP_DU_TELEPHONE:5555` dans
`~/.config/joey/env`.

## Suivre

- `tail -f ~/.local/state/joey/auto-install.log`
- Avec `NTFY_TOPIC=…` dans `~/.config/joey/env`, tu reçois une notification à chaque installation.
- Arrêter : `systemctl --user stop joey-auto-install` ; désactiver : `systemctl --user disable joey-auto-install`.

À savoir : l'installation relance Joey (la conversation en cours est coupée). Le PC vérifie toutes les 2 minutes.
