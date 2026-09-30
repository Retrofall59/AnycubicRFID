# Changelog

## v1.1 (build 2)

- Refonte visuelle : reprend la charte graphique de BambuRfidReader et PrusaTag (fond clair,
  carte avec barre d'accent en haut, icônes par champ avec animation en cascade, pastille de
  couleur ronde), avec le noir/orange Anycubic à la place du teal/orange Bambu. L'écran sombre et
  minimal de la v1.0 est remplacé.
- Aucun changement côté décodage.

## v1.0 (build 1)

Première version.

- Lecture NFC-A/MifareUltralight des tags Anycubic (NTAG213, format maison non chiffré).
- Décodeur (`DecodeurAnycubic.kt`) écrit à partir de zéro, validé sur 8 vraies bobines PLA Basic
  (Purple, Silver, Pink, Orange, Beige, Gold, Cyan, Blue) : matière, poids, plage de température
  buse, température plateau et référence produit lus en direct sur le tag.
- Table de 8 couleurs vérifiées (nom + hex officiel Anycubic), avec repli honnête
  ("couleur inconnue") pour tout code non encore rencontré — jamais de nom deviné.
- Non testé sur un vrai lecteur NFC en conditions réelles (seul le décodage logiciel a pu être
  vérifié en local, sur les dumps fournis) — premier vrai test à faire sur le terrain.
