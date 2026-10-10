package com.tomyn.anycubicrfid

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.Vibrator
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class EtiquetteEnAttente(
        val matiere: String,
        val nomCouleur: String?,
        val couleurArgb: Int?,
        val codeHexa: String?,
        val poidsGrammes: Long?,
        val tempBuseMin: Int?,
        val tempBuseMax: Int?,
        val tempPlateau: Int?,
        // Dump brut (pages 0x00-0x1F) en hexa, pour le QR code de reconsultation sur l'etiquette
        // imprimee - porte depuis ElegooRFID le 10/10/2026, voir dessinerQr/lienQrPourDump.
        val dumpHex: String?,
        // Origine de la bobine (ORIGINE_SCAN_NFC/ORIGINE_QR, voir ces constantes) - porte depuis
        // ElegooRFID : distingue un vrai scan NFC d'une simple consultation via QR (sans tag a
        // portee), pour ne pas les confondre sur l'etiquette imprimee.
        val origine: String
    )

    private lateinit var imgNfc: ImageView
    private lateinit var vuCouleur: View
    private lateinit var txtStatut: TextView
    private lateinit var layoutLignesInfo: LinearLayout
    private lateinit var btnImprimerEtiquette: Button
    private lateinit var btnReglagesNfc: Button
    private lateinit var btnCloner: Button
    private lateinit var btnEffacer: Button
    private lateinit var btnAnnulerClonage: Button
    private lateinit var nfcAdapter: NfcAdapter

    private val filesAttenteEtiquettes = mutableListOf<EtiquetteEnAttente>()
    private val dernieresLignesInfo = mutableListOf<Pair<Int, String>>()
    private var dernierDumpTexte: String = ""
    private var dernierResume: String = ""
    private var dernierScanReussi = false
    // Contenu en attente pendant que l'utilisateur choisit ou enregistrer le fichier (selecteur Android)
    private var contenuAExporter: String? = null

    // --- Clonage/effacement/creation (portes depuis ElegooRFID le 10/10/2026 a la demande de
    // Tomyn, voir ClonageAnycubic.kt pour le detail des plages de pages et le raisonnement de
    // securite) : dump brut de la derniere bobine lue, necessaire pour "Cloner" - non concerne
    // par une simple consultation via QR, voir afficherResultats/origine. ---
    private var dernierDumpBrut: ByteArray? = null
    private var enAttenteTagCible = false
    private var ecrasementConfirme = false
    private var enAttenteTagEffacement = false
    private var effacementConfirmeMalgreVerrou = false

    // --- Ecriture guidee par lot (import base ou creation en quantite) - meme principe que le
    // clonage par lot d'ElegooRFID, voir demarrerLotAvecValides. ---
    private var enAttenteTagLot = false
    private var lotAClone: List<Pair<String, ByteArray>> = emptyList()
    private var indexLotCourant = 0
    private data class ResultatLotLigne(val nom: String, val reussi: Boolean, val detail: String?)
    private val resultatsLot = mutableListOf<ResultatLotLigne>()

    // Origine de la derniere bobine affichee - distingue un vrai scan NFC d'une consultation via
    // QR (sans tag a portee) ou d'une creation manuelle, pour ne pas les confondre sur l'etiquette
    // imprimee (voir dessinerEtiquette).
    private var derniereOrigineBobine: String = ORIGINE_SCAN_NFC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contenuAExporter = savedInstanceState?.getString("contenuAExporter")
        setContentView(R.layout.activity_main)

        imgNfc = findViewById(R.id.imgNfc)
        vuCouleur = findViewById(R.id.vuCouleur)
        txtStatut = findViewById(R.id.txtStatut)
        layoutLignesInfo = findViewById(R.id.layoutLignesInfo)
        btnImprimerEtiquette = findViewById(R.id.btnImprimerEtiquette)
        btnReglagesNfc = findViewById(R.id.btnReglagesNfc)
        btnCloner = findViewById(R.id.btnCloner)
        btnEffacer = findViewById(R.id.btnEffacer)
        btnAnnulerClonage = findViewById(R.id.btnAnnulerClonage)

        btnImprimerEtiquette.setOnClickListener { imprimerEtiquette() }
        btnReglagesNfc.setOnClickListener { ouvrirReglagesNfc() }
        btnCloner.setOnClickListener { demarrerModeClonage() }
        btnEffacer.setOnClickListener { demarrerModeEffacement() }
        btnAnnulerClonage.setOnClickListener { if (enAttenteTagLot) annulerLot() else annulerModeAttente() }
        findViewById<Button>(R.id.btnCreerTag).setOnClickListener {
            startActivityForResult(Intent(this, CreationTagActivity::class.java), CODE_CREATION)
        }
        findViewById<Button>(R.id.btnExporter).setOnClickListener { exporterDump() }
        findViewById<Button>(R.id.btnCopier).setOnClickListener { copierResume() }
        findViewById<Button>(R.id.btnPartager).setOnClickListener { partagerResume() }
        findViewById<Button>(R.id.btnHistorique).setOnClickListener { afficherHistorique() }
        findViewById<Button>(R.id.btnRapportCompat).setOnClickListener { copierRapportCompatibilite() }
        findViewById<Button>(R.id.btnScannerQr).setOnClickListener {
            startActivityForResult(Intent(this, ScanQrActivity::class.java), CODE_SCAN_QR)
        }
        findViewById<Button>(R.id.btnImporterBase).setOnClickListener {
            startActivityForResult(Intent(this, ImportBaseDonneesActivity::class.java), CODE_IMPORT_BASE)
        }
        findViewById<ImageButton>(R.id.btnParametres).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        NfcAdapter.getDefaultAdapter(this)?.let { nfcAdapter = it }

        // Restauration apres rotation d'ecran : DOIT venir apres l'initialisation de tous les
        // boutons ci-dessus (mettreAJourBoutonImpression() en depend).
        restaurerAffichageResultat(savedInstanceState)

        traiterIntentEventuel(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::nfcAdapter.isInitialized) return

        if (!nfcAdapter.isEnabled) {
            btnReglagesNfc.visibility = View.VISIBLE
            txtStatut.text = "Le NFC est désactivé sur ce téléphone."
            return
        }
        if (btnReglagesNfc.visibility == View.VISIBLE) {
            btnReglagesNfc.visibility = View.GONE
            txtStatut.text = "Approche une bobine Anycubic du dos du téléphone..."
        }

        // Mode lecteur : des que l'appli est au premier plan, elle intercepte directement le tag,
        // sans repasser par le systeme de dispatch Android. Callback hors thread UI.
        nfcAdapter.enableReaderMode(
            this,
            NfcAdapter.ReaderCallback { tag -> runOnUiThread { lireTag(tag) } },
            NfcAdapter.FLAG_READER_NFC_A,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        if (::nfcAdapter.isInitialized) nfcAdapter.disableReaderMode(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        traiterIntentEventuel(intent)
    }

    private fun traiterIntentEventuel(intent: android.content.Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW) {
            traiterLienEtiquette(intent.data)
            return
        }
        if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED) return

        @Suppress("DEPRECATION")
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        lireTag(tag)
    }

    /**
     * Reception du lien "anycubicrfid://dump/<hex>" encode dans le QR des etiquettes (porte
     * depuis ElegooRFID le 10/10/2026, voir dessinerQr et l'intent-filter dans
     * AndroidManifest.xml) : permet de reconsulter une bobine a partir de l'etiquette papier
     * scannee par n'importe quelle appli de QR, SANS avoir le tag NFC a portee - utile si le tag
     * est abime/illisible mais l'etiquette existe encore.
     *
     * enregistrerHistorique=false : ce n'est pas une vraie lecture NFC, pas d'entree
     * d'historique pour une simple consultation.
     */
    private fun traiterLienEtiquette(uri: android.net.Uri?) {
        if (uri == null || uri.scheme != SCHEME_QR || uri.host != HOTE_QR) return
        val hex = uri.lastPathSegment
        if (hex.isNullOrBlank() || hex.length % 2 != 0 || !hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            Toast.makeText(this, "Étiquette illisible (lien invalide).", Toast.LENGTH_LONG).show()
            return
        }
        val dump = try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Étiquette illisible (lien invalide).", Toast.LENGTH_LONG).show()
            return
        }
        val info = DecodeurAnycubic.decoder(dump)
        if (info.matiere == null) {
            Toast.makeText(this, "Étiquette reconnue mais le contenu ne correspond pas à un dump Anycubic valide.", Toast.LENGTH_LONG).show()
            return
        }
        afficherResultats(info, dump, statutTexte = "Bobine (depuis l'étiquette, sans NFC)", enregistrerHistorique = false, origine = ORIGINE_QR)
    }

    private fun lireTag(tag: Tag) {
        if (enAttenteTagCible) {
            traiterTagCibleClonage(tag)
            return
        }
        if (enAttenteTagEffacement) {
            effacerTagCible(tag)
            return
        }
        if (enAttenteTagLot) {
            traiterTagLot(tag)
            return
        }

        layoutLignesInfo.removeAllViews()
        dernieresLignesInfo.clear()
        vuCouleur.visibility = View.GONE
        imgNfc.visibility = View.VISIBLE
        btnCloner.visibility = View.GONE
        txtStatut.text = "Lecture en cours..."

        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            txtStatut.text = "Ce tag n'est pas compatible NFC-A"
            dernierScanReussi = false
            return
        }

        try {
            nfcA.connect()

            // Lecture en commandes ISO14443-3A brutes (0x30 + numero de page -> 16 octets/4 pages)
            // plutot que via la classe MifareUltralight, qui depend d'une classification du tag
            // par Android qui a echoue sur un tag reel pourtant bien de ce type (test terrain).
            val dump = lireDumpBrut(nfcA, 28)
            val info = DecodeurAnycubic.decoder(dump)

            if (info.matiere == null) {
                txtStatut.text = "Tag lu, mais pas au format Anycubic reconnu"
                dernierScanReussi = false
                dernierDumpTexte = "UID : ${info.uid ?: "?"}\n\n--- DUMP BRUT ---\n${formaterDumpHex(dump)}"
                return
            }

            afficherResultats(info, dump)
        } catch (e: Exception) {
            txtStatut.text = "Erreur de lecture : ${e.message}"
            dernierScanReussi = false
        } finally {
            try { nfcA.close() } catch (e: Exception) { /* rien a faire */ }
        }
    }

    /** Lit les pages 0 a dernierePageIncluse (arrondi au multiple de 4 superieur) via READ (0x30). */
    private fun lireDumpBrut(nfcA: NfcA, dernierePageIncluse: Int): ByteArray {
        val tampon = ByteArrayOutputStream()
        var page = 0
        while (page <= dernierePageIncluse) {
            tampon.write(nfcA.transceive(byteArrayOf(0x30, page.toByte())))
            page += 4
        }
        return tampon.toByteArray()
    }

    /** Ecrit une page (commande WRITE, 0xA2) et VERIFIE la reponse (ACK 0x0A), avec quelques
     * tentatives en cas d'echec passager - meme logique qu'ElegooRFID, voir ClonageAnycubic.kt
     * pour pourquoi ignorer cette reponse serait risque (ecriture silencieusement incomplete). */
    private fun ecrirePageAvecVerif(nfcA: NfcA, page: Int, octets: ByteArray): Boolean {
        repeat(3) {
            try {
                val reponse = nfcA.transceive(
                    byteArrayOf(0xA2.toByte(), page.toByte(), octets[0], octets[1], octets[2], octets[3])
                )
                if (reponse.size == 1 && reponse[0] == 0x0A.toByte()) {
                    try { Thread.sleep(10) } catch (e: InterruptedException) { /* rien a faire */ }
                    return true
                }
            } catch (e: Exception) {
                // Tag eloigne un instant pendant l'ecriture : on retente avant d'abandonner cette page.
            }
        }
        return false
    }

    // ============================== CLONAGE / EFFACEMENT ==============================
    // Portes depuis ElegooRFID le 10/10/2026, memes principes - voir ClonageAnycubic.kt pour le
    // detail des plages de pages et le raisonnement de securite (pourquoi on ne touche jamais aux
    // pages 0x20+, config de puce).

    private fun demarrerModeClonage() {
        val source = dernierDumpBrut
        if (source == null || source.size < ClonageAnycubic.TAILLE_MIN_DUMP_SOURCE) {
            Toast.makeText(this, "Scanne d'abord une bobine Anycubic valide avant de cloner.", Toast.LENGTH_SHORT).show()
            return
        }
        enAttenteTagCible = true
        btnCloner.visibility = View.GONE
        btnEffacer.visibility = View.GONE
        btnAnnulerClonage.visibility = View.VISIBLE
        btnAnnulerClonage.text = "Annuler le clonage"
        layoutLignesInfo.removeAllViews()
        dernieresLignesInfo.clear()
        vuCouleur.visibility = View.GONE
        imgNfc.visibility = View.VISIBLE
        txtStatut.text = "Approche maintenant la bobine VIERGE à écrire (NTAG213)..."
    }

    private fun demarrerModeEffacement() {
        AlertDialog.Builder(this)
            .setTitle("Effacer un tag")
            .setMessage("Le prochain tag approché sera entièrement effacé (données produit remises à zéro), que ce soit une bobine Anycubic, un clone, ou un tag de test. Irréversible. Continuer ?")
            .setPositiveButton("Approcher un tag") { _, _ ->
                enAttenteTagEffacement = true
                effacementConfirmeMalgreVerrou = false
                btnCloner.visibility = View.GONE
                btnEffacer.visibility = View.GONE
                btnAnnulerClonage.visibility = View.VISIBLE
                btnAnnulerClonage.text = "Annuler l'effacement"
                layoutLignesInfo.removeAllViews()
                dernieresLignesInfo.clear()
                vuCouleur.visibility = View.GONE
                imgNfc.visibility = View.VISIBLE
                txtStatut.text = "Approche maintenant le tag à effacer..."
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun effacerTagCible(tag: Tag) {
        if (effacementConfirmeMalgreVerrou) {
            effacementConfirmeMalgreVerrou = false
            effectuerEffacement(tag)
            return
        }

        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            Toast.makeText(this, "Ce tag n'est pas compatible NFC-A, essaie un autre tag.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            nfcA.connect()
            val dumpAvant = lireDumpBrut(nfcA, 0x28)
            nfcA.close()
            val verrousDyn = ClonageAnycubic.verrousDynamiquesHex(dumpAvant)
            val verrouDetecte = verrousDyn != null && verrousDyn != "00 00"
            if (verrouDetecte) {
                runOnUiThread {
                    txtStatut.text = "Verrou détecté - confirmation demandée..."
                    AlertDialog.Builder(this)
                        .setTitle("Ce tag semble verrouillé")
                        .setMessage(
                            "Verrou dynamique (0x28) : ${verrousDyn ?: "?"}.\n\n" +
                                "L'effacement risque d'échouer sur une ou plusieurs pages à cause de ce verrou. Continuer quand même ?"
                        )
                        .setPositiveButton("Effacer quand même") { _, _ ->
                            effacementConfirmeMalgreVerrou = true
                            txtStatut.text = "Confirmé : rapproche à nouveau le tag pour l'effacer..."
                        }
                        .setNegativeButton("Annuler") { _, _ -> annulerModeAttente() }
                        .setOnCancelListener { annulerModeAttente() }
                        .show()
                }
                return
            }
        } catch (e: Exception) {
            try { nfcA.close() } catch (e2: Exception) { /* rien a faire */ }
            runOnUiThread { txtStatut.text = "Erreur de lecture avant effacement : ${e.message}" }
            return
        }

        effectuerEffacement(tag)
    }

    private fun effectuerEffacement(tag: Tag) {
        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            Toast.makeText(this, "Ce tag n'est pas compatible NFC-A, essaie un autre tag.", Toast.LENGTH_SHORT).show()
            return
        }

        runOnUiThread { txtStatut.text = "Effacement en cours, ne retire pas le tag..." }

        try {
            nfcA.connect()
            var pageEnEchec: Int? = null
            for (page in ClonageAnycubic.pagesAEffacer()) {
                if (!ecrirePageAvecVerif(nfcA, page, ClonageAnycubic.OCTETS_PAGE_VIDE)) {
                    pageEnEchec = page
                    break
                }
            }
            val dumpRelu = lireDumpBrut(nfcA, 0x28)
            nfcA.close()

            enAttenteTagEffacement = false
            val reussi = pageEnEchec == null && ClonageAnycubic.zoneEffacee(dumpRelu)
            runOnUiThread {
                btnAnnulerClonage.visibility = View.GONE
                btnEffacer.visibility = View.VISIBLE
                if (dernierScanReussi) btnCloner.visibility = View.VISIBLE
                txtStatut.text = when {
                    reussi -> "Tag effacé et vérifié ✓ — prêt pour un nouveau clonage"
                    pageEnEchec != null -> "Écriture interrompue (page 0x%02X non confirmée) — repose le tag bien à plat sans le bouger et réessaie.".format(pageEnEchec)
                    else -> {
                        val pagesRestantes = ClonageAnycubic.pagesNonEffacees(dumpRelu)
                        val listePages = pagesRestantes.joinToString(", ") { "0x%02X".format(it) }
                        val verrousDyn = ClonageAnycubic.verrousDynamiquesHex(dumpRelu)
                        "Écriture confirmée par le tag (ACK) mais relecture non vide : page(s) $listePages encore non nulle(s)" +
                            (verrousDyn?.let { " — verrou dynamique (0x28) : $it" } ?: "") +
                            ". Réessaie ; si ça persiste sur la même page, elle est peut-être verrouillée."
                    }
                }
            }
        } catch (e: Exception) {
            try { nfcA.close() } catch (e2: Exception) { /* rien a faire */ }
            enAttenteTagEffacement = false
            runOnUiThread {
                btnAnnulerClonage.visibility = View.GONE
                btnEffacer.visibility = View.VISIBLE
                if (dernierScanReussi) btnCloner.visibility = View.VISIBLE
                txtStatut.text = "Erreur d'effacement : ${e.message} — le tag est peut-être verrouillé ou n'est pas un NTAG213."
            }
        }
    }

    private fun annulerModeAttente() {
        val effacementEnCours = enAttenteTagEffacement
        enAttenteTagCible = false
        enAttenteTagEffacement = false
        ecrasementConfirme = false
        effacementConfirmeMalgreVerrou = false
        btnAnnulerClonage.visibility = View.GONE
        btnEffacer.visibility = View.VISIBLE
        if (dernierScanReussi) btnCloner.visibility = View.VISIBLE
        txtStatut.text = if (effacementEnCours) {
            "Effacement annulé. Approche une bobine Anycubic du dos du téléphone..."
        } else {
            "Clonage annulé. Approche une bobine Anycubic du dos du téléphone..."
        }
    }

    private fun traiterTagCibleClonage(tag: Tag) {
        val dumpSource = dernierDumpBrut
        if (dumpSource == null || dumpSource.size < ClonageAnycubic.TAILLE_MIN_DUMP_SOURCE) {
            annulerModeAttente()
            Toast.makeText(this, "Le modèle source a été perdu, relance le clonage depuis une nouvelle lecture.", Toast.LENGTH_LONG).show()
            return
        }

        if (ecrasementConfirme) {
            ecrasementConfirme = false
            ecrireEtVerifierClone(tag, dumpSource)
            return
        }

        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            Toast.makeText(this, "Ce tag n'est pas compatible NFC-A, essaie un autre tag.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            nfcA.connect()
            val dumpExistant = lireDumpBrut(nfcA, 28)
            val infoExistante = DecodeurAnycubic.decoder(dumpExistant)
            nfcA.close()

            if (infoExistante.matiere != null) {
                runOnUiThread {
                    txtStatut.text = "Tag deja ecrit detecte - confirmation demandee..."
                    AlertDialog.Builder(this)
                        .setTitle("Ce tag contient déjà une bobine")
                        .setMessage(
                            "Ce tag semble être une bobine Anycubic existante (${infoExistante.matiere}" +
                                (infoExistante.couleurHexBrute?.let { " #$it" } ?: "") +
                                "), pas un tag vierge.\n\nL'écraser avec le clone effacera définitivement ses données actuelles. Continuer quand même ?"
                        )
                        .setPositiveButton("Écraser quand même") { _, _ ->
                            ecrasementConfirme = true
                            txtStatut.text = "Confirmé : rapproche à nouveau la MÊME bobine pour l'écraser..."
                        }
                        .setNegativeButton("Annuler") { _, _ -> annulerModeAttente() }
                        .setOnCancelListener { annulerModeAttente() }
                        .show()
                }
                return
            }
            ecrireEtVerifierClone(tag, dumpSource)
        } catch (e: Exception) {
            try { nfcA.close() } catch (e2: Exception) { /* rien a faire */ }
            runOnUiThread { txtStatut.text = "Erreur de lecture du tag cible : ${e.message}" }
        }
    }

    private data class ResultatClonage(val reussi: Boolean, val pageEnEchec: Int?, val erreurMessage: String?)

    private fun executerClonage(tag: Tag, dumpSource: ByteArray): ResultatClonage {
        val nfcA = NfcA.get(tag)
            ?: return ResultatClonage(false, null, "Ce tag n'est pas compatible NFC-A, essaie un autre tag.")
        return try {
            nfcA.connect()
            var pageEnEchec: Int? = null
            for ((page, octets) in ClonageAnycubic.pagesAEcrire(dumpSource)) {
                if (!ecrirePageAvecVerif(nfcA, page, octets)) {
                    pageEnEchec = page
                    break
                }
            }
            val dumpRelu = lireDumpBrut(nfcA, ClonageAnycubic.DERNIERE_PAGE_DONNEES)
            nfcA.close()
            val reussi = pageEnEchec == null && ClonageAnycubic.zoneCloneeIdentique(dumpSource, dumpRelu)
            ResultatClonage(reussi, pageEnEchec, null)
        } catch (e: Exception) {
            try { nfcA.close() } catch (e2: Exception) { /* rien a faire */ }
            ResultatClonage(false, null, e.message)
        }
    }

    private fun ecrireEtVerifierClone(tag: Tag, dumpSource: ByteArray) {
        runOnUiThread { txtStatut.text = "Écriture en cours, ne retire pas le tag..." }
        val resultat = executerClonage(tag, dumpSource)
        enAttenteTagCible = false
        runOnUiThread {
            btnAnnulerClonage.visibility = View.GONE
            btnEffacer.visibility = View.VISIBLE
            if (dernierScanReussi) btnCloner.visibility = View.VISIBLE
            when {
                resultat.reussi -> {
                    txtStatut.text = "Clonage réussi et vérifié ✓"
                    if (GestionnaireParametres.lireVibrationFinLecture(this)) vibrerConfirmation()
                }
                resultat.pageEnEchec != null -> {
                    txtStatut.text = "Écriture interrompue (page 0x%02X non confirmée) — repose le tag bien à plat sans le bouger et réessaie.".format(resultat.pageEnEchec)
                }
                resultat.erreurMessage != null -> {
                    txtStatut.text = "Erreur d'écriture : ${resultat.erreurMessage} — le tag cible est peut-être verrouillé ou n'est pas un NTAG213 vierge."
                }
                else -> {
                    txtStatut.text = "Écriture terminée mais la relecture ne correspond pas - clonage probablement incomplet. Réessaie."
                }
            }
        }
    }

    // ============================== ECRITURE PAR LOT ==============================
    // Utilise par la creation en quantite (CreationTagActivity.dumpsGeneres) et par un futur
    // import de base (meme principe que le clonage par lot d'ElegooRFID) : enchaine
    // automatiquement sur le tag suivant apres chaque succes.

    private fun demarrerLotAvecValides(valides: List<Pair<String, ByteArray>>) {
        lotAClone = valides
        indexLotCourant = 0
        resultatsLot.clear()
        demarrerLotTagSuivant()
    }

    private fun demarrerLotTagSuivant() {
        enAttenteTagLot = true
        btnCloner.visibility = View.GONE
        btnEffacer.visibility = View.GONE
        btnAnnulerClonage.visibility = View.VISIBLE
        btnAnnulerClonage.text = "Annuler le lot"
        layoutLignesInfo.removeAllViews()
        dernieresLignesInfo.clear()
        vuCouleur.visibility = View.GONE
        imgNfc.visibility = View.VISIBLE
        val (nom, _) = lotAClone[indexLotCourant]
        txtStatut.text = "Approche le tag vierge pour \"$nom\" (${indexLotCourant + 1}/${lotAClone.size})..."
    }

    private fun traiterTagLot(tag: Tag) {
        val (nom, dump) = lotAClone[indexLotCourant]
        runOnUiThread { txtStatut.text = "Écriture en cours (${indexLotCourant + 1}/${lotAClone.size}) : $nom..." }
        val resultat = executerClonage(tag, dump)
        runOnUiThread {
            if (resultat.reussi) {
                resultatsLot.add(ResultatLotLigne(nom, true, null))
                indexLotCourant++
                if (GestionnaireParametres.lireVibrationFinLecture(this)) vibrerConfirmation()
                avancerOuTerminerLot()
            } else {
                val raison = when {
                    resultat.pageEnEchec != null -> "page 0x%02X non confirmée".format(resultat.pageEnEchec)
                    resultat.erreurMessage != null -> resultat.erreurMessage
                    else -> "relecture non conforme"
                }
                AlertDialog.Builder(this)
                    .setTitle("Échec sur \"$nom\"")
                    .setMessage("$raison\n\nRéessayer ce fichier (sur le même tag ou un autre), passer au suivant, ou annuler le lot ?")
                    .setPositiveButton("Réessayer") { _, _ ->
                        txtStatut.text = "Approche un tag pour réessayer \"$nom\" (${indexLotCourant + 1}/${lotAClone.size})..."
                    }
                    .setNeutralButton("Passer au suivant") { _, _ ->
                        resultatsLot.add(ResultatLotLigne(nom, false, raison))
                        indexLotCourant++
                        avancerOuTerminerLot()
                    }
                    .setNegativeButton("Annuler le lot") { _, _ -> annulerLot() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun avancerOuTerminerLot() {
        if (indexLotCourant >= lotAClone.size) {
            terminerLot()
        } else {
            val (nom, _) = lotAClone[indexLotCourant]
            txtStatut.text = "Approche le tag vierge pour \"$nom\" (${indexLotCourant + 1}/${lotAClone.size})..."
        }
    }

    private fun terminerLot() {
        val reussis = resultatsLot.count { it.reussi }
        val total = resultatsLot.size
        val echecs = resultatsLot.filter { !it.reussi }.map { it.nom }
        val detail = if (echecs.isNotEmpty()) "\n\nNon clonés : ${echecs.joinToString(", ")}" else ""
        val lignesSnapshot = resultatsLot.toList()
        AlertDialog.Builder(this)
            .setTitle("Lot terminé")
            .setMessage("$reussis/$total tag(s) créés avec succès.$detail")
            .setPositiveButton("OK", null)
            .setNeutralButton("Exporter le résultat") { _, _ -> exporterResultatLot(lignesSnapshot) }
            .show()
        reinitialiserEtatLot()
        txtStatut.text = "Lot terminé. Approche une bobine Anycubic du dos du téléphone..."
    }

    private fun exporterResultatLot(lignes: List<ResultatLotLigne>) {
        val csv = StringBuilder("Fichier;Résultat;Détail\n")
        for (l in lignes) {
            val resultatTexte = if (l.reussi) "Réussi" else "Échec"
            csv.append(listOf(l.nom, resultatTexte, l.detail ?: "").joinToString(";") { it.replace(";", ",") })
            csv.append("\n")
        }
        exporterVers("resultat_lot_anycubic_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()) + ".csv", csv.toString(), "text/csv")
    }

    private fun annulerLot() {
        reinitialiserEtatLot()
        txtStatut.text = "Lot annulé. Approche une bobine Anycubic du dos du téléphone..."
    }

    private fun reinitialiserEtatLot() {
        enAttenteTagLot = false
        lotAClone = emptyList()
        resultatsLot.clear()
        indexLotCourant = 0
        btnAnnulerClonage.visibility = View.GONE
        btnAnnulerClonage.text = "Annuler le clonage"
        btnEffacer.visibility = View.VISIBLE
        if (dernierScanReussi) btnCloner.visibility = View.VISIBLE
    }

    private fun formaterDumpHex(dump: ByteArray): String {
        val sb = StringBuilder()
        for (page in dump.indices step 4) {
            val fin = minOf(page + 4, dump.size)
            val octets = dump.copyOfRange(page, fin).joinToString(" ") { "%02X".format(it) }
            sb.appendLine("Page %02X : %s".format(page / 4, octets))
        }
        return sb.toString().trim()
    }

    private fun ajouterLigneInfo(icone: Int, texte: String) {
        dernieresLignesInfo.add(icone to texte)
        val ligne = LinearLayout(this)
        ligne.orientation = LinearLayout.HORIZONTAL
        ligne.gravity = Gravity.CENTER_VERTICAL
        val paddingPx = (6 * resources.displayMetrics.density).toInt()
        ligne.setPadding(0, paddingPx, 0, paddingPx)

        val img = ImageView(this)
        img.setImageResource(icone)
        val tailleIcone = (20 * resources.displayMetrics.density).toInt()
        val paramsImg = LinearLayout.LayoutParams(tailleIcone, tailleIcone)
        paramsImg.marginEnd = (10 * resources.displayMetrics.density).toInt()
        img.layoutParams = paramsImg

        val txt = TextView(this)
        txt.text = texte
        txt.setTextColor(resources.getColor(R.color.texte_principal, theme))
        txt.textSize = 14f

        ligne.addView(img)
        ligne.addView(txt)
        layoutLignesInfo.addView(ligne)

        val animation = AnimationUtils.loadAnimation(this, R.anim.apparition_ligne)
        animation.startOffset = (layoutLignesInfo.childCount - 1) * 90L
        ligne.startAnimation(animation)
    }

    /**
     * @param statutTexte texte affiche en haut (par defaut "Bobine identifiée" pour un vrai scan NFC).
     * @param enregistrerHistorique false pour une simple consultation via QR (voir
     *   traiterLienEtiquette) - pas une vraie lecture NFC, pas d'entree d'historique.
     * @param origine ORIGINE_SCAN_NFC/ORIGINE_QR/ORIGINE_CREATION (voir ces constantes).
     */
    private fun afficherResultats(
        info: DecodeurAnycubic.InfoBobine,
        dump: ByteArray,
        statutTexte: String = "Bobine identifiée",
        enregistrerHistorique: Boolean = true,
        origine: String = ORIGINE_SCAN_NFC
    ) {
        // Couleur : depuis le 10/10/2026, la couleur BRUTE lue sur le tag (page 20, voir
        // DecodeurAnycubic) est la source de verite - fiable pour N'IMPORTE QUELLE couleur, pas
        // seulement les 8 deja repertoriees. Le code 2 lettres (dans la reference produit) ne
        // sert plus qu'a retrouver un NOM officiel quand il est connu ; absent de la table, la
        // couleur reste affichee (grace au hex brut) mais sous un nom generique plutot que
        // "couleur inconnue" pur et simple.
        val couleurConnue = TableCouleursAnycubic.trouver(info.codeCouleur)
        val hexAffiche = info.couleurHexBrute ?: couleurConnue?.hex
        val nomAffiche = couleurConnue?.nom ?: (if (hexAffiche != null) "Couleur personnalisée" else null)
        dernierScanReussi = true
        dernierDumpBrut = dump
        derniereOrigineBobine = origine
        btnCloner.visibility = if (dump.size >= ClonageAnycubic.TAILLE_MIN_DUMP_SOURCE) View.VISIBLE else View.GONE

        txtStatut.text = statutTexte

        if (hexAffiche != null) {
            try {
                val argb = Color.parseColor("#$hexAffiche")
                vuCouleur.backgroundTintList = ColorStateList.valueOf(argb)
                vuCouleur.visibility = View.VISIBLE
                imgNfc.visibility = View.GONE
            } catch (e: Exception) { /* hex invalide : on garde l'icone NFC */ }
        }

        ajouterLigneInfo(R.drawable.ic_materiau, "${info.matiere} - ${info.referenceProduit ?: "?"}")
        if (hexAffiche != null) {
            ajouterLigneInfo(R.drawable.ic_couleur, nomAffiche ?: "Couleur personnalisée")
            ajouterLigneInfo(R.drawable.ic_couleur, "Code hexa : #$hexAffiche")
        } else {
            ajouterLigneInfo(R.drawable.ic_couleur, "Couleur illisible (code ${info.codeCouleur ?: "?"})")
        }
        info.poidsGrammes?.let { ajouterLigneInfo(R.drawable.ic_materiau, "Poids bobine : ${it}g") }
        if (info.diametreMm != null) ajouterLigneInfo(R.drawable.ic_materiau, "Diamètre : ${info.diametreMm}mm${info.longueurMetres?.let { " - $it m" } ?: ""}")
        if (info.temperatureBuseMin != null && info.temperatureBuseMax != null) {
            ajouterLigneInfo(R.drawable.ic_temperature, "Buse : ${info.temperatureBuseMin}-${info.temperatureBuseMax}°C")
        }
        info.temperaturePlateau?.let { ajouterLigneInfo(R.drawable.ic_temperature, "Plateau : ${it}°C") }

        // Resume (pour copier/partager) + dump technique complet (pour export)
        val resume = StringBuilder()
        resume.appendLine("Matière : ${info.matiere} ${nomAffiche ?: "(couleur illisible : ${info.codeCouleur})"}")
        hexAffiche?.let { resume.appendLine("Code hexa : #$it") }
        info.poidsGrammes?.let { resume.appendLine("Poids : ${it}g") }
        info.diametreMm?.let { resume.appendLine("Diamètre : ${it}mm") }
        info.longueurMetres?.let { resume.appendLine("Longueur : ${it}m") }
        if (info.temperatureBuseMin != null) resume.appendLine("Buse : ${info.temperatureBuseMin}-${info.temperatureBuseMax}°C")
        info.temperaturePlateau?.let { resume.appendLine("Plateau : ${it}°C") }
        info.referenceProduit?.let { resume.appendLine("Référence : $it") }
        dernierResume = resume.toString().trim()
        dernierDumpTexte = dernierResume + "\n\n--- DUMP BRUT (pour analyse) ---\nUID : ${info.uid ?: "?"}\n" + formaterDumpHex(dump)

        if (enregistrerHistorique) {
            if (GestionnaireParametres.lireVibrationFinLecture(this)) vibrerConfirmation()
            enregistrerDansHistorique(info.uid ?: "?", info.matiere ?: "?", nomAffiche ?: (info.codeCouleur ?: ""))
        }

        val argb = hexAffiche?.let { try { Color.parseColor("#$it") } catch (e: Exception) { null } }
        filesAttenteEtiquettes.add(
            EtiquetteEnAttente(
                matiere = "${info.matiere} ${nomAffiche ?: ""}".trim(),
                nomCouleur = nomAffiche,
                couleurArgb = argb,
                codeHexa = hexAffiche,
                poidsGrammes = info.poidsGrammes,
                tempBuseMin = info.temperatureBuseMin,
                tempBuseMax = info.temperatureBuseMax,
                tempPlateau = info.temperaturePlateau,
                dumpHex = if (dump.size >= ClonageAnycubic.TAILLE_MIN_DUMP_SOURCE) {
                    dump.copyOfRange(0, ClonageAnycubic.TAILLE_MIN_DUMP_SOURCE).joinToString("") { "%02X".format(it) }
                } else null,
                origine = origine
            )
        )
        mettreAJourBoutonImpression()
    }

    private fun vibrerConfirmation() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(80)
            }
        } catch (e: Exception) { /* pas grave si la vibration echoue */ }
    }

    private fun mettreAJourBoutonImpression() {
        val n = filesAttenteEtiquettes.size
        btnImprimerEtiquette.text = if (n <= 1) "Imprimer l'étiquette" else "Imprimer les étiquettes ($n)"
    }

    private fun imprimerEtiquette() {
        if (filesAttenteEtiquettes.isEmpty()) {
            Toast.makeText(this, "Aucune étiquette en attente, scanne d'abord un tag.", Toast.LENGTH_SHORT).show()
            return
        }
        val etiquettesAImprimer = filesAttenteEtiquettes.toList()

        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            var document: PdfDocument? = null

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                document = PdfDocument()
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val nbPages = Math.ceil(etiquettesAImprimer.size.toDouble() / ETIQUETTES_PAR_PAGE).toInt().coerceAtLeast(1)
                val info = PrintDocumentInfo.Builder("etiquettes_filament_anycubic.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(nbPages)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                var index = 0
                while (index < etiquettesAImprimer.size) {
                    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index / ETIQUETTES_PAR_PAGE + 1).create()
                    val page = document!!.startPage(pageInfo)
                    val lotDeCettePage = etiquettesAImprimer.subList(
                        index, minOf(index + ETIQUETTES_PAR_PAGE, etiquettesAImprimer.size)
                    )
                    lotDeCettePage.forEachIndexed { positionDansPage, etiquette ->
                        val colonne = positionDansPage % COLONNES_GRILLE
                        val ligne = positionDansPage / COLONNES_GRILLE
                        val x = MARGE_GRILLE + colonne * LARGEUR_CELLULE
                        val y = MARGE_GRILLE + ligne * HAUTEUR_CELLULE
                        dessinerEtiquette(page.canvas, etiquette, x, y)
                    }
                    document!!.finishPage(page)
                    index += ETIQUETTES_PAR_PAGE
                }
                try {
                    document!!.writeTo(FileOutputStream(destination.fileDescriptor))
                } catch (e: IOException) {
                    callback.onWriteFailed(e.message)
                    return
                } finally {
                    document!!.close()
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            }
        }

        printManager.print("Etiquettes filament Anycubic", adapter, PrintAttributes.Builder().build())
        filesAttenteEtiquettes.removeAll(etiquettesAImprimer)
        mettreAJourBoutonImpression()
    }

    private fun dessinerEtiquette(canvas: Canvas, etiquette: EtiquetteEnAttente, margeGauche: Float, y: Float) {
        val largeurEtiquette = LARGEUR_CELLULE - 8f
        val hauteurEtiquette = HAUTEUR_CELLULE - 8f
        val rembourrage = 8f

        val paintTitre = Paint().apply { color = Color.GRAY; textSize = 7f }
        val paintMatiere = Paint().apply { color = Color.BLACK; textSize = 11f; isFakeBoldText = true }
        val paintTexte = Paint().apply { color = Color.DKGRAY; textSize = 8f }
        val paintBordure = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }
        val paintSwatch = Paint().apply { style = Paint.Style.FILL }

        canvas.drawRoundRect(margeGauche, y, margeGauche + largeurEtiquette, y + hauteurEtiquette, 5f, 5f, paintBordure)

        // QR de reconsultation (porte depuis ElegooRFID le 10/10/2026) en haut a droite - voir
        // dessinerQr. Directement avec le rendu bitmap (jamais module par module, voir
        // ScanQrActivity pour pourquoi) puisqu'on sait deja, grace a ElegooRFID, que c'est la
        // seule methode fiable a l'impression.
        val tailleQr = minOf(hauteurEtiquette - 2 * rembourrage, 56f)
        var xQr = margeGauche + largeurEtiquette - rembourrage
        if (etiquette.dumpHex != null) {
            xQr = margeGauche + largeurEtiquette - rembourrage - tailleQr
            dessinerQr(canvas, lienQrPourDump(etiquette.dumpHex), xQr, y + rembourrage, tailleQr)
        }
        val largeurTexteDisponible = xQr - 4f - (margeGauche + rembourrage)

        val margeInterne = margeGauche + rembourrage
        var yInterne = y + 13f
        canvas.drawText("AnycubicRFID", margeInterne, yInterne, paintTitre)

        yInterne += 14f
        canvas.drawText(tronquerPourLargeur(paintMatiere, etiquette.matiere, largeurTexteDisponible), margeInterne, yInterne, paintMatiere)

        // Badge d'origine (porte depuis ElegooRFID) : seulement si ce n'est PAS un vrai scan NFC,
        // pour ne pas confondre plus tard une vraie bobine Anycubic avec un tag cree a la main ou
        // simplement reconsulte via QR.
        if (etiquette.origine != ORIGINE_SCAN_NFC) {
            yInterne += 10f
            val badge = when (etiquette.origine) {
                ORIGINE_CREATION -> "⚠ Créé manuellement"
                ORIGINE_QR -> "⚠ Vu via QR (non re-scanné)"
                else -> "⚠ ${etiquette.origine}"
            }
            canvas.drawText(tronquerPourLargeur(paintTexte, badge, largeurTexteDisponible), margeInterne, yInterne, paintTexte)
        }

        yInterne += 16f
        if (etiquette.couleurArgb != null) {
            paintSwatch.color = etiquette.couleurArgb
            canvas.drawCircle(margeInterne + 5f, yInterne - 3f, 5.5f, paintSwatch)
            val paintCercleBordure = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.8f }
            canvas.drawCircle(margeInterne + 5f, yInterne - 3f, 5.5f, paintCercleBordure)
            canvas.drawText(tronquerPourLargeur(paintTexte, etiquette.nomCouleur ?: "", largeurTexteDisponible - 16f), margeInterne + 16f, yInterne, paintTexte)
            yInterne += 11f
            if (etiquette.codeHexa != null) {
                canvas.drawText("#${etiquette.codeHexa}", margeInterne + 16f, yInterne, paintTexte)
                yInterne += 12f
            }
        }
        if (etiquette.poidsGrammes != null) {
            canvas.drawText("Poids : ${etiquette.poidsGrammes}g", margeInterne, yInterne, paintTexte)
            yInterne += 11f
        }
        if (etiquette.tempBuseMin != null && etiquette.tempBuseMax != null) {
            canvas.drawText("Buse : ${etiquette.tempBuseMin}-${etiquette.tempBuseMax}°C", margeInterne, yInterne, paintTexte)
            yInterne += 11f
        }
        etiquette.tempPlateau?.let { canvas.drawText("Plateau : ${it}°C", margeInterne, yInterne, paintTexte) }

        val paintDate = Paint().apply { color = Color.LTGRAY; textSize = 5.5f }
        canvas.drawText(
            SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date()),
            margeInterne, y + hauteurEtiquette - 6f, paintDate
        )
    }

    /** Tronque "texte" avec une ellipse finale si besoin pour ne pas depasser "largeurMax" avec
     * "peinture" - evite qu'une ligne trop longue ne passe par-dessus le QR a cote. Porte depuis
     * ElegooRFID (meme bug reel deja rencontre et corrige la-bas). */
    private fun tronquerPourLargeur(peinture: Paint, texte: String, largeurMax: Float): String {
        if (largeurMax <= 0f || peinture.measureText(texte) <= largeurMax) return texte
        var tronque = texte
        while (tronque.isNotEmpty() && peinture.measureText("$tronque…") > largeurMax) {
            tronque = tronque.dropLast(1)
        }
        return if (tronque.isEmpty()) "…" else "$tronque…"
    }

    private fun lienQrPourDump(dumpHex: String): String = "$SCHEME_QR://$HOTE_QR/$dumpHex"

    /** Dessine un QR code encodant un lien (voir lienQrPourDump) sur le Canvas - porte depuis
     * ElegooRFID le 10/10/2026 avec DIRECTEMENT la version finale corrigee (rendu via Bitmap
     * intermediaire, un pixel par module) : la version initiale d'ElegooRFID dessinait un
     * rectangle vectoriel independant PAR MODULE, ce qui s'est revele illisible une fois imprime
     * (arrondis de rasterisage independants entre modules voisins) - pas de raison de refaire
     * cette erreur ici en repartant de zero. */
    private fun dessinerQr(canvas: Canvas, contenu: String, x: Float, y: Float, taille: Float): Boolean {
        return try {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
            val matrice = QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, 0, 0, hints)
            val bitmap = Bitmap.createBitmap(matrice.width, matrice.height, Bitmap.Config.ARGB_8888)
            for (ty in 0 until matrice.height) {
                for (tx in 0 until matrice.width) {
                    bitmap.setPixel(tx, ty, if (matrice.get(tx, ty)) Color.BLACK else Color.WHITE)
                }
            }
            val peintureQr = Paint().apply { isFilterBitmap = false }
            canvas.drawBitmap(bitmap, null, RectF(x, y, x + taille, y + taille), peintureQr)
            true
        } catch (ex: WriterException) {
            false
        }
    }

    private fun copierResume() {
        if (dernierResume.isEmpty()) {
            Toast.makeText(this, "Rien à copier pour l'instant, scanne d'abord un tag.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Résultat Anycubic RFID", dernierResume))
        Toast.makeText(this, "Copié dans le presse-papier.", Toast.LENGTH_SHORT).show()
    }

    private fun partagerResume() {
        if (dernierResume.isEmpty()) {
            Toast.makeText(this, "Rien à partager pour l'instant, scanne d'abord un tag.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, dernierResume)
        startActivity(Intent.createChooser(intent, "Partager le résultat"))
    }

    private fun exporterDump() {
        if (dernierDumpTexte.isEmpty()) {
            Toast.makeText(this, "Aucun dump à exporter pour l'instant, scanne d'abord un tag.", Toast.LENGTH_SHORT).show()
            return
        }
        val nomFichier = "dump_anycubic_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()) + ".txt"
        exporterVers(nomFichier, dernierDumpTexte, "text/plain")
    }

    private fun exporterVers(nomSuggere: String, contenu: String, typeMime: String) {
        contenuAExporter = contenu
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = typeMime
            putExtra(Intent.EXTRA_TITLE, nomSuggere)
        }
        try {
            startActivityForResult(intent, CODE_EXPORT)
        } catch (e: Exception) {
            contenuAExporter = null
            Toast.makeText(this, "Impossible d'ouvrir le sélecteur de fichiers : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CODE_CREATION) {
            if (resultCode != RESULT_OK) return
            val lot = CreationTagActivity.dumpsGeneres
            if (lot.isNotEmpty()) {
                CreationTagActivity.dumpsGeneres = emptyList()
                demarrerLotAvecValides(lot)
                return
            }
            val dump = data?.getByteArrayExtra("dump") ?: return
            val info = DecodeurAnycubic.decoder(dump)
            if (info.matiere == null) {
                Toast.makeText(this, "Tag créé mais le dump généré ne se relit pas comme attendu.", Toast.LENGTH_LONG).show()
                return
            }
            afficherResultats(info, dump, statutTexte = "Tag créé (pas encore écrit sur une bobine)", enregistrerHistorique = false, origine = ORIGINE_CREATION)
            return
        }

        if (requestCode == CODE_SCAN_QR) {
            if (resultCode != RESULT_OK) return
            val lien = data?.getStringExtra(ScanQrActivity.EXTRA_LIEN) ?: return
            traiterLienEtiquette(android.net.Uri.parse(lien))
            return
        }

        if (requestCode == CODE_IMPORT_BASE) {
            if (resultCode != RESULT_OK) return
            // Voir ImportBaseDonneesActivity.dumpsGeneres : passe par un champ statique plutot
            // que par les extras de l'Intent (simple liste de (nom, ByteArray)). Reutilise le
            // meme flux de clonage par lot que CODE_CREATION en quantite > 1.
            val valides = ImportBaseDonneesActivity.dumpsGeneres
            ImportBaseDonneesActivity.dumpsGeneres = emptyList()
            if (valides.isEmpty()) {
                Toast.makeText(this, "Import interrompu (l'appli a été relancée), recommence.", Toast.LENGTH_LONG).show()
                return
            }
            demarrerLotAvecValides(valides)
            return
        }

        if (requestCode != CODE_EXPORT) return
        val contenu = contenuAExporter
        contenuAExporter = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (contenu == null) {
            Toast.makeText(this, "Export interrompu (l'appli a été relancée), recommence.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val flux = contentResolver.openOutputStream(uri) ?: throw IOException("fichier inaccessible")
            flux.use { it.write(contenu.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Fichier enregistré.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur export : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun enregistrerDansHistorique(uid: String, matiere: String, couleur: String) {
        try {
            val fichier = File(getExternalFilesDir(null), "historique_scans.csv")
            val ligne = "${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())};$uid;$matiere;$couleur\n"
            fichier.appendText(ligne)
        } catch (e: Exception) { /* pas grave si l'ecriture de l'historique echoue */ }
    }

    private fun afficherHistorique() {
        try {
            val fichier = File(getExternalFilesDir(null), "historique_scans.csv")
            if (!fichier.exists() || fichier.readText().isBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Historique des scans")
                    .setMessage("Aucun scan enregistré pour l'instant.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            val lignes = fichier.readLines().reversed()
            val texteAffiche = lignes.joinToString("\n\n") { ligne ->
                val parts = ligne.split(";")
                if (parts.size >= 3) {
                    "${parts[0]}\n${parts[2]}${if (parts.size >= 4 && parts[3].isNotBlank()) " (${parts[3]})" else ""}"
                } else ligne
            }
            AlertDialog.Builder(this)
                .setTitle("Historique des scans (${lignes.size})")
                .setMessage(texteAffiche)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Exporter") { _, _ -> exporterVers("historique_scans_anycubic.csv", fichier.readText(), "text/csv") }
                .setNegativeButton("Vider l'historique") { _, _ ->
                    fichier.delete()
                    Toast.makeText(this, "Historique effacé.", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lecture historique : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun construireRapportCompatibilite(): String {
        val versionAppli = try {
            val infoPaquet = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) infoPaquet.longVersionCode else infoPaquet.versionCode.toLong()
            "${infoPaquet.versionName} (build $build)"
        } catch (e: Exception) { "?" }
        val nfcActif = if (::nfcAdapter.isInitialized) (if (nfcAdapter.isEnabled) "oui" else "non") else "pas de puce NFC"

        val r = StringBuilder()
        r.append("=== Rapport de compatibilité - AnycubicRFID ===\n")
        r.append("Appli : v$versionAppli\n")
        r.append("Téléphone : ${Build.MANUFACTURER} ${Build.MODEL}\n")
        r.append("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        r.append("NFC actif : $nfcActif\n")
        r.append("Date : ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())}\n\n")
        r.append("--- Dernier scan ---\n")
        if (dernierResume.isEmpty()) {
            r.append("Aucun scan effectué depuis l'ouverture de l'appli.\n")
        } else {
            r.append(if (dernierScanReussi) "Lecture réussie\n" else "Lecture incomplète\n")
            r.append(dernierResume).append("\n")
        }
        return r.toString()
    }

    private fun copierRapportCompatibilite() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Rapport compatibilité AnycubicRFID", construireRapportCompatibilite()))
        Toast.makeText(this, "Rapport copié : colle-le sur le forum.", Toast.LENGTH_SHORT).show()
    }

    private fun ouvrirReglagesNfc() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Impossible d'ouvrir les réglages NFC.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        contenuAExporter?.let { outState.putString("contenuAExporter", it) }

        outState.putString("dernierDumpTexte", dernierDumpTexte)
        outState.putString("dernierResume", dernierResume)
        outState.putBoolean("dernierScanReussi", dernierScanReussi)
        outState.putString("txtStatutTexte", txtStatut.text.toString())
        val couleurVisible = vuCouleur.visibility == View.VISIBLE
        outState.putBoolean("vuCouleurVisible", couleurVisible)
        if (couleurVisible) outState.putInt("vuCouleurArgb", vuCouleur.backgroundTintList?.defaultColor ?: Color.TRANSPARENT)
        outState.putIntArray("lignesInfoIcones", dernieresLignesInfo.map { it.first }.toIntArray())
        outState.putStringArray("lignesInfoTextes", dernieresLignesInfo.map { it.second }.toTypedArray())

        outState.putStringArray("etiquettesMatiere", filesAttenteEtiquettes.map { it.matiere }.toTypedArray())
        outState.putStringArray("etiquettesNomCouleur", filesAttenteEtiquettes.map { it.nomCouleur }.toTypedArray())
        outState.putIntArray("etiquettesCouleurArgb", filesAttenteEtiquettes.map { it.couleurArgb ?: SENTINEL_NULL }.toIntArray())
        outState.putStringArray("etiquettesCodeHexa", filesAttenteEtiquettes.map { it.codeHexa }.toTypedArray())
        outState.putLongArray("etiquettesPoidsGrammes", filesAttenteEtiquettes.map { it.poidsGrammes ?: SENTINEL_NULL.toLong() }.toLongArray())
        outState.putIntArray("etiquettesTempBuseMin", filesAttenteEtiquettes.map { it.tempBuseMin ?: SENTINEL_NULL }.toIntArray())
        outState.putIntArray("etiquettesTempBuseMax", filesAttenteEtiquettes.map { it.tempBuseMax ?: SENTINEL_NULL }.toIntArray())
        outState.putIntArray("etiquettesTempPlateau", filesAttenteEtiquettes.map { it.tempPlateau ?: SENTINEL_NULL }.toIntArray())
        outState.putStringArray("etiquettesDumpHex", filesAttenteEtiquettes.map { it.dumpHex }.toTypedArray())
        outState.putStringArray("etiquettesOrigine", filesAttenteEtiquettes.map { it.origine }.toTypedArray())

        dernierDumpBrut?.let { outState.putByteArray("dernierDumpBrut", it) }
    }

    private fun restaurerAffichageResultat(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        savedInstanceState.getString("dernierDumpTexte")?.let { dernierDumpTexte = it }
        savedInstanceState.getString("dernierResume")?.let { dernierResume = it }
        dernierScanReussi = savedInstanceState.getBoolean("dernierScanReussi")
        savedInstanceState.getString("txtStatutTexte")?.let { txtStatut.text = it }

        if (savedInstanceState.getBoolean("vuCouleurVisible")) {
            vuCouleur.backgroundTintList = ColorStateList.valueOf(savedInstanceState.getInt("vuCouleurArgb"))
            vuCouleur.visibility = View.VISIBLE
            imgNfc.visibility = View.GONE
        } else {
            vuCouleur.visibility = View.GONE
            imgNfc.visibility = View.VISIBLE
        }

        val icones = savedInstanceState.getIntArray("lignesInfoIcones") ?: IntArray(0)
        val textes = savedInstanceState.getStringArray("lignesInfoTextes") ?: emptyArray()
        for (i in icones.indices) ajouterLigneInfo(icones[i], textes.getOrNull(i) ?: "")

        val matieres = savedInstanceState.getStringArray("etiquettesMatiere") ?: emptyArray()
        val nomsCouleur = savedInstanceState.getStringArray("etiquettesNomCouleur") ?: emptyArray()
        val couleursArgb = savedInstanceState.getIntArray("etiquettesCouleurArgb") ?: IntArray(0)
        val codesHexa = savedInstanceState.getStringArray("etiquettesCodeHexa") ?: emptyArray()
        val poids = savedInstanceState.getLongArray("etiquettesPoidsGrammes") ?: LongArray(0)
        val tempsMin = savedInstanceState.getIntArray("etiquettesTempBuseMin") ?: IntArray(0)
        val tempsMax = savedInstanceState.getIntArray("etiquettesTempBuseMax") ?: IntArray(0)
        val tempsPlateau = savedInstanceState.getIntArray("etiquettesTempPlateau") ?: IntArray(0)
        val dumpsHex = savedInstanceState.getStringArray("etiquettesDumpHex") ?: emptyArray()
        val origines = savedInstanceState.getStringArray("etiquettesOrigine") ?: emptyArray()
        for (i in matieres.indices) {
            filesAttenteEtiquettes.add(
                EtiquetteEnAttente(
                    matiere = matieres[i] ?: "",
                    nomCouleur = nomsCouleur.getOrNull(i),
                    couleurArgb = couleursArgb.getOrNull(i)?.takeIf { it != SENTINEL_NULL },
                    codeHexa = codesHexa.getOrNull(i),
                    poidsGrammes = poids.getOrNull(i)?.takeIf { it != SENTINEL_NULL.toLong() },
                    tempBuseMin = tempsMin.getOrNull(i)?.takeIf { it != SENTINEL_NULL },
                    tempBuseMax = tempsMax.getOrNull(i)?.takeIf { it != SENTINEL_NULL },
                    tempPlateau = tempsPlateau.getOrNull(i)?.takeIf { it != SENTINEL_NULL },
                    dumpHex = dumpsHex.getOrNull(i),
                    origine = origines.getOrNull(i) ?: ORIGINE_SCAN_NFC
                )
            )
        }
        mettreAJourBoutonImpression()

        dernierDumpBrut = savedInstanceState.getByteArray("dernierDumpBrut")
        if (dernierDumpBrut != null && !enAttenteTagCible && !enAttenteTagEffacement && !enAttenteTagLot) {
            btnCloner.visibility = View.VISIBLE
        }
    }

    // Grille d'etiquettes sur une page A4 (595x842 points) : 3 colonnes x 7 lignes = 21
    // etiquettes par page, a decouper aux ciseaux une fois imprimees.
    companion object {
        const val CODE_EXPORT = 4711
        const val CODE_CREATION = 4712
        const val CODE_SCAN_QR = 4713
        const val CODE_IMPORT_BASE = 4714
        /** Represente une valeur nulle dans un tableau primitif de Bundle (qui ne peut pas contenir null). */
        const val SENTINEL_NULL = Int.MIN_VALUE
        const val COLONNES_GRILLE = 3
        const val LIGNES_GRILLE = 7
        const val ETIQUETTES_PAR_PAGE = COLONNES_GRILLE * LIGNES_GRILLE
        const val MARGE_GRILLE = 25f
        const val LARGEUR_CELLULE = (595f - 2 * MARGE_GRILLE) / COLONNES_GRILLE
        const val HAUTEUR_CELLULE = (842f - 2 * MARGE_GRILLE) / LIGNES_GRILLE

        // Lien QR (voir dessinerQr/lienQrPourDump) et origine d'une bobine (voir afficherResultats/
        // dessinerEtiquette) - portes depuis ElegooRFID le 10/10/2026.
        const val SCHEME_QR = "anycubicrfid"
        const val HOTE_QR = "dump"
        const val ORIGINE_SCAN_NFC = "Scan NFC"
        const val ORIGINE_CREATION = "Création manuelle"
        const val ORIGINE_QR = "Lecture QR (sans NFC)"
    }
}
