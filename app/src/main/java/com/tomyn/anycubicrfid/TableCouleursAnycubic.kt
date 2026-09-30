package com.tomyn.anycubicrfid

/**
 * Correspondance code couleur (2 lettres, lu sur le tag) -> nom + hex officiel.
 * Les hex viennent du site officiel store.anycubic.com (gamme PLA Basic), pas d'une base tierce.
 *
 * Etabli uniquement a partir de bobines reellement scannees (8 couleurs a ce jour) : PAS de
 * deduction ni d'extrapolation sur les codes non vus. Un code absent de cette table affiche
 * honnêtement "couleur inconnue" plutot qu'un nom invente - voir le cas Cherry Red de JAYO qui
 * donnait du noir dans une base tierce non verifiee, exactement ce qu'on veut eviter ici.
 *
 * Pour ajouter une couleur : scanner la bobine, lire le code dans DecodeurAnycubic.InfoBobine.codeCouleur,
 * verifier le nom exact sur l'etiquette, puis ajouter l'entree ici.
 */
object TableCouleursAnycubic {

    data class Couleur(val nom: String, val hex: String)

    val table = mapOf(
        "PO" to Couleur("Purple", "6A6DCD"),
        "SL" to Couleur("Texture Silver", "8A8D8F"),
        "SP" to Couleur("Pink", "FF8DA1"),
        "VO" to Couleur("Orange", "FF7F32"),
        "LB" to Couleur("Beige", "D4B996"),
        "GD" to Couleur("Gold", "FFB81C"),
        "CY" to Couleur("Cyan", "23A3C7"),
        "DB" to Couleur("Blue", "003594")
    )

    /** Ne renvoie jamais un nom invente : null si le code n'est pas (encore) dans la table. */
    fun trouver(codeCouleur: String?): Couleur? = codeCouleur?.let { table[it.uppercase()] }
}
