# GestionMagasin Pro — ORCA DECO

Application Android native (Kotlin, Jetpack Compose, Room, ML Kit, ZXing).

## Compiler l'APK debug
- Prérequis : JDK 17 et Android SDK (API 34).
- Ligne de commande : `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Android Studio : Open → dossier du projet → Build > Build APK(s).
- Sans PC : poussez le dossier sur GitHub, le workflow `.github/workflows/build-apk.yml` produit l'APK (onglet Actions > Artifacts).

## Notes
- Export/import Excel et Word : module interne `OfficeLite` (Apache POI retiré, incompatible Android).
- Export Word des étiquettes : `RvoitureDocxBuilder` à partir de `assets/RVOITURE.docx` (cadres inutilisés et pages vides supprimés).
- Logo : `res/drawable/logo_orca_deco.jpg` (extrait de RVOITURE.docx).
