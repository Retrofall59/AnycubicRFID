package com.tomyn.anycubicrfid

import android.content.Context

object GestionnaireParametres {

    private const val FICHIER = "anycubicrfid_parametres"
    private const val CLE_VIBRATION = "vibration_fin_lecture"

    fun lireVibrationFinLecture(context: Context): Boolean =
        context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).getBoolean(CLE_VIBRATION, true)

    fun ecrireVibrationFinLecture(context: Context, active: Boolean) {
        context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).edit()
            .putBoolean(CLE_VIBRATION, active).apply()
    }
}
