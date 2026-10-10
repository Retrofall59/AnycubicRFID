package com.tomyn.anycubicrfid

/**
 * Modele + parseur pour les fichiers JSON de bases de donnees de filaments (porte depuis
 * ElegooRFID le 10/10/2026 a la demande de Tomyn : "toutes les fonctions qu'il y a sur iligo...
 * base de données... tu les mets tous" - memes 36 fichiers communautaires, un par fabricant).
 *
 * Format communautaire observe sur les 36 fichiers (un par fabricant) :
 *   { "manufacturer": "...", "filaments": [ { "name", "material", "weights", "diameters",
 *     "extruder_temp" OU "extruder_temp_range", "bed_temp" OU "bed_temp_range" (optionnel),
 *     "colors": [ { "name", "hex" } OU { "name", "hexes", "multi_color_direction" } ] } ] }
 *
 * Difference avec la version ElegooRFID de ce fichier : le format Anycubic (voir
 * EncodeurAnycubic.kt) a une temperature de plateau ET une plage de temperature buse min/max/
 * defaut, contrairement au format Elegoo qui n'a pas de plateau du tout - donc ici "bed_temp"/
 * "bed_temp_range" EST lu (contrairement a la version Elegoo qui l'ignore, cf. son commentaire).
 * Une gamme sans temperature de plateau dans le JSON est quand meme grisee par
 * ImportBaseDonneesActivity, comme pour les autres champs manquants - pas de valeur inventee.
 *
 * Limites du format de tag Anycubic face a ce JSON, gerees en marquant l'information
 * manquante/incompatible plutot qu'en l'inventant :
 *   - une seule couleur RGB par tag -> une entree "hexes" (couleur multi-ton/degradee) devient une
 *     Couleur avec hexRgb=null, multiTon=true (voir ImportBaseDonneesActivity, qui la grise).
 *   - pas de longueur de bobine (en metres) dans ce format JSON -> MaterialsJsonMappingAnycubic
 *     l'estime a partir du poids/diametre/densite de la matiere (voir son commentaire), marquee
 *     "estimee" a l'ecran plutot que donnee comme fiable a 100%.
 *   - une matiere parmi une liste fixe (MATIERES dans CreationTagActivity) -> le rapprochement
 *     avec le champ JSON "material" est delegue a MaterialsJsonMappingAnycubic.kt, pas fait ici.
 */
object FilamentDatabase {

    data class Couleur(
        val nom: String,
        val hexRgb: String?, // 6 caracteres hexadecimaux (RRGGBB), ou null si non representable
        val multiTon: Boolean
    )

    data class Filament(
        val nomTemplate: String,  // ex. "Silk {color_name}" - voir nomAffiche()
        val materiauJson: String, // valeur brute du champ "material", ex. "PLA+", "PA6-CF"...
        val poidsGrammes: Int?,
        val diametreMm: Double?,
        val tempMinC: Int?,
        val tempMaxC: Int?,
        val tempPlateauC: Int?,
        val couleurs: List<Couleur>
    )

    data class Fabricant(val nom: String, val filaments: List<Filament>)

    /**
     * @return null si le contenu n'est pas du JSON valide, ou pas un objet avec un champ
     * "manufacturer" (chaine) et un champ "filaments" (tableau) - dans ce cas le fichier est
     * simplement ignore par l'ecran d'import plutot que de faire planter tout le chargement.
     */
    fun analyser(contenuJson: String): Fabricant? {
        val racine = try { MiniJson.parser(contenuJson) } catch (e: Exception) { return null }
        val obj = racine as? JsonValeur.Obj ?: return null
        val nomFabricant = obj.texte("manufacturer")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val filamentsJson = obj.tableau("filaments") ?: return null

        val filaments = filamentsJson.mapNotNull { fv -> analyserFilament(fv) }
        return Fabricant(nomFabricant, filaments)
    }

