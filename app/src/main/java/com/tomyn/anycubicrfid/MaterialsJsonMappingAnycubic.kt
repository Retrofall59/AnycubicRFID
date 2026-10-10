package com.tomyn.anycubicrfid

/**
 * Rapproche le champ texte libre "material" des fichiers JSON de base de filaments (ex. "PLA+",
 * "PETG-CF", "EASYASA"...) avec la liste fixe de matieres de CreationTagActivity.MATIERES. Ajoute
 * le 10/10/2026 a la demande de Tomyn - voir ImportBaseDonneesActivity.
 *
 * Plus simple que son equivalent ElegooRFID (MaterialsJsonMapping.kt) : le format Anycubic n'a pas
 * de sous-type fige par matiere (juste une chaine ASCII libre a la page 15, voir
 * DecodeurAnycubic/EncodeurAnycubic), donc rapprocher revient juste a trouver LA matiere de base
 * (PLA, PETG, ABS, TPU, ASA, PA, PC) - jamais a choisir un sous-type precis.
 *
 * Deux niveaux de resultat, pour rester honnete sur ce qui est fiable et ce qui est approximatif :
 *   - correspondance exacte : le texte JSON est EXACTEMENT une matiere connue (ex. "PETG", "TPU").
 *   - correspondance approximative : le texte JSON est une variante proche d'une matiere connue
 *     (ex. "PLA+", "ABS+MATTE", "EASYASA", "PETG-CF") -> matiere de base correcte, mais le detail
 *     ("+", "-CF", "EASY"...) est perdu - approximation signalee a l'ecran, jamais cachee.
 * Tout le reste (PVDF, PCPBT, GREENTEC, FLAX, PEARL, BIOFUSION, HTPET+, PC-ABS...) est laisse non
 * reconnu plutot que de deviner une matiere au hasard.
 */
object MaterialsJsonMappingAnycubic {

    data class Resultat(val matiere: String, val approximatif: Boolean)

    private val connues: Set<String> by lazy { CreationTagActivity.MATIERES.map { it.uppercase() }.toSet() }

    // Variante JSON -> matiere de base connue (toujours "approximatif" : le detail de la variante
    // n'a pas d'equivalent dans le champ ASCII libre d'Anycubic, voir le commentaire en tete de
    // fichier).
    private val aliasApproximatifs: Map<String, String> = mapOf(
        "PLA+" to "PLA", "PLAX" to "PLA", "HTPLA+" to "PLA", "SILK PLA" to "PLA", "PLA SILK" to "PLA",
        "ABS+" to "ABS", "ABS+MATTE" to "ABS", "ABS+GLOSS" to "ABS", "PC-ABS" to "ABS",
        "PETG-CF" to "PETG", "PETG-GF" to "PETG",
        "ASA-CF" to "ASA", "ASA-GF" to "ASA", "EASYASA" to "ASA",
        "TPU-85A" to "TPU", "TPU-90A" to "TPU", "TPU-95A" to "TPU", "TPU-98A" to "TPU",
        "PA6-CF" to "PA", "PA6-GF" to "PA", "PA12" to "PA", "NYLON" to "PA",
    )

    /** @return null si aucune correspondance, meme approximative, n'existe pour ce texte. */
    fun resoudre(materiauJson: String): Resultat? {
        val norm = materiauJson.trim().uppercase()
        if (norm in connues) return Resultat(norm, approximatif = false)
        val base = aliasApproximatifs[norm] ?: return null
        return Resultat(base, approximatif = true)
    }

    // Densites approximatives (g/cm3), utilisees UNIQUEMENT pour estimer la longueur de bobine
    // (champ absent des 36 fichiers JSON communautaires, contrairement au poids et au diametre -
    // voir FilamentDatabase.kt) : longueur = poids / (densite * aire_section). Valeurs usuelles de
    // l'industrie de l'impression 3D (sources : fiches techniques fabricants), pas mesurees par
    // Tomyn ni Claude - toujours affichee comme "estimee" a l'ecran (ImportBaseDonneesActivity),
    // jamais presentee comme une valeur certaine lue sur un tag reel.
    private val densitesGCm3: Map<String, Double> = mapOf(
        "PLA" to 1.24, "PETG" to 1.27, "ABS" to 1.04, "TPU" to 1.21,
        "ASA" to 1.07, "PA" to 1.15, "PC" to 1.20
    )

    /**
     * Estime la longueur de bobine en metres a partir du poids et du diametre - voir le
     * commentaire sur [densitesGCm3]. @return null si la matiere n'a pas de densite connue ici.
     */
    fun estimerLongueurMetres(matiere: String, poidsGrammes: Int, diametreMm: Double): Int? {
        val densite = densitesGCm3[matiere.uppercase()] ?: return null
        val rayonCm = (diametreMm / 10.0) / 2.0
        val aireCm2 = Math.PI * rayonCm * rayonCm
        val volumeCm3 = poidsGrammes / densite
        val longueurCm = volumeCm3 / aireCm2
        return Math.round(longueurCm / 100.0).toInt()
    }
}
