# CertCheck 🔒

[![Android CI](https://github.com/flamme-demon/certchecker/actions/workflows/android.yml/badge.svg)](https://github.com/flamme-demon/certchecker/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/flamme-demon/certchecker)](https://github.com/flamme-demon/certchecker/releases)

**Français** | [English](#english)

---

## Français

**Vérificateur de certificats SSL/TLS du point de vue Android.**

Certains certificats fonctionnent parfaitement dans un navigateur mais échouent dans une application Android native. CertCheck diagnostique ces problèmes directement depuis votre appareil Android.

### Pourquoi ?

Android utilise son propre **trust store système** qui diffère de celui des navigateurs :

- **Chrome** embarque son propre trust store et peut résoudre les chaînes incomplètes via AIA fetching
- **Android natif** utilise le trust store du système, ne fait pas d'AIA fetching, et exige des SANs (pas de fallback CN)

#### Problèmes courants détectés

| Problème | Navigateur | App Android |
|----------|-----------|-------------|
| Certificat intermédiaire manquant | ✅ Fonctionne (cache/AIA) | ❌ Échoue |
| Nouvelle CA pas encore dans Android | ✅ Fonctionne | ❌ Échoue |
| Certificat avec CN uniquement (sans SAN) | ⚠️ Peut fonctionner | ❌ Échoue (API 26+) |
| Cross-signing expiré (ex: Let's Encrypt) | ✅ Fonctionne | ❌ Échoue (vieux Android) |
| Certificat auto-signé | ⚠️ Avertissement | ❌ Échoue |

### Fonctionnalités

- 🔍 Vérification complète du certificat depuis le **trust store Android**, avec la **chaîne d'exceptions réelle** conservée (`CertPathValidatorException` et causes) — plus aucune erreur avalée
- 🧭 Diagnostic de la cause exacte d'un échec de confiance : racine absente, intermédiaire manquant, ancre homonyme à clé différente, certificat auto-signé…
- 🔗 **Résolution AIA (caIssuers)** : distingue « il manque un intermédiaire » de « racine inconnue d'Android », même quand le serveur ne sert pas la racine (bonne pratique)
- 📱 **Corrélation racine / terminal** : âge de la racine confronté à la version d'Android et au patch système de l'appareil ; magasin figé avant Android 14, mis à jour via Google Play system updates à partir d'Android 14
- 🌐 **Détection de dépendance au SNI** : le serveur sert-t-il un certificat par défaut (souvent auto-signé) aux clients sans SNI ?
- 🏷️ Vérification du hostname (SNI + SAN matching)
- ⏰ Détection d'expiration et pré-expiration
- 🔐 Audit cryptographique (algorithmes faibles, tailles de clé)
- 📊 Analyse du cipher suite : force, Forward Secrecy, compatibilité (Android, iOS/Safari, navigateurs, systèmes anciens)
- 📋 Détails complets : fingerprints SHA-256/SHA-1, SANs, numéros de série
- ⭐ Favoris, 📜 historique des vérifications (Room), 🔄 vérification quotidienne automatique (WorkManager)
- 📲 Widget d'accueil rapide
- 🎨 Material Design 3 avec support thème dynamique

### Stack technique

- **Kotlin** + **Jetpack Compose**
- **Material 3** (Material You)
- **Zéro dépendance crypto externe** — uniquement les APIs Android/Java standard
- `target SDK 35` / `min SDK 26`

### Build

```bash
git clone https://github.com/flamme-demon/certchecker.git
cd certchecker
./gradlew assembleDebug
```

L'APK sera dans `app/build/outputs/apk/debug/`.

Les APK pré-compilés sont disponibles dans les [Releases GitHub](https://github.com/flamme-demon/certchecker/releases) (le APK `debug` est signé avec la clé de debug : installable directement ; le APK `release` est non signé, à signer avant distribution).

### Intégration continue

Chaque push/PR déclenche le workflow **Android CI** (`.github/workflows/android.yml`) : build des APK debug + release, téléchargeables depuis les artifacts du run.

La publication d'une release se déclenche en poussant un tag `vX.Y.Z` :

```bash
git tag v1.2.1
git push origin v1.2.1
```

Le workflow build les APK et publie automatiquement la release GitHub correspondante avec les APK joints.

### Architecture

```
com.flammedemon.certcheck/
├── MainActivity.kt              # Point d'entrée
├── MainViewModel.kt             # State management
├── UserPreferences.kt           # Préférences utilisateur
├── model/
│   └── CertCheckResult.kt       # Data classes
├── network/
│   └── SSLChecker.kt            # Cœur de la vérification SSL + diagnostics
├── database/                    # Room : favoris + historique
├── worker/
│   └── DailyCertificateCheckWorker.kt   # Vérification quotidienne
├── widget/
│   └── CertCheckWidgetProvider.kt       # Widget d'accueil
└── ui/
    ├── components/Components.kt # Composants Compose réutilisables
    ├── screens/Screens.kt       # Écrans Home + Résultat
    └── theme/Theme.kt           # Thème Material 3
```

### Licence

MIT License — voir [LICENSE](LICENSE)

---

## English

**SSL/TLS certificate checker from Android's point of view.**

Some certificates work perfectly in a browser but fail in a native Android app. CertCheck diagnoses these problems directly from your Android device.

### Why?

Android uses its own **system trust store**, which differs from browser trust stores:

- **Chrome** ships its own trust store and can resolve incomplete chains via AIA fetching
- **Native Android** uses the system trust store, does no AIA fetching, and requires SANs (no CN fallback)

#### Commonly detected problems

| Problem | Browser | Android app |
|---------|---------|-------------|
| Missing intermediate certificate | ✅ Works (cache/AIA) | ❌ Fails |
| New CA not yet in Android | ✅ Works | ❌ Fails |
| Certificate with CN only (no SAN) | ⚠️ May work | ❌ Fails (API 26+) |
| Expired cross-signing (e.g. Let's Encrypt) | ✅ Works | ❌ Fails (old Android) |
| Self-signed certificate | ⚠️ Warning | ❌ Fails |

### Features

- 🔍 Full certificate check against the **Android trust store**, keeping the **real exception chain** (`CertPathValidatorException` and causes) — no more swallowed errors
- 🧭 Root-cause diagnosis of trust failures: absent root, missing intermediate, same-name anchor with a different key, self-signed certificate…
- 🔗 **AIA resolution (caIssuers)**: distinguishes "an intermediate is missing" from "root unknown to Android", even when the server omits the root (recommended practice)
- 📱 **Root / device correlation**: root age compared to the device's Android version and security patch level; store frozen before Android 14, updated via Google Play system updates from Android 14 on
- 🌐 **SNI dependency detection**: does the server hand a default (often self-signed) certificate to clients without SNI?
- 🏷️ Hostname verification (SNI + SAN matching)
- ⏰ Expiry and pre-expiry detection
- 🔐 Cryptographic audit (weak algorithms, key sizes)
- 📋 Full details: SHA-256/SHA-1 fingerprints, SANs, serial numbers
- 📊 Cipher suite analysis: strength, Forward Secrecy, compatibility (Android, iOS/Safari, browsers, legacy systems)
- ⭐ Favorites, 📜 check history (Room), 🔄 automatic daily checks (WorkManager)
- 📲 Home screen widget
- 🎨 Material Design 3 with dynamic theme support

### Tech stack

- **Kotlin** + **Jetpack Compose**
- **Material 3** (Material You)
- **No external crypto dependency** — standard Android/Java APIs only
- `target SDK 35` / `min SDK 26`

### Build

```bash
git clone https://github.com/flamme-demon/certchecker.git
cd certchecker
./gradlew assembleDebug
```

The APK will be in `app/build/outputs/apk/debug/`.

Pre-built APKs are available on the [GitHub Releases](https://github.com/flamme-demon/certchecker/releases) page (the `debug` APK is debug-signed and directly installable; the `release` APK is unsigned and must be signed before distribution).

### Continuous integration

Every push/PR triggers the **Android CI** workflow (`.github/workflows/android.yml`): it builds the debug + release APKs, downloadable from the run artifacts.

A release is published by pushing a `vX.Y.Z` tag:

```bash
git tag v1.2.1
git push origin v1.2.1
```

The workflow builds the APKs and automatically publishes the matching GitHub release with the APKs attached.

### Architecture

```
com.flammedemon.certcheck/
├── MainActivity.kt              # Entry point
├── MainViewModel.kt             # State management
├── UserPreferences.kt           # User preferences
├── model/
│   └── CertCheckResult.kt       # Data classes
├── network/
│   └── SSLChecker.kt            # SSL check core + diagnostics
├── database/                    # Room: favorites + history
├── worker/
│   └── DailyCertificateCheckWorker.kt   # Daily checks
├── widget/
│   └── CertCheckWidgetProvider.kt       # Home screen widget
└── ui/
    ├── components/Components.kt # Reusable Compose components
    ├── screens/Screens.kt       # Home + Result screens
    └── theme/Theme.kt           # Material 3 theme
```

### License

MIT License — see [LICENSE](LICENSE)
