package com.joey.app.senses

/** Ce que Joey voit : la dernière image de la caméra, en JPEG base64 (null si rien). */
fun interface Eyes { fun captureJpegBase64(): String? }
