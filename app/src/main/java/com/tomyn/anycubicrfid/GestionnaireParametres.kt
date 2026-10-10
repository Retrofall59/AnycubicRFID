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

    // Sources de la base de filaments JSON (ajoute le 10/10/2026, voir ImportBaseDonneesActivity) :
    // les Uri des fichiers choisis par Tomyn, memorisees pour ne pas avoir a les reselectionner a
    // chaque lancement. Separateur "||" plutot qu'une virgule, meme convention que ElegooRFID.
    private const val CLE_SOURCES_BASE_JSON = "sources_base_json"
    private const val SEPARATEUR_SOURCES_BASE_JSON = "||"

    fun lireSourcesBaseJson(context: Context): List<String> {
        val brut = context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
            .getString(CLE_SOURCES_BASE_JSON, "") ?: ""
        return if (brut.isEmpty()) emptyList() else brut.split(SEPARATEUR_SOURCES_BASE_JSON).filter { it.isNotBlank() }
    }

    fun ecrireSourcesBaseJson(context: Context, uris: List<String>) {
        context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).edit()
            .putString(CLE_SOURCES_BASE_JSON, uris.joinToString(SEPARATEUR_SOURCES_BASE_JSON))
            .apply()
    }
}
