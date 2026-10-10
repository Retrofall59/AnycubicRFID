package com.tomyn.anycubicrfid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scan du QR des etiquettes directement depuis l'appareil photo de l'appli (porte depuis
 * ElegooRFID le 10/10/2026, a la demande de Tomyn - meme tag NTAG213, meme besoin : pouvoir
 * consulter/reimprimer une bobine a partir de l'etiquette papier sans avoir le tag NFC a portee).
 * CameraX pour la previsualisation et l'analyse d'image, ZXing pour le decodage.
 *
 * Reprend directement la version finale, deja corrigee, du meme ecran sur ElegooRFID - pas
 * besoin de repasser par les trois rounds de bugs reels rencontres la-bas (QR imprime illisible
 * par rendu vectoriel module par module -> rendu bitmap ; resolution d'analyse par defaut trop
 * basse -> ResolutionSelector force a 1920x1440 ; mise au point continue pas assez reactive sur
 * une etiquette tenue de pres -> declenchement explicite au centre de l'apercu) :
 * - Le dessin du QR cote etiquette (voir MainActivity.dessinerQr) utilise d'emblee un Bitmap
 *   intermediaire plutot que des rectangles vectoriels par module.
 * - L'analyse CameraX est d'emblee configuree en haute resolution + mise au point centree
 *   explicite ci-dessous.
 *
 * MISE EN GARDE (honnetete "cash et franc", comme pour ElegooRFID) : ce code est repris quasi
 * a l'identique, donc la logique elle-meme a deja ete validee en conditions reelles sur
 * ElegooRFID. Ce qui n'a PAS ete reverifie ici : que l'impression des etiquettes Anycubic (mise
 * en page differente, voir dessinerEtiquette) produit bien un QR net au tirage - a confirmer par
 * Damdam2959 sur sa prochaine planche imprimee.
 */
class ScanQrActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var executeurAnalyse: ExecutorService
    private val lecteurQr = MultiFormatReader()
    private val hintsDecodage = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )
    private var dejaTraite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_qr)
        previewView = findViewById(R.id.previewViewScanQr)
        findViewById<ImageButton>(R.id.btnFermerScanQr).setOnClickListener { finish() }
        executeurAnalyse = Executors.newSingleThreadExecutor()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            demarrerCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CODE_PERMISSION_CAMERA)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CODE_PERMISSION_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            demarrerCamera()
        } else {
            Toast.makeText(this, "Permission caméra refusée, impossible de scanner un QR.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun demarrerCamera() {
        val fournisseurFutur = ProcessCameraProvider.getInstance(this)
        fournisseurFutur.addListener({
            try {
                val fournisseur = fournisseurFutur.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val selecteurResolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
                val analyse = ImageAnalysis.Builder()
                    .setResolutionSelector(selecteurResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyse.setAnalyzer(executeurAnalyse) { image -> analyserImage(image) }
                fournisseur.unbindAll()
                val camera = fournisseur.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyse)

                val pointCentral = previewView.meteringPointFactory.createPoint(
                    previewView.width / 2f, previewView.height / 2f
                )
                camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pointCentral).build())
            } catch (e: Exception) {
                Toast.makeText(this, "Impossible de démarrer la caméra : ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyserImage(image: ImageProxy) {
        if (dejaTraite) {
            image.close()
            return
        }
        try {
            val plan = image.planes[0]
            val tampon = plan.buffer
            val donnees = ByteArray(tampon.remaining())
            tampon.get(donnees)
            val source = PlanarYUVLuminanceSource(
                donnees, plan.rowStride, image.height,
                0, 0, image.width, image.height, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val resultat = try {
                lecteurQr.decode(bitmap, hintsDecodage)
            } catch (e: NotFoundException) {
                null
            }
            if (resultat != null) {
                dejaTraite = true
                runOnUiThread { validerResultat(resultat.text) }
            }
        } catch (e: Exception) {
            // Image illisible sur ce cliche (flou, mauvais cadrage...) - pas grave, le flux
            // continue et la prochaine image sera retentee automatiquement.
        } finally {
            image.close()
        }
    }

    private fun validerResultat(texte: String) {
        val resultat = Intent()
        resultat.putExtra(EXTRA_LIEN, texte)
        setResult(Activity.RESULT_OK, resultat)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        executeurAnalyse.shutdown()
    }

    companion object {
        private const val CODE_PERMISSION_CAMERA = 501
        const val EXTRA_LIEN = "lien_qr"
    }
}
