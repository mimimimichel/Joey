package com.joey.app.life

/**
 * Ce que Joey fait de lui-même quand on ne lui demande rien (mode autonome) : une seule décision, prise toutes les
 * quelques secondes, par ordre de priorité (comme un arbre de comportements : la première règle qui s'applique gagne).
 *
 * 1. Rien si le mode est coupé, si on lui parle ou s'il est déjà occupé.
 * 2. Se recharger : batterie des roues ou du téléphone basse, ou la nuit, loin de sa base. Après un accostage raté, il
 *    attend un peu avant de réessayer (sinon il tournait en rond toute la nuit).
 * 3. Saluer quelqu'un qui vient d'arriver.
 * 4. Sur sa base en charge : se reposer jusqu'à 80 %.
 * 5. Explorer : tant que sa carte a des frontières, par séances de 3 min ; une fois la maison connue, une petite ronde
 *    par heure.
 *
 * Pur (sans Android) : testé seul ; MainViewModel l'exécute.
 */
object AutonomyPolicy {
    data class Snapshot(
        val now: Long,
        val hour: Int,
        val autonomyOn: Boolean,
        /** Occupé (outil en cours, suis-moi, danse, il parle). */
        val busy: Boolean,
        /** Dernière fois qu'une personne lui a parlé (ms). */
        val lastTalkAt: Long,
        val roombaBattery: Int?,
        val phoneBattery: Int,
        val phoneCharging: Boolean,
        val onBase: Boolean,
        val charging: Boolean,
        /** Sa carte part de sa base (il sait y retourner par la carte). */
        val mapFromBase: Boolean,
        /** Quelqu'un est en vue depuis [personSince] (ms), ou 0. */
        val personSince: Long,
        val lastGreetAt: Long,
        val lastFaceSeenAt: Long,
        val lastExploreAt: Long,
        /** Il reste des frontières atteignables sur sa carte (la maison n'est pas toute connue). */
        val frontiersLeft: Boolean,
        /** Dernier accostage raté (ms), ou 0. */
        val lastDockFailAt: Long,
        val dockFailures: Int,
    )

    sealed class Action {
        object None : Action()
        data class Charge(val reason: String) : Action()
        object Greet : Action()
        data class Explore(val seconds: Int, val reason: String) : Action()
        data class Rest(val reason: String) : Action()
    }

    /** Batterie des roues en dessous de laquelle il rentre. */
    const val ROOMBA_LOW = 25
    const val PHONE_LOW = 20
    /** Calme la nuit : de 22 h à 8 h il reste sur sa base. */
    fun night(hour: Int) = hour >= 22 || hour < 8

    /** Attente après un accostage raté : 5 min, puis 15, puis 45… (au plus 2 h). */
    fun dockBackoffMs(failures: Int): Long = if (failures <= 0) 0L else minOf(2 * 3600_000L, 5 * 60_000L * Math.pow(3.0, (failures - 1).toDouble()).toLong())

    fun decide(s: Snapshot): Action {
        if (!s.autonomyOn) return Action.None
        if (s.busy) return Action.None
        val talking = s.now - s.lastTalkAt < 120_000
        val canTryDock = s.now - s.lastDockFailAt >= dockBackoffMs(s.dockFailures)
        val rb = s.roombaBattery ?: 100
        // Se recharger passe avant tout le reste, même si on lui parle (il le dira) : une batterie vide le laisse en plan.
        if (!s.onBase && canTryDock) {
            if (rb < ROOMBA_LOW) return Action.Charge("la batterie de mes roues est à $rb %")
            if (s.phoneBattery in 0 until PHONE_LOW && !s.phoneCharging) return Action.Charge("la batterie de mon téléphone est à ${s.phoneBattery} %")
        }
        if (talking) return Action.None
        if (night(s.hour)) {
            return if (!s.onBase && canTryDock && s.mapFromBase) Action.Charge("c'est la nuit") else Action.None
        }
        // Quelqu'un vient d'arriver (vu depuis moins de 30 s) : il va le saluer, au plus une fois toutes les 20 min.
        if (s.personSince != 0L && s.now - s.personSince < 30_000 && s.now - s.lastGreetAt > 20 * 60_000L) return Action.Greet
        if (s.onBase && s.charging && rb < 80) return Action.Rest("je me recharge ($rb %)")
        // Explorer : assez de batterie partout, personne autour depuis 10 min.
        val enough = rb >= 40 && (s.phoneBattery !in 0 until 35 || s.phoneCharging)
        val alone = s.now - s.lastFaceSeenAt > 10 * 60_000L
        if (enough && alone) {
            if (s.frontiersLeft && s.now - s.lastExploreAt > 10 * 60_000L) return Action.Explore(180, "ma carte n'est pas finie")
            if (!s.frontiersLeft && s.now - s.lastExploreAt > 60 * 60_000L) return Action.Explore(75, "petite ronde")
        }
        return Action.None
    }
}
