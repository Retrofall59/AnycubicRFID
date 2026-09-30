# AnycubicRFID

Appli Android pour lire les tags RFID des bobines Anycubic (gamme PLA Basic), et afficher
matière, couleur, poids et températures.

**Projet séparé de [BambuRfidReader](https://github.com/Retrofall59/BambuRfidReader) et de
[PrusaTag](https://github.com/Retrofall59/PrusaTag)** : troisième technologie NFC différente.
Bambu utilise du MIFARE Classic chiffré (clés dérivées cassées par la communauté), Prusa du
NFC-V/NDEF standard (spec ouverte OpenPrintTag), Anycubic un **NTAG213 en lecture libre, sans
aucun chiffrement, mais dans un format propriétaire non-NDEF** (l'octet magique NDEF est présent
dans la Capability Container, mais les données qui suivent ne forment pas un message NDEF valide).

## Fonctionnement

Approche le dos du téléphone d'une bobine Anycubic. L'appli lit le tag NFC-A/MifareUltralight,
décode le format maison qu'Anycubic y a écrit, et affiche matière, couleur (avec pastille),
poids, plage de température buse et plateau, et la référence produit complète.

## Format du tag

Établi entièrement à partir de **8 vraies bobines PLA Basic** scannées par l'utilisateur
(Purple, Silver, Pink, Orange, Beige, Gold, Cyan, Blue), comparées octet par octet : le poids, la
matière, les températures et la référence produit sont identiques sur les 8 (lus en direct par
l'appli, pas codés en dur), seule une page de 4 octets encode la couleur sous forme d'un code à
deux lettres.

**Sur la couleur** : la table (`TableCouleursAnycubic.kt`) ne contient que les 8 couleurs
réellement vérifiées, avec leur hex officiel (site store.anycubic.com). Une couleur non listée
s'affiche honnêtement comme "couleur inconnue (code XX)" plutôt qu'un nom deviné — voir le dossier
`tests/` pour la méthode de validation. Une demande a été envoyée à Anycubic pour obtenir la liste
complète des codes couleur ; si elle aboutit, la table sera complétée. Sinon, elle s'enrichira au
fil des retours, comme pour les deux autres projets.

Un champ (page 0x14, 4 octets) reste non expliqué — probablement une somme de contrôle, mais sans
algorithme identifié sur seulement 8 échantillons. Il n'est pas nécessaire au fonctionnement de
l'appli et n'est pas interprété.

## Distribution

Comme pour les deux autres projets : chaque mise à jour est livrée sous forme de zip complet du
dépôt à uploader sur GitHub, ce qui déclenche automatiquement la compilation de l'APK via GitHub
Actions.

## Historique des versions

Voir [CHANGELOG.md](CHANGELOG.md).