    private fun analyserFilament(fv: JsonValeur): Filament? {
        val f = fv as? JsonValeur.Obj ?: return null
        val nomTemplate = f.texte("name") ?: return null
        val materiau = f.texte("material") ?: return null

        val poids = (f.tableau("weights")?.firstOrNull() as? JsonValeur.Obj)
            ?.nombre("weight")?.let { Math.round(it).toInt() }

        val diametre = (f.tableau("diameters")?.firstOrNull() as? JsonValeur.Nombre)?.valeur

        var tempMin: Int? = null
        var tempMax: Int? = null
        val tempUnique = f.nombre("extruder_temp")
        if (tempUnique != null) {
            tempMin = Math.round(tempUnique).toInt()
            tempMax = tempMin
        } else {
            val plage = f.tableau("extruder_temp_range")
            if (plage != null && plage.size == 2) {
                tempMin = (plage[0] as? JsonValeur.Nombre)?.valeur?.let { Math.round(it).toInt() }
                tempMax = (plage[1] as? JsonValeur.Nombre)?.valeur?.let { Math.round(it).toInt() }
            }
        }

        var tempPlateau: Int? = null
        val plateauUnique = f.nombre("bed_temp")
        if (plateauUnique != null) {
            tempPlateau = Math.round(plateauUnique).toInt()
        } else {
            val plagePlateau = f.tableau("bed_temp_range")
            if (plagePlateau != null && plagePlateau.isNotEmpty()) {
                // Pas de "plateau min/max" separe cote Anycubic (un seul champ temperaturePlateau,
                // voir EncodeurAnycubic) : on prend la valeur haute de la plage, cote securisant
                // (eviter un plateau trop froid plutot que trop chaud n'a pas de sens physique
                // inverse - l'ordre des deux bornes dans "bed_temp_range" n'est pas garanti par le
                // format JSON, donc on prend le max des deux plutot que le dernier element).
                tempPlateau = plagePlateau.mapNotNull { (it as? JsonValeur.Nombre)?.valeur }
                    .maxOrNull()?.let { Math.round(it).toInt() }
            }
        }

        val couleurs = (f.tableau("colors") ?: emptyList()).mapNotNull { cv -> analyserCouleur(cv) }

        return Filament(nomTemplate, materiau, poids, diametre, tempMin, tempMax, tempPlateau, couleurs)
    }

    private fun analyserCouleur(cv: JsonValeur): Couleur? {
        val c = cv as? JsonValeur.Obj ?: return null
        val nom = c.texte("name") ?: return null
        val estMultiTon = c.tableau("hexes") != null
        val hex = if (estMultiTon) null else c.texte("hex")?.let { normaliserHex(it) }
        return Couleur(nom, hex, estMultiTon || hex == null)
    }

    /**
     * Garde les 6 derniers caracteres (RRGGBB) d'un hex a 8 caracteres (AARRGGBB, utilise par
     * certains fichiers pour les couleurs translucides) - le canal alpha n'a pas d'equivalent
     * dans le format de tag Anycubic (une seule couleur RGB, voir EncodeurAnycubic.kt).
     * @return null si la longueur ne correspond a aucun des deux formats connus.
     */
    private fun normaliserHex(hex: String): String? {
        val nettoye = hex.trim().removePrefix("#")
        if (!nettoye.all { it in "0123456789abcdefABCDEF" }) return null
        return when (nettoye.length) {
            6 -> nettoye.uppercase()
            8 -> nettoye.substring(2).uppercase()
            else -> null
        }
    }

    /** Nom affichable d'un filament pour une couleur donnee (substitue "{color_name}" dans le patron). */
    fun nomAffiche(filament: Filament, couleur: Couleur): String =
        filament.nomTemplate.replace("{color_name}", couleur.nom).trim()

    /**
     * Nom de gamme affichable dans le menu deroulant, sans couleur precise (voir le commentaire
     * equivalent dans la version ElegooRFID de ce fichier pour l'historique du bug corrige).
     */
    fun nomGammeAffiche(filament: Filament): String {
        val motif = filament.nomTemplate
            .replace("{color_name}", "")
            .trim(' ', '-', '|', '/')
            .replace(Regex("\\s+"), " ")
        return when {
            motif.isEmpty() -> filament.materiauJson
            motif.contains(filament.materiauJson, ignoreCase = true) -> motif
            else -> "$motif (${filament.materiauJson})"
        }
    }
}
