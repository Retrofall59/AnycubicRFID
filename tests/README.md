# Tests — décodage Anycubic

## Lancer

```
cd tests
kotlinc ../app/src/main/java/com/tomyn/anycubicrfid/DecodeurAnycubic.kt \
        ../app/src/main/java/com/tomyn/anycubicrfid/TableCouleursAnycubic.kt \
        TestDecodeurAnycubic.kt -include-runtime -d test.jar
java -jar test.jar
```

## Contenu

- **`dumps/*.bin`** — dumps bruts réels (180 octets chacun, pages NTAG213 complètes) de 8 vraies
  bobines Anycubic PLA Basic (Purple, Silver, Pink, Orange, Beige, Gold, Cyan, Blue), scannées par
  l'utilisateur avec NFC Tools (export HEX). `purple.bin` a été vérifié octet pour octet contre le
  fichier d'export original.
- **`TestDecodeurAnycubic.kt`** — décode les 8 dumps et vérifie que les champs communs (matière,
  poids, températures, référence produit) sont identiques sur les 8, que le code couleur lu
  correspond à la vraie couleur de chaque bobine, et qu'un code couleur inconnu ne renvoie jamais
  un nom deviné.

Avant de toucher au décodeur (`DecodeurAnycubic.kt`) ou à la table de couleurs, relance ce test.

## Ajouter une couleur

Quand une nouvelle couleur est scannée : exporter son dump (NFC Tools → Lire la mémoire → export
HEX), l'ajouter dans `dumps/`, vérifier le nom exact sur l'étiquette, et ajouter l'entrée dans
`TableCouleursAnycubic.kt` — avec un vrai hex (site officiel Anycubic de préférence), jamais une
valeur devinée.
