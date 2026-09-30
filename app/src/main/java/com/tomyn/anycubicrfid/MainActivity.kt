package com.tomyn.anycubicrfid

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        val tempPlateau: Int?
    )

    private lateinit var imgNfc: ImageView
    private lateinit var vuCouleur: View
    private lateinit var txtStatut: TextView
    private lateinit var layoutLignesInfo: LinearLayout
    private lateinit var btnImprimerEtiquette: Button
    private lateinit var btnReglagesNfc: Button
    private lateinit var nfcAdapter: NfcAdapter

    private val filesAttenteEtiquettes = mutableListOf<EtiquetteEnAttente>()
    private val dernieresLignesInfo = mutableListOf<Pair<Int, String>>()
    private var dernierDumpTexte: String = ""
    private var dernierResume: String = ""
    private var dernierScanReussi = false
    // Contenu en attente pendant que l'utilisateur choisit ou enregistrer le fichier (selecteur Android)
    private var contenuAExporter: String? = null

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

        btnImprimerEtiquette.setOnClickListener { imprimerEtiquette() }
        btnReglagesNfc.setOnClickListener { ouvrirReglagesNfc() }
        findViewById<Button>(R.id.btnExporter).setOnClickListener { exporterDump() }
        findViewById<Button>(R.id.btnCopier).setOnClickListener { copierResume() }
        findViewById<Button>(R.id.btnPartager).setOnClickListener { partagerResume() }
        findViewById<Button>(R.id.btnHistorique).setOnClickListener { afficherHistorique() }
        findViewById<Button>(R.id.btnRapportCompat).setOnClickListener { copierRapportCompatibilite() }
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
        if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED) return

        @Suppress("DEPRECATION")
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        lireTag(tag)
    }

    private fun lireTag(tag: Tag) {
        layoutLignesInfo.removeAllViews()
        dernieresLignesInfo.clear()
        vuCouleur.visibility = View.GONE
        imgNfc.visibility = View.VISIBLE
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
            val tampon = ByteArrayOutputStream()
            var page = 0
            while (page <= 28) {
                tampon.write(nfcA.transceive(byteArrayOf(0x30, page.toByte())))
                page += 4
            }
            val dump = tampon.toByteArray()
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

    private fun afficherResultats(info: DecodeurAnycubic.InfoBobine, dump: ByteArray) {
        val couleur = TableCouleursAnycubic.trouver(info.codeCouleur)
        dernierScanReussi = true

        txtStatut.text = "Bobine identifiée"

        if (couleur != null) {
            try {
                val argb = Color.parseColor("#${couleur.hex}")
                vuCouleur.backgroundTintList = ColorStateList.valueOf(argb)
                vuCouleur.visibility = View.VISIBLE
                imgNfc.visibility = View.GONE
            } catch (e: Exception) { /* hex invalide : on garde l'icone NFC */ }
        }

        ajouterLigneInfo(R.drawable.ic_materiau, "${info.matiere} - ${info.referenceProduit ?: "?"}")
        if (couleur != null) {
            ajouterLigneInfo(R.drawable.ic_couleur, couleur.nom)
            ajouterLigneInfo(R.drawable.ic_couleur, "Code hexa : #${couleur.hex}")
        } else {
            ajouterLigneInfo(R.drawable.ic_couleur, "Couleur inconnue (code ${info.codeCouleur ?: "?"})")
        }
        info.poidsGrammes?.let { ajouterLigneInfo(R.drawable.ic_materiau, "Poids bobine : ${it}g") }
        if (info.temperatureBuseMin != null && info.temperatureBuseMax != null) {
            ajouterLigneInfo(R.drawable.ic_temperature, "Buse : ${info.temperatureBuseMin}-${info.temperatureBuseMax}°C")
        }
        info.temperaturePlateau?.let { ajouterLigneInfo(R.drawable.ic_temperature, "Plateau : ${it}°C") }

        // Resume (pour copier/partager) + dump technique complet (pour export)
        val resume = StringBuilder()
        resume.appendLine("Matière : ${info.matiere} ${couleur?.nom ?: "(couleur inconnue : ${info.codeCouleur})"}")
        couleur?.let { resume.appendLine("Code hexa : #${it.hex}") }
        info.poidsGrammes?.let { resume.appendLine("Poids : ${it}g") }
        if (info.temperatureBuseMin != null) resume.appendLine("Buse : ${info.temperatureBuseMin}-${info.temperatureBuseMax}°C")
        info.temperaturePlateau?.let { resume.appendLine("Plateau : ${it}°C") }
        info.referenceProduit?.let { resume.appendLine("Référence : $it") }
        dernierResume = resume.toString().trim()
        dernierDumpTexte = dernierResume + "\n\n--- DUMP BRUT (pour analyse) ---\nUID : ${info.uid ?: "?"}\n" + formaterDumpHex(dump)

        if (GestionnaireParametres.lireVibrationFinLecture(this)) vibrerConfirmation()
        enregistrerDansHistorique(info.uid ?: "?", info.matiere ?: "?", couleur?.nom ?: (info.codeCouleur ?: ""))

        val argb = couleur?.let { try { Color.parseColor("#${it.hex}") } catch (e: Exception) { null } }
        filesAttenteEtiquettes.add(
            EtiquetteEnAttente(
                matiere = "${info.matiere} ${couleur?.nom ?: ""}".trim(),
                nomCouleur = couleur?.nom,
                couleurArgb = argb,
                codeHexa = couleur?.hex,
                poidsGrammes = info.poidsGrammes,
                tempBuseMin = info.temperatureBuseMin,
                tempBuseMax = info.temperatureBuseMax,
                tempPlateau = info.temperaturePlateau
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

        val paintTitre = Paint().apply { color = Color.GRAY; textSize = 7f }
        val paintMatiere = Paint().apply { color = Color.BLACK; textSize = 11f; isFakeBoldText = true }
        val paintTexte = Paint().apply { color = Color.DKGRAY; textSize = 8f }
        val paintBordure = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }
        val paintSwatch = Paint().apply { style = Paint.Style.FILL }

        canvas.drawRoundRect(margeGauche, y, margeGauche + largeurEtiquette, y + hauteurEtiquette, 5f, 5f, paintBordure)

        val margeInterne = margeGauche + 8f
        var yInterne = y + 13f
        canvas.drawText("AnycubicRFID", margeInterne, yInterne, paintTitre)

        yInterne += 14f
        canvas.drawText(etiquette.matiere, margeInterne, yInterne, paintMatiere)

        yInterne += 16f
        if (etiquette.couleurArgb != null) {
            paintSwatch.color = etiquette.couleurArgb
            canvas.drawCircle(margeInterne + 5f, yInterne - 3f, 5.5f, paintSwatch)
            val paintCercleBordure = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.8f }
            canvas.drawCircle(margeInterne + 5f, yInterne - 3f, 5.5f, paintCercleBordure)
            canvas.drawText(etiquette.nomCouleur ?: "", margeInterne + 16f, yInterne, paintTexte)
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
        for (i in icones.indices) ajouterLigneInfo(icones[i], textes.getOrElse(i) { "" })

        val matieres = savedInstanceState.getStringArray("etiquettesMatiere") ?: emptyArray()
        val nomsCouleur = savedInstanceState.getStringArray("etiquettesNomCouleur") ?: emptyArray()
        val couleursArgb = savedInstanceState.getIntArray("etiquettesCouleurArgb") ?: IntArray(0)
        val codesHexa = savedInstanceState.getStringArray("etiquettesCodeHexa") ?: emptyArray()
        val poids = savedInstanceState.getLongArray("etiquettesPoidsGrammes") ?: LongArray(0)
        val tempsMin = savedInstanceState.getIntArray("etiquettesTempBuseMin") ?: IntArray(0)
        val tempsMax = savedInstanceState.getIntArray("etiquettesTempBuseMax") ?: IntArray(0)
        val tempsPlateau = savedInstanceState.getIntArray("etiquettesTempPlateau") ?: IntArray(0)
        for (i in matieres.indices) {
            filesAttenteEtiquettes.add(
                EtiquetteEnAttente(
                    matiere = matieres[i],
                    nomCouleur = nomsCouleur.getOrNull(i),
                    couleurArgb = couleursArgb.getOrNull(i)?.takeIf { it != SENTINEL_NULL },
                    codeHexa = codesHexa.getOrNull(i),
                    poidsGrammes = poids.getOrNull(i)?.takeIf { it != SENTINEL_NULL.toLong() },
                    tempBuseMin = tempsMin.getOrNull(i)?.takeIf { it != SENTINEL_NULL },
                    tempBuseMax = tempsMax.getOrNull(i)?.takeIf { it != SENTINEL_NULL },
                    tempPlateau = tempsPlateau.getOrNull(i)?.takeIf { it != SENTINEL_NULL }
                )
            )
        }
        mettreAJourBoutonImpression()
    }

    // Grille d'etiquettes sur une page A4 (595x842 points) : 3 colonnes x 7 lignes = 21
    // etiquettes par page, a decouper aux ciseaux une fois imprimees.
    companion object {
        const val CODE_EXPORT = 4711
        /** Represente une valeur nulle dans un tableau primitif de Bundle (qui ne peut pas contenir null). */
        const val SENTINEL_NULL = Int.MIN_VALUE
        const val COLONNES_GRILLE = 3
        const val LIGNES_GRILLE = 7
        const val ETIQUETTES_PAR_PAGE = COLONNES_GRILLE * LIGNES_GRILLE
        const val MARGE_GRILLE = 25f
        const val LARGEUR_CELLULE = (595f - 2 * MARGE_GRILLE) / COLONNES_GRILLE
        const val HAUTEUR_CELLULE = (842f - 2 * MARGE_GRILLE) / LIGNES_GRILLE
    }
}
