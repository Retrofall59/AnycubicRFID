# Changelog

## v1.5 (build 6)

Port de toutes les fonctions de BambuRfidReader qui ont du sens pour Anycubic (sauf la recherche
d'équivalent Bambu à partir d'un code couleur, propre à ce projet-là) :

- **Exporter le dernier dump** (texte, vers Téléchargements/Drive via le sélecteur Android).
- **Copier / Partager** le résultat.
- **Historique des scans** (CSV local : date, UID, matière, couleur), avec export et suppression.
- **Copier le rapport de compatibilité** (modèle, Android, NFC actif, dernier scan) — pour signaler un bug sur le forum.
- **Bandeau "NFC désactivé"** avec raccourci vers les réglages.
- **Vibration** de confirmation à chaque lecture réussie.
- **Le résultat affiché et la file d'étiquettes survivent maintenant à une rotation d'écran** — directement fait dès cette version, plutôt que d'attendre de reproduire les deux bugs successifs déjà corrigés sur BambuRfidReader (texte affiché perdu, puis variables des boutons Exporter/Copier/Partager perdues séparément).

Non portées, sciemment : le diagnostic sain/limite/défaillant et le nombre de passes réglable (spécifiques aux ratés d'authentification MIFARE Classic, sans équivalent ici où la lecture est directe), et bien sûr la recherche de couleur Bambu équivalente.

## v1.4 (build 5)

- Ajout de l'impression d'étiquettes, sur le même principe que BambuRfidReader : chaque bobine scannée s'ajoute à une file d'attente, le bouton propose d'imprimer une ou plusieurs étiquettes d'un coup (grille 3×7 sur page A4, à découper). Chaque étiquette affiche matière, couleur (avec pastille), code hex, poids et températures.

## v1.3 (build 4)

- Corrige : quand l'appli était déjà ouverte, approcher un tag repassait par le système Android (qui pouvait réafficher un écran système) au lieu d'être lu directement, contrairement à BambuRfidReader. Ajout du **mode lecteur** (`enableReaderMode`), activé dès que l'appli est au premier plan : elle intercepte désormais le tag directement, sans repasser par le dispatch Android. Le lancement à froid (appli fermée, on approche un tag) continue de fonctionner comme avant.

## v1.2 (build 3)

**Corrige "Impossible de trouver une application prenant en charge la balise NFC"** au premier vrai test terrain.
- Cause : le filtre NFC (`nfc_tech_filter.xml`) déclarait NfcA et MifareUltralight dans un seul bloc, ce qui exige les deux technologies **à la fois** sur le tag. Sur le téléphone testé, le tag n'a apparemment pas été classé "MifareUltralight" par le système malgré un comportement Ultralight réel (puce probablement clone/rebrandée), donc aucune correspondance et Android n'a proposé aucune appli.
- Corrigé en deux temps : le filtre déclare maintenant NfcA et MifareUltralight dans deux blocs séparés (l'un ou l'autre suffit), et la lecture elle-même n'utilise plus la classe `MifareUltralight` (qui dépend de cette même classification fragile) mais des commandes NFC-A brutes (`0x30` + numéro de page), qui fonctionnent sur n'importe quel tag NFC-A quelle que soit sa classification.
- Aucun changement côté décodage (toujours 74 vérifications, tout passe).

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
