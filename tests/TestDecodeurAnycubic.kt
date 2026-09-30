import com.tomyn.anycubicrfid.DecodeurAnycubic
import com.tomyn.anycubicrfid.TableCouleursAnycubic
import java.io.File

/**
 * Test du decodeur sur les 8 VRAIS dumps de bobines Anycubic PLA Basic fournis par Tomyn
 * (Purple, Silver, Pink, Orange, Beige, Gold, Cyan, Blue). Verifie que :
 *  - les champs communs (matiere, poids, temperatures, reference) sont identiques sur les 8,
 *    ce qui confirme qu'ils sont bien lus en direct et non devines ;
 *  - le code couleur lu correspond a la vraie couleur de chaque bobine ;
 *  - un code couleur inconnu ne renvoie jamais un nom invente.
 *
 *   kotlinc ../app/src/main/java/com/tomyn/anycubicrfid/DecodeurAnycubic.kt \
 *           ../app/src/main/java/com/tomyn/anycubicrfid/TableCouleursAnycubic.kt \
 *           TestDecodeurAnycubic.kt -include-runtime -d test.jar
 *   java -jar test.jar
 */
var echecs = 0
fun check(nom: String, ok: Boolean, detail: String = "") {
    println((if (ok) "  OK   " else "  ECHEC") + " $nom" + if (detail.isNotEmpty()) "  [$detail]" else "")
    if (!ok) echecs++
}

fun main() {
    val attendus = mapOf(
        "purple" to "Purple", "silver" to "Texture Silver", "pink" to "Pink", "orange" to "Orange",
        "beige" to "Beige", "gold" to "Gold", "cyan" to "Cyan", "blue" to "Blue"
    )
    val codesAttendus = mapOf(
        "purple" to "PO", "silver" to "SL", "pink" to "SP", "orange" to "VO",
        "beige" to "LB", "gold" to "GD", "cyan" to "CY", "blue" to "DB"
    )

    for ((fichier, nomCouleur) in attendus) {
        val dump = File("dumps/$fichier.bin").readBytes()
        val info = DecodeurAnycubic.decoder(dump)

        check("$fichier : matiere = PLA", info.matiere == "PLA", "obtenu=${info.matiere}")
        check("$fichier : poids = 1000 g", info.poidsGrammes == 1000L, "obtenu=${info.poidsGrammes}")
        check("$fichier : plateau = 50 C", info.temperaturePlateau == 50, "obtenu=${info.temperaturePlateau}")
        check("$fichier : buse defaut = 200 C", info.temperatureBuseDefaut == 200, "obtenu=${info.temperatureBuseDefaut}")
        check("$fichier : buse min = 190 C", info.temperatureBuseMin == 190, "obtenu=${info.temperatureBuseMin}")
        check("$fichier : buse max = 230 C", info.temperatureBuseMax == 230, "obtenu=${info.temperatureBuseMax}")
        check("$fichier : code couleur = ${codesAttendus[fichier]}", info.codeCouleur == codesAttendus[fichier], "obtenu=${info.codeCouleur}")
        check("$fichier : reference = AHPL${codesAttendus[fichier]}-107", info.referenceProduit == "AHPL${codesAttendus[fichier]}-107", "obtenu=${info.referenceProduit}")

        val couleur = TableCouleursAnycubic.trouver(info.codeCouleur)
        check("$fichier : nom couleur = $nomCouleur", couleur?.nom == nomCouleur, "obtenu=${couleur?.nom}")
    }

    // Code couleur inconnu : jamais de nom invente
    val inconnu = TableCouleursAnycubic.trouver("ZZ")
    check("code inconnu -> null (pas de nom invente)", inconnu == null)

    // Dump tronque : ne doit jamais planter, renvoie ce qu'il peut
    val tronque = DecodeurAnycubic.decoder(File("dumps/purple.bin").readBytes().copyOfRange(0, 32))
    check("dump tronque : matiere absente proprement (pas d'erreur)", tronque.matiere == null)
    check("dump tronque : reference encore lisible (tient dans les 32 premiers octets)", tronque.referenceProduit != null)

    println(if (echecs == 0) "\n=> TOUT PASSE" else "\n=> $echecs ECHEC(S)")
    if (echecs > 0) System.exit(1)
}
