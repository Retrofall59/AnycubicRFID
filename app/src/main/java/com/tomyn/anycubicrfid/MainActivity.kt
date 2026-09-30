package com.tomyn.anycubicrfid

import android.content.res.ColorStateList
import android.graphics.Color
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var imgNfc: ImageView
    private lateinit var vuCouleur: View
    private lateinit var txtStatut: TextView
    private lateinit var layoutLignesInfo: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgNfc = findViewById(R.id.imgNfc)
        vuCouleur = findViewById(R.id.vuCouleur)
        txtStatut = findViewById(R.id.txtStatut)
        layoutLignesInfo = findViewById(R.id.layoutLignesInfo)

        traiterIntentEventuel(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        traiterIntentEventuel(intent)
    }

    private fun traiterIntentEventuel(intent: android.content.Intent?) {
        if (intent == null) return
        if (intent.action != android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED) return

        @Suppress("DEPRECATION")
        val tag = intent.getParcelableExtra<Tag>(android.nfc.NfcAdapter.EXTRA_TAG) ?: return
        lireTag(tag)
    }

    private fun lireTag(tag: Tag) {
        // Nouvelle lecture : on repart d'un ecran propre.
        layoutLignesInfo.removeAllViews()
        vuCouleur.visibility = View.GONE
        imgNfc.visibility = View.VISIBLE
        txtStatut.text = "Lecture en cours..."

        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            txtStatut.text = "Ce tag n'est pas compatible NFC-A"
            return
        }

        try {
            nfcA.connect()

            // Lecture en commandes ISO14443-3A brutes (0x30 + numero de page -> 16 octets/4 pages)
            // plutot que via la classe MifareUltralight : celle-ci depend d'une classification du
            // tag par Android qui a echoue sur un tag reel pourtant bien de ce type (test terrain).
            // Le decodeur a besoin jusqu'a la page 0x1F (poids), on lit large jusqu'a la page 31.
            val tampon = ByteArrayOutputStream()
            var page = 0
            while (page <= 28) {
                val reponse = nfcA.transceive(byteArrayOf(0x30, page.toByte()))
                tampon.write(reponse)
                page += 4
            }
            val dump = tampon.toByteArray()

            val info = DecodeurAnycubic.decoder(dump)

            if (info.matiere == null) {
                txtStatut.text = "Tag lu, mais pas au format Anycubic reconnu"
                return
            }

            afficherResultats(info)
        } catch (e: Exception) {
            txtStatut.text = "Erreur de lecture : ${e.message}"
        } finally {
            try { nfcA.close() } catch (e: Exception) { /* rien a faire */ }
        }
    }

    private fun ajouterLigneInfo(icone: Int, texte: String) {
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

    private fun afficherResultats(info: DecodeurAnycubic.InfoBobine) {
        val couleur = TableCouleursAnycubic.trouver(info.codeCouleur)

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
    }
}
