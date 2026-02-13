# CertCheck 🔒

**Vérificateur de certificats SSL/TLS du point de vue Android.**

Certains certificats fonctionnent parfaitement dans un navigateur mais échouent dans une application Android native. CertCheck diagnostique ces problèmes directement depuis votre appareil Android.

## Pourquoi ?

Android utilise son propre **trust store système** qui diffère de celui des navigateurs :

- **Chrome** embarque son propre trust store et peut résoudre les chaînes incomplètes via AIA fetching
- **Android natif** utilise le trust store du système, ne fait pas d'AIA fetching, et exige des SANs (pas de fallback CN)

### Problèmes courants détectés

| Problème | Navigateur | App Android |
|----------|-----------|-------------|
| Certificat intermédiaire manquant | ✅ Fonctionne (cache/AIA) | ❌ Échoue |
| Nouvelle CA pas encore dans Android | ✅ Fonctionne | ❌ Échoue |
| Certificat avec CN uniquement (sans SAN) | ⚠️ Peut fonctionner | ❌ Échoue (API 26+) |
| Cross-signing expiré (ex: Let's Encrypt) | ✅ Fonctionne | ❌ Échoue (vieux Android) |
| Certificat auto-signé | ⚠️ Avertissement | ❌ Échoue |

## Fonctionnalités

- 🔍 Vérification complète du certificat SSL depuis le trust store Android
- 🔗 Analyse de la chaîne de certificats (intermédiaires manquants)
- 🏷️ Vérification du hostname (SNI + SAN matching)
- ⏰ Détection d'expiration et pré-expiration
- 🔐 Audit cryptographique (algorithmes faibles, tailles de clé)
- 📋 Détails complets : fingerprints SHA-256/SHA-1, SANs, numéros de série
- 📜 Historique des vérifications (en session)
- 🎨 Material Design 3 avec support thème dynamique

## Stack technique

- **Kotlin** + **Jetpack Compose**
- **Material 3** (Material You)
- **Zéro dépendance externe** — uniquement les APIs Android/Java standard
- `target SDK 35` / `min SDK 26`

## Build

```bash
git clone https://github.com/votre-user/certcheck.git
cd certcheck
./gradlew assembleDebug
```

L'APK sera dans `app/build/outputs/apk/debug/`.

## Architecture

```
de.guenthers.certcheck/
├── MainActivity.kt          # Point d'entrée
├── MainViewModel.kt         # State management
├── model/
│   └── CertCheckResult.kt   # Data classes
├── network/
│   └── SSLChecker.kt        # Cœur de la vérification SSL
└── ui/
    ├── components/
    │   └── Components.kt     # Composants Compose réutilisables
    ├── screens/
    │   └── Screens.kt        # Écrans Home + Résultat
    └── theme/
        └── Theme.kt          # Thème Material 3
```

## Licence

MIT License — voir [LICENSE](LICENSE)
