package com.tomyn.anycubicrfid

/**
 * Decodeur du format des tags RFID Anycubic (NTAG213, lecture libre sans chiffrement,
 * mais format proprietaire : la case memoire a bien l'octet magique NDEF E1 en page 3,
 * mais les donnees qui suivent ne forment PAS un message NDEF valide).
 *
 * Structure etablie a partir de 8 vrais dumps de bobines PLA Basic (Purple, Silver, Pink,
 * Orange, Beige, Gold, Cyan, Blue) fournis par Tomyn, en comparant page par page : seules les
 * pages UID (0-2), la page couleur (0x06) et une page de controle non expliquee (0x14) changent
 * d'une bobine a l'autre ; tout le reste (matiere, poids, temperatures, reference produit) est
 * identique quelle que soit la couleur, ce qui confirme que ce sont bien des champs fixes pour
 * "PLA Basic 1kg", lus en direct sur le tag plutot que codes en dur ici.
 *
 * Chaque page fait 4 octets. Offsets en octets = numero de page * 4.
 */
object DecodeurAnycubic {

    data class InfoBobine(
        val uid: String?,
        val referenceProduit: String?,   // ex: "AHPLPO-107", lue telle quelle sur le tag
        val codeCouleur: String?,        // ex: "PO", les 2 lettres au sein de la reference
        val matiere: String?,            // ex: "PLA", lue en clair
        val poidsGrammes: Long?,
        val temperaturePlateau: Int?,
        val temperatureBuseDefaut: Int?,
        val temperatureBuseMin: Int?,
        val temperatureBuseMax: Int?
    )

    private fun u16le(d: ByteArray, off: Int): Int? {
        if (off + 1 >= d.size) return null
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32le(d: ByteArray, off: Int): Long? {
        if (off + 3 >= d.size) return null
        var v = 0L
        for (i in 0..3) v = v or ((d[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun texteAscii(d: ByteArray, off: Int, longueur: Int): String? {
        if (off + longueur > d.size) return null
        val brut = d.copyOfRange(off, off + longueur)
        val fin = brut.indexOf(0).let { if (it == -1) brut.size else it }
        if (fin == 0) return null
        return String(brut, 0, fin, Charsets.US_ASCII)
    }

    /**
     * @param dump octets bruts du tag, page 0 en premier (4 octets/page). Doit couvrir au moins
     *             jusqu'a la page 0x1F (128 octets) pour obtenir tous les champs ; un dump plus
     *             court renvoie les champs qu'il peut et laisse les autres a null (jamais d'erreur).
     */
    fun decoder(dump: ByteArray): InfoBobine {
        val uid = if (dump.size >= 8) {
            (dump.copyOfRange(0, 3) + dump.copyOfRange(4, 8)).joinToString(":") { "%02X".format(it) }
        } else null

        val reference = texteAscii(dump, 20, 12)  // pages 5,6,7 : "AHPL" + code couleur + "-107"
        val codeCouleur = texteAscii(dump, 24, 2) // page 6, 2 premiers octets

        return InfoBobine(
            uid = uid,
            referenceProduit = reference,
            codeCouleur = codeCouleur,
            matiere = texteAscii(dump, 60, 3),     // page 0x0F
            poidsGrammes = u32le(dump, 124),       // page 0x1F
            temperaturePlateau = u16le(dump, 92),      // page 0x17, premier u16
            temperatureBuseDefaut = u16le(dump, 94),   // page 0x17, second u16
            temperatureBuseMin = u16le(dump, 96),      // page 0x18, premier u16
            temperatureBuseMax = u16le(dump, 98)       // page 0x18, second u16
        )
    }
}
