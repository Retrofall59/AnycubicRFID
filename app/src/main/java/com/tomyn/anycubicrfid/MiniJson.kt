package com.tomyn.anycubicrfid

/**
 * Mini-parseur JSON ecrit a la main (ajoute le 09/10/2026 pour FilamentDatabase.kt, voir
 * ImportBaseDonneesActivity).
 *
 * Pourquoi pas org.json (la lib standard presente sur tout appareil Android) : org.json fait
 * partie du framework Android, pas du JDK - donc indisponible pour compiler/tester ce fichier en
 * dehors d'un vrai projet Gradle (comme c'est le cas ici, verifie par kotlinc + stubs maison, voir
 * TestDecodeurElegoo.kt). Un parseur maison, lui, tourne pour de vrai aussi bien ici que sur le
 * telephone de Tomyn - pas de decalage entre "ce qui compile" et "ce qui marche".
 *
 * Volontairement minimal : gere exactement le sous-ensemble JSON observe dans les 36 fichiers de
 * base de filaments fournis par Tomyn (objets, tableaux, chaines avec les echappements standards,
 * nombres, booleens, null) - pas une impl complete de la RFC (pas d'unicode \uXXXX, jamais vu dans
 * ces fichiers ; une chaine qui en contiendrait serait simplement mal decodee plutot que de faire
 * echouer tout le fichier).
 */
sealed class JsonValeur {
    data class Obj(val champs: Map<String, JsonValeur>) : JsonValeur()
    data class Arr(val elements: List<JsonValeur>) : JsonValeur()
    data class Texte(val valeur: String) : JsonValeur()
    data class Nombre(val valeur: Double) : JsonValeur()
    data class Bool(val valeur: Boolean) : JsonValeur()
    object Null : JsonValeur()
}

/** Champ texte d'un objet JSON, ou null si absent ou d'un autre type. */
fun JsonValeur.Obj.texte(cle: String): String? = (champs[cle] as? JsonValeur.Texte)?.valeur

/** Champ numerique d'un objet JSON, ou null si absent ou d'un autre type. */
fun JsonValeur.Obj.nombre(cle: String): Double? = (champs[cle] as? JsonValeur.Nombre)?.valeur

/** Champ tableau d'un objet JSON, ou null si absent ou d'un autre type. */
fun JsonValeur.Obj.tableau(cle: String): List<JsonValeur>? = (champs[cle] as? JsonValeur.Arr)?.elements

class JsonInvalideException(message: String) : Exception(message)

object MiniJson {

    fun parser(texte: String): JsonValeur {
        val p = Parseur(texte)
        val valeur = p.parserValeur()
        p.ignorerEspaces()
        return valeur
    }

    private class Parseur(private val s: String) {
        var i = 0

        fun ignorerEspaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parserValeur(): JsonValeur {
            ignorerEspaces()
            if (i >= s.length) throw JsonInvalideException("fin de fichier inattendue")
            return when (s[i]) {
                '{' -> parserObjet()
                '[' -> parserTableau()
                '"' -> JsonValeur.Texte(parserChaine())
                't' -> { attendre("true"); JsonValeur.Bool(true) }
                'f' -> { attendre("false"); JsonValeur.Bool(false) }
                'n' -> { attendre("null"); JsonValeur.Null }
                else -> parserNombre()
            }
        }

        private fun attendre(mot: String) {
            if (i + mot.length > s.length || s.substring(i, i + mot.length) != mot) {
                throw JsonInvalideException("mot-cle attendu : $mot (position $i)")
            }
            i += mot.length
        }

        private fun parserObjet(): JsonValeur.Obj {
            i++ // '{'
            val champs = mutableMapOf<String, JsonValeur>()
            ignorerEspaces()
            if (i < s.length && s[i] == '}') { i++; return JsonValeur.Obj(champs) }
            while (true) {
                ignorerEspaces()
                val cle = parserChaine()
                ignorerEspaces()
                if (i >= s.length || s[i] != ':') throw JsonInvalideException("':' attendu (position $i)")
                i++
                champs[cle] = parserValeur()
                ignorerEspaces()
                if (i >= s.length) throw JsonInvalideException("'}' attendu")
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; break }
                    else -> throw JsonInvalideException("',' ou '}' attendu (position $i)")
                }
            }
            return JsonValeur.Obj(champs)
        }

        private fun parserTableau(): JsonValeur.Arr {
            i++ // '['
            val elements = mutableListOf<JsonValeur>()
            ignorerEspaces()
            if (i < s.length && s[i] == ']') { i++; return JsonValeur.Arr(elements) }
            while (true) {
                elements.add(parserValeur())
                ignorerEspaces()
                if (i >= s.length) throw JsonInvalideException("']' attendu")
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; break }
                    else -> throw JsonInvalideException("',' ou ']' attendu (position $i)")
                }
            }
            return JsonValeur.Arr(elements)
        }

        private fun parserChaine(): String {
            ignorerEspaces()
            if (i >= s.length || s[i] != '"') throw JsonInvalideException("chaîne attendue (position $i)")
            i++
            val sb = StringBuilder()
            while (i < s.length && s[i] != '"') {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'u' -> {
                            if (i + 4 < s.length) {
                                val code = s.substring(i + 1, i + 5).toIntOrNull(16)
                                if (code != null) { sb.append(code.toChar()); i += 4 }
                            }
                        }
                        else -> sb.append(s[i])
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            if (i >= s.length) throw JsonInvalideException("chaîne non terminée")
            i++ // '"' finale
            return sb.toString()
        }

        private fun parserNombre(): JsonValeur.Nombre {
            val debut = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val brut = s.substring(debut, i)
            val valeur = brut.toDoubleOrNull() ?: throw JsonInvalideException("nombre invalide : '$brut' (position $debut)")
            return JsonValeur.Nombre(valeur)
        }
    }
}
