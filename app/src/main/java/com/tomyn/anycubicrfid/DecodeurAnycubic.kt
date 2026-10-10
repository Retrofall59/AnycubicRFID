package com.tomyn.anycubicrfid

/**
 * Decodeur du format des tags RFID Anycubic (NTAG213, lecture libre sans chiffrement,
 * mais format proprietaire : la case memoire a bien l'octet magique NDEF E1 en page 3,
 * mais les donnees qui suivent ne forment PAS un message NDEF valide).
 *
 * Structure etablie a partir de 8 vrais dumps de bobines PLA Basic (Purple, Silver, Pink,
 * Orange, Beige, Gold, Cyan, Blue) fournis par Tomyn, en comparant page par page.
 *
 * Mise a jour le 10/10/2026 : la page 0x14 (page 20, octets 80-83), jusque-la non expliquee,
 * est en realite la COULEUR BRUTE de la bobine, au format ABGR ([Alpha][Bleu][Vert][Rouge]) -
 * confirme octet par octet sur les 8 dumps reels, exact match avec les hex officiels deja dans
 * TableCouleursAnycubic (ex. Purple : octets FF CD 6D 6A -> #6A6DCD, exactement la valeur
 * officielle). Ce n'est PAS un checksum : juste la couleur, encodee une seconde fois en brut en
 * plus du code 2 lettres dans la reference produit (page 6). Decouverte initialement suggeree
 * par un document communautaire (projet DnG-Crafts/ACE-RFID sur GitHub, pour l'Anycubic ACE Pro -
 * un autre produit Anycubic, avec une pagination differente) puis VERIFIEE ici directement sur
 * les dumps reels de Tomyn plutot que recopiee telle quelle, le document ayant des incoherences
 * internes sur la pagination exacte.
 *
 * Consequence concrete : la couleur peut desormais etre lue en direct pour N'IMPORTE QUELLE
 * bobine, pas seulement les 8 deja repertoriees dans TableCouleursAnycubic - "couleur inconnue"
 * devient l'exception (tag abime/format different) plutot que la norme pour toute couleur non
 * encore vue.
 *
 * Egalement decouvert a la meme occasion (pages 10-13, 16 octets) : un champ "marque" ASCII
 * ("AC" sur les 8 echantillons, suivi de remplissage) - expose ici a toutes fins utiles mais sans
 * usage particulier pour l'instant (toujours "AC" chez Anycubic lui-meme).
 *
 * Chaque page fait 4 octets. Offsets en octets = numero de page * 4.
 */
object DecodeurAnycubic {

    data class InfoBobine(
        val uid: String?,
        val referenceProduit: String?,   // ex: "AHPLPO-107", lue telle quelle sur le tag
        val codeCouleur: String?,        // ex: "PO", les 2 lettres au sein de la reference
        val marque: String?,             // ex: "AC" (page 10, 16 octets) - ajoute le 10/10/2026
        val matiere: String?,            // ex: "PLA", lue en clair
        // Couleur brute lue directement sur le tag (page 20, format ABGR) - ajoute le 10/10/2026,
        // voir le commentaire en tete de fichier. "RRGGBB" sans '#', null si le dump est trop
        // court pour couvrir cette page.
        val couleurHexBrute: String?,
        val poidsGrammes: Long?,
        val diametreMm: Double?,         // page 30 (offset 120) - ajoute le 10/10/2026
        val longueurMetres: Int?,        // page 30 (offset 122) - ajoute le 10/10/2026
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

    /** Couleur brute au format ABGR (page 20, offset 80) -> "RRGGBB" sans '#'. Voir le commentaire
     * en tete de fichier. Null si le dump est trop court. */
    private fun couleurAbgr(d: ByteArray, off: Int): String? {
        if (off + 3 >= d.size) return null
        val bleu = d[off + 1]
        val vert = d[off + 2]
        val rouge = d[off + 3]
        return "%02X%02X%02X".format(rouge, vert, bleu)
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
        val diametreCentiemes = u16le(dump, 120)  // page 30, premier u16

        return InfoBobine(
            uid = uid,
            referenceProduit = reference,
            codeCouleur = codeCouleur,
            marque = texteAscii(dump, 40, 16),         // pages 10-13
            // 4 octets (toute la page 0x0F), pas 3 : corrige le 10/10/2026 - invisible sur les 8
            // echantillons reels ("PLA\0" tient en 3 caracteres utiles) mais tronquait a tort une
            // matiere de 4 lettres ("PETG") a la lecture d'un tag cree par EncodeurAnycubic, qui
            // ecrit bien les 4 octets de cette page (voir EncodeurAnycubic.construireDump).
            matiere = texteAscii(dump, 60, 4),         // page 0x0F
            couleurHexBrute = couleurAbgr(dump, 80),   // page 0x14 (20)
            poidsGrammes = u32le(dump, 124),           // page 0x1F
            diametreMm = diametreCentiemes?.let { it / 100.0 },
            longueurMetres = u16le(dump, 122),         // page 30, second u16
            temperaturePlateau = u16le(dump, 92),      // page 0x17, premier u16
            temperatureBuseDefaut = u16le(dump, 94),   // page 0x17, second u16
            temperatureBuseMin = u16le(dump, 96),      // page 0x18, premier u16
            temperatureBuseMax = u16le(dump, 98)       // page 0x18, second u16
        )
    }
}
