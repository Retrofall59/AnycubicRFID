package com.tomyn.anycubicrfid

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnRetour).setOnClickListener { finish() }

        val interrupteur = findViewById<Switch>(R.id.interrupteurVibration)
        interrupteur.isChecked = GestionnaireParametres.lireVibrationFinLecture(this)
        interrupteur.setOnCheckedChangeListener { _, active ->
            GestionnaireParametres.ecrireVibrationFinLecture(this, active)
        }

        findViewById<TextView>(R.id.texteVersion).text = try {
            val infos = packageManager.getPackageInfo(packageName, 0)
            "AnycubicRFID — version ${infos.versionName}"
        } catch (e: PackageManager.NameNotFoundException) {
            "AnycubicRFID"
        }
    }
}
