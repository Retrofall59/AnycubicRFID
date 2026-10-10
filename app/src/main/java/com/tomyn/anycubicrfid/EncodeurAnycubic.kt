package com.tomyn.anycubicrfid

/**
 * Construit un dump Anycubic pret a cloner/creer, a partir de champs saisis a la main
 * (CreationTagActivity) ou importes (ImportBaseDonneesActivity) - l'inverse de DecodeurAnycubic.
 * Ajoute le 10/10/2026 a la demande de Tomyn, meme principe que EncodeurElegoo.
 *
 * Construit un tableau de 128 octets (pages 0x00 a 0x1F - voir ClonageAnycubic.TAILLE_MIN_DUMP_SOURCE),
 * en recopiant telles quelles les zones communes aux 8 echantillons reels (Capability Container,
 * en-tete "protocole", marque "AC") et en ecrivant les champs connus - voir DecodeurAnycubic.kt
 * pour le detail des offsets.
 *
 * Champs volontairement laisses tels quels plutot que devines :
 *   - pages 0x00-0x02 (UID) : jamais copiees par le clonage (ClonageAnycubic ne touche qu'a partir
 *     de 0x04, encore plus prudent que ElegooRFID qui part de 0x03), leur valeur ici n'a donc
 *     aucune importance.
 *   - page 0x1D (29, offset 116) : valeur constante "37 00 41 00" sur les 8 echantillons reels,
 *     role inconnu - recopiee telle quelle plutot que devinee (meme logique que les pages
 *     inexpliquees d'ElegooRFID, voir ClonageElegoo.kt).
 *   - suffixe de reference "-107" : constant sur les 8 echantillons, role inconnu (numero de lot ?
 *     de version ? pas de quoi se prononcer avec seulement 8 exemples, tous recents) - recopie
 *     a l'identique pour un tag cree par l'appli.
 *
 * Contrairement a ElegooRFID, la couleur n'a PAS besoin d'une table de correspondance pour etre
 * creee correctement : elle s'ecrit en clair en RGB a la page 20 (voir ecrireCouleur), donc une
 * couleur jamais vue auparavant (absente de TableCouleursAnycubic) est tout aussi valide a
 * l'ecriture qu'une des 8 deja connues.
 */
object EncodeurAnycubic {

    // Capability Container (page 3) - identique sur tous les echantillons reels.
    private val CC = byteArrayOf(0xE1.toByte(), 0x10, 0x12, 0x00)

    // En-tete "protocole" (page 4) - identique sur tous les echantillons reels, role exact inconnu
    // (probablement un identifiant de version du format, pas une donnee produit).
    private val ENTETE = byteArrayOf(0x7B, 0x00, 0x65, 0x00)

    // Marque (pages 10-13, 16 octets) - "AC" + remplissage, identique sur les 8 echantillons reels.
    private val MARQUE = byteArrayOf(0x41, 0x43, 0x00, 0x00)

    // Suffixe constant de la reference produit (voir le commentaire en tete de fichier).
    private const val SUFFIXE_REFERENCE = "-107"

    // Page 0x1D (29) - valeur constante inexpliquee, voir le commentaire en tete de fichier.
    private val PAGE_29_INCONNUE = byteArrayOf(0x37, 0x00, 0x41, 0x00)

    private fun ecrireU16(d: ByteArray, off: Int, valeur: Int) {
        d[off] = (valeur and 0xFF).toByte()
        d[off + 1] = ((valeur shr 8) and 0xFF).toByte()
    }

    private fun ecrireU32(d: ByteArray, off: Int, valeur: Long) {
        for (i in 0..3) d[off + i] = ((valeur shr (i * 8)) and 0xFF).toByte()
    }

    private fun ecrireAscii(d: ByteArray, off: Int, texte: String, longueurMax: Int) {
        val octets = texte.toByteArray(Charsets.US_ASCII)
        val n = minOf(octets.size, longueurMax)
        octets.copyInto(d, off, 0, n)
        // Le reste de la zone reste a zero (ByteArray initialise a zero par defaut) - terminateur
        // null implicite, meme convention que DecodeurAnycubic.texteAscii.
    }

    /** Ecrit la couleur brute (page 20) au format ABGR - voir DecodeurAnycubic.couleurAbgr.
     * @param couleurHex 6 caracteres hexadecimaux (RRGGBB), sans '#'. */
    private fun ecrireCouleur(d: ByteArray, off: Int, couleurHex: String) {
        val rouge = couleurHex.substring(0, 2).toInt(16).toByte()
        val vert = couleurHex.substring(2, 4).toInt(16).toByte()
        val bleu = couleurHex.substring(4, 6).toInt(16).toByte()
        d[off] = 0xFF.toByte() // alpha, constant sur les 8 echantillons reels
        d[off + 1] = bleu
        d[off + 2] = vert
        d[off + 3] = rouge
    }

    /**
     * @param codeCouleur 2 lettres (ex. "PO") pour la reference produit - purement indicatif
     *   desormais (voir le commentaire en tete de fichier) : un code invente pour une couleur
     *   personnalisee n'empeche pas l'app de relire la bonne couleur, lue en RGB brut.
     * @param couleurHex 6 caracteres hexadecimaux (RRGGBB), sans '#'.
     * @param diametreMm ex. 1.75 - converti en centiemes de mm (meme unite que le format du tag).
     * @return un dump de 128 octets, pret pour ClonageAnycubic.pagesAEcrire (clonage) ou
     *         DecodeurAnycubic.decoder (verification/apercu avant clonage).
     */
    fun construireDump(
        matiere: String,
        codeCouleur: String,
        couleurHex: String,
        poidsGrammes: Int,
        diametreMm: Double,
        longueurMetres: Int,
        temperaturePlateau: Int,
        temperatureBuseDefaut: Int,
        temperatureBuseMin: Int,
        temperatureBuseMax: Int
    ): ByteArray {
        val d = ByteArray(128)

        CC.copyInto(d, 3 * 4)
        ENTETE.copyInto(d, 4 * 4)

        val reference = "AHPL${codeCouleur.uppercase()}$SUFFIXE_REFERENCE"
        ecrireAscii(d, 5 * 4, reference, 12) // pages 5,6,7

        MARQUE.copyInto(d, 10 * 4) // pages 10-13 (3 pages de remplissage suivent, deja a zero)

        ecrireAscii(d, 15 * 4, matiere, 4) // page 15 (+ pages 16-18 de remplissage, deja a zero)

        ecrireCouleur(d, 20 * 4, couleurHex) // page 20

        ecrireU16(d, 23 * 4, temperaturePlateau)
        ecrireU16(d, 23 * 4 + 2, temperatureBuseDefaut)
        ecrireU16(d, 24 * 4, temperatureBuseMin)
        ecrireU16(d, 24 * 4 + 2, temperatureBuseMax)

        PAGE_29_INCONNUE.copyInto(d, 29 * 4)

        ecrireU16(d, 30 * 4, Math.round(diametreMm * 100).toInt())
        ecrireU16(d, 30 * 4 + 2, longueurMetres)
        ecrireU32(d, 31 * 4, poidsGrammes.toLong())

        return d
    }
}
