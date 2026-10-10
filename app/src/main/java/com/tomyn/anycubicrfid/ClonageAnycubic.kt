package com.tomyn.anycubicrfid

/**
 * Logique de clonage/effacement d'un tag Anycubic (hors acces NFC lui-meme, qui reste dans
 * MainActivity pour rester coherent avec lireTag). Ajoute le 10/10/2026 a la demande de Tomyn,
 * meme principe que ClonageElegoo.kt.
 *
 * CHOIX DE SECURITE - zone ecrite :
 *
 * Les 8 dumps reels fournis par Tomyn font chacun 180 octets (45 pages, 0x00-0x2C). Comparaison
 * page par page des 8 echantillons :
 *   - Pages 0x00-0x02 : UID (figees en usine, jamais reinscriptibles sur un tag standard)
 *   - Page  0x03      : Capability Container (E1 10 12 00) - standard NTAG213, identique partout
 *   - Pages 0x04-0x1F : zone produit (en-tete, reference, marque, matiere, couleur, temperatures,
 *                       diametre/longueur, poids - voir DecodeurAnycubic/EncodeurAnycubic)
 *   - Pages 0x20-0x2C : au-dela, valeurs IDENTIQUES sur les 8 echantillons avec des octets a des
 *                       positions normalement RFUI qui ne le sont pas (0xBD en page 0x28, meme
 *                       motif que ce qu'avait trouve ElegooRFID a la MEME page 0x28 sur ses propres
 *                       echantillons) - quasi certainement la configuration de puce standard
 *                       NTAG213 (verrouillage dynamique, compteur), pas de la donnee produit.
 *
 * DECISION, par prudence maximale (encore plus conservatrice que ElegooRFID, qui commence a
 * 0x03) : le clonage/l'effacement ne touchent QUE les pages 0x04 a 0x1F - jamais le Capability
 * Container (0x03, deja standard sur un tag neuf, aucune raison de le reecrire) ni les pages
 * 0x20+ (configuration de puce, meme motif suspect qu'ElegooRFID a identifie au meme endroit).
 */
object ClonageAnycubic {

    const val PREMIERE_PAGE_DONNEES = 0x04
    const val DERNIERE_PAGE_DONNEES = 0x1F

    /** Nombre d'octets du dump source necessaires pour pouvoir cloner (jusqu'a la fin de 0x1F incluse). */
    const val TAILLE_MIN_DUMP_SOURCE = (DERNIERE_PAGE_DONNEES + 1) * 4

    /**
     * Decoupe le dump source en pages pretes a ecrire (numero de page, 4 octets), de
     * PREMIERE_PAGE_DONNEES a DERNIERE_PAGE_DONNEES inclus.
     * @return liste vide si le dump est trop court pour couvrir toute la plage.
     */
    fun pagesAEcrire(dumpSource: ByteArray): List<Pair<Int, ByteArray>> {
        if (dumpSource.size < TAILLE_MIN_DUMP_SOURCE) return emptyList()
        val resultat = mutableListOf<Pair<Int, ByteArray>>()
        for (page in PREMIERE_PAGE_DONNEES..DERNIERE_PAGE_DONNEES) {
            val offset = page * 4
            resultat.add(page to dumpSource.copyOfRange(offset, offset + 4))
        }
        return resultat
    }

    /** Compare la zone clonee (0x04-0x1F) d'un dump relu sur le tag cible avec le dump source. */
    fun zoneCloneeIdentique(dumpSource: ByteArray, dumpCibleRelu: ByteArray): Boolean {
        if (dumpSource.size < TAILLE_MIN_DUMP_SOURCE || dumpCibleRelu.size < TAILLE_MIN_DUMP_SOURCE) return false
        val debut = PREMIERE_PAGE_DONNEES * 4
        val fin = (DERNIERE_PAGE_DONNEES + 1) * 4
        return dumpSource.copyOfRange(debut, fin).contentEquals(dumpCibleRelu.copyOfRange(debut, fin))
    }

    val OCTETS_PAGE_VIDE: ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    /** Numeros de pages a ecrire a zero pour un effacement complet de la zone de donnees. */
    fun pagesAEffacer(): List<Int> = (PREMIERE_PAGE_DONNEES..DERNIERE_PAGE_DONNEES).toList()

    /** Confirme qu'un dump relu a bien ete entierement remis a zero sur la zone effacee. */
    fun zoneEffacee(dumpCibleRelu: ByteArray): Boolean {
        if (dumpCibleRelu.size < TAILLE_MIN_DUMP_SOURCE) return false
        val debut = PREMIERE_PAGE_DONNEES * 4
        val fin = (DERNIERE_PAGE_DONNEES + 1) * 4
        return dumpCibleRelu.copyOfRange(debut, fin).all { it == 0.toByte() }
    }

    /** Liste les pages (0x04-0x1F) qui ne sont PAS entierement a zero dans un dump relu apres
     * effacement - pour un message d'erreur precis plutot qu'un "effacement incomplet" generique. */
    fun pagesNonEffacees(dumpCibleRelu: ByteArray): List<Int> {
        if (dumpCibleRelu.size < TAILLE_MIN_DUMP_SOURCE) return emptyList()
        val resultat = mutableListOf<Int>()
        for (page in PREMIERE_PAGE_DONNEES..DERNIERE_PAGE_DONNEES) {
            val offset = page * 4
            if (!dumpCibleRelu.copyOfRange(offset, offset + 4).all { it == 0.toByte() }) resultat.add(page)
        }
        return resultat
    }

    /** Octets de verrouillage dynamique (page 0x28, les 2 premiers octets) en hexa, si le dump les
     * couvre - diagnostic uniquement, jamais ecrit. */
    fun verrousDynamiquesHex(dump: ByteArray): String? {
        val offset = 0x28 * 4
        if (dump.size < offset + 2) return null
        return dump.copyOfRange(offset, offset + 2).joinToString(" ") { "%02X".format(it) }
    }
}
