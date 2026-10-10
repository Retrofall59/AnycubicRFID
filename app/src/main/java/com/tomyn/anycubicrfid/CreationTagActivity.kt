package com.tomyn.anycubicrfid

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Formulaire de creation d'un tag Anycubic "a la main" (porte depuis ElegooRFID le 10/10/2026 a
 * la demande de Tomyn, avec la quantite incluse directement - voir EncodeurAnycubic.kt pour la
 * construction du dump.
 *
 * Renvoie le dump construit (128 octets) dans l'extra "dump" du resultat si quantite == 1 -
 * MainActivity le traite comme une bobine lue (voir onActivityResult, CODE_CREATION) : decodage +
 * affichage + boutons Cloner/Exporter/Imprimer actives, sans avoir touche de tag physique.
 *
 * Quantite > 1 : meme principe que CreationTagActivity.dumpsGeneres sur ElegooRFID (v0.32) -
 * genere directement N dumps identiques, MainActivity enchaine sur l'ecriture guidee bobine par
 * bobine.
 *
 * Contrairement a ElegooRFID, pas besoin d'une base de matieres figee (MaterialsElegoo) : la
 * matiere est juste le texte choisi dans le spinner, et la couleur s'ecrit en RGB direct (voir
 * EncodeurAnycubic) - le code 2 lettres n'est plus qu'indicatif pour la reference produit.
 */
class CreationTagActivity : AppCompatActivity() {

    companion object {
        /** Garde-fou contre une faute de frappe, pas une limite technique du format. */
        const val QUANTITE_MAX = 500
        var dumpsGeneres: List<Pair<String, ByteArray>> = emptyList()

        // Visible (non-private) depuis le 10/10/2026 : reutilisee par MaterialsJsonMappingAnycubic
        // pour rapprocher les matieres des fichiers JSON importes (voir ImportBaseDonneesActivity)
        // de cette meme liste - une seule source de verite pour "quelles matieres cette appli gere".
        val MATIERES = listOf("PLA", "PETG", "ABS", "TPU", "ASA", "PA", "PC")
    }

    private lateinit var spinnerMatiere: Spinner
    private lateinit var champCodeCouleur: EditText
    private lateinit var champCouleur: EditText
    private lateinit var apercuCouleur: View
    private lateinit var champPoids: EditText
    private lateinit var champDiametre: EditText
    private lateinit var champLongueur: EditText
    private lateinit var champTempDefaut: EditText
    private lateinit var champTempMin: EditText
    private lateinit var champTempMax: EditText
    private lateinit var champTempPlateau: EditText
    private lateinit var champQuantite: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_creation_tag)

        findViewById<ImageButton>(R.id.btnRetourCreation).setOnClickListener { finish() }

        spinnerMatiere = findViewById(R.id.spinnerMatiere)
        champCodeCouleur = findViewById(R.id.champCodeCouleur)
        champCouleur = findViewById(R.id.champCouleur)
        apercuCouleur = findViewById(R.id.apercuCouleur)
        champPoids = findViewById(R.id.champPoids)
        champDiametre = findViewById(R.id.champDiametre)
        champLongueur = findViewById(R.id.champLongueur)
        champTempDefaut = findViewById(R.id.champTempDefaut)
        champTempMin = findViewById(R.id.champTempMin)
        champTempMax = findViewById(R.id.champTempMax)
        champTempPlateau = findViewById(R.id.champTempPlateau)
        champQuantite = findViewById(R.id.champQuantite)

        spinnerMatiere.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MATIERES)

        champCouleur.setOnFocusChangeListener { _, _ -> actualiserApercuCouleur() }

        findViewById<Button>(R.id.btnCreerTag).setOnClickListener { creerTag() }
    }

    private fun actualiserApercuCouleur() {
        try {
            val argb = Color.parseColor("#${champCouleur.text.toString().trim()}")
            apercuCouleur.backgroundTintList = ColorStateList.valueOf(argb)
        } catch (e: Exception) { /* hex incomplet/invalide pendant la saisie : pas grave, on retentera a la validation */ }
    }

    private fun creerTag() {
        val matiere = MATIERES[spinnerMatiere.selectedItemPosition]

        val codeCouleur = champCodeCouleur.text.toString().trim().uppercase().ifEmpty { "XX" }
        if (codeCouleur.length != 2) {
            Toast.makeText(this, "Code couleur invalide : 2 lettres attendues (ou laisse vide).", Toast.LENGTH_LONG).show()
            return
        }

        val couleurHex = champCouleur.text.toString().trim().uppercase()
        if (couleurHex.length != 6 || couleurHex.any { it !in "0123456789ABCDEF" }) {
            Toast.makeText(this, "Couleur invalide : 6 caractères hexadécimaux attendus (ex. 6A6DCD).", Toast.LENGTH_LONG).show()
            return
        }

        val poids = champPoids.text.toString().trim().toIntOrNull()
        if (poids == null || poids <= 0) {
            Toast.makeText(this, "Poids invalide.", Toast.LENGTH_SHORT).show()
            return
        }

        val diametre = champDiametre.text.toString().trim().replace(",", ".").toDoubleOrNull()
        if (diametre == null || diametre <= 0) {
            Toast.makeText(this, "Diamètre invalide.", Toast.LENGTH_SHORT).show()
            return
        }

        val longueur = champLongueur.text.toString().trim().toIntOrNull()
        if (longueur == null || longueur <= 0) {
            Toast.makeText(this, "Longueur invalide.", Toast.LENGTH_SHORT).show()
            return
        }

        val tempDefaut = champTempDefaut.text.toString().trim().toIntOrNull()
        val tempMin = champTempMin.text.toString().trim().toIntOrNull()
        val tempMax = champTempMax.text.toString().trim().toIntOrNull()
        if (tempDefaut == null || tempMin == null || tempMax == null || tempMin <= 0 || tempMax <= 0 || tempMin > tempMax) {
            Toast.makeText(this, "Températures buse invalides (min ≤ max, les trois requises).", Toast.LENGTH_LONG).show()
            return
        }

        val tempPlateau = champTempPlateau.text.toString().trim().toIntOrNull()
        if (tempPlateau == null || tempPlateau <= 0) {
            Toast.makeText(this, "Température plateau invalide.", Toast.LENGTH_SHORT).show()
            return
        }

        val quantiteTexte = champQuantite.text.toString().trim()
        val quantite = if (quantiteTexte.isEmpty()) 1 else quantiteTexte.toIntOrNull()
        if (quantite == null || quantite < 1 || quantite > QUANTITE_MAX) {
            Toast.makeText(this, "Quantité invalide (entre 1 et $QUANTITE_MAX).", Toast.LENGTH_LONG).show()
            return
        }

        val dump = EncodeurAnycubic.construireDump(
            matiere = matiere,
            codeCouleur = codeCouleur,
            couleurHex = couleurHex,
            poidsGrammes = poids,
            diametreMm = diametre,
            longueurMetres = longueur,
            temperaturePlateau = tempPlateau,
            temperatureBuseDefaut = tempDefaut,
            temperatureBuseMin = tempMin,
            temperatureBuseMax = tempMax
        )

        if (quantite == 1) {
            val resultat = Intent()
            resultat.putExtra("dump", dump)
            setResult(Activity.RESULT_OK, resultat)
            finish()
            return
        }

        dumpsGeneres = (1..quantite).map { index ->
            "$matiere #$index/$quantite" to dump.copyOf()
        }
        setResult(Activity.RESULT_OK, Intent())
        finish()
    }
}
