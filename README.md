# MercurOplay

Application Android (Java) pour la radio FM **Mercure**, avec écoute du direct
(Icecast) et un lecteur de podcasts/vidéos à partir d'un flux MRSS.

- **Namespace** : `fr.svpro.radiomercure`
- **Nom de l'app** : MercurOplay
- **Java 17, minSdk 24, targetSdk 34**
- **Navigation** : `BottomNavigationView` + Jetpack Navigation Component, 2 onglets (Direct / Podcasts)

## Fonctionnalités

### Onglet "Direct" (`live/`)
- Lecture du flux Icecast en direct : `https://oplay-stream.radiomercure.net`
- Métadonnées du morceau en cours (ICY `StreamTitle`) affichées en temps réel,
  lues automatiquement par Media3 ExoPlayer (support ICY natif, aucun parsing
  manuel requis) via `Player.Listener#onMediaMetadataChanged`.
- Pochette de l'album récupérée en temps réel via l'**iTunes Search API**
  (gratuite, sans clé) à partir de l'artiste/titre détecté (`CoverArtFetcher`).
- La pochette trouvée est relayée à `PlaybackService` via une **commande de
  session personnalisée** (`PlaybackService.COMMAND_SET_ARTWORK`, envoyée par
  `LiveFragment#updateNotificationArtwork` avec `MediaController#sendCustomCommand`),
  qui applique `Player#replaceMediaItem` **directement sur l'instance ExoPlayer
  côté service** afin que la **notification système** affiche la même pochette
  que l'app, en plus du titre/artiste ICY. Le `PlaybackService` utilise un
  `DataSourceBitmapLoader` pour que la session sache charger cette image depuis
  une URL réseau.
  > ⚠️ Important : cette mise à jour doit être effectuée sur le `Player` lui-même
  > et non via un `MediaController` (ex. directement depuis le Fragment) : c'est
  > un bug connu de Media3 (voir [androidx/media#706](https://github.com/androidx/media/issues/706))
  > où `replaceMediaItem()` appelé depuis un contrôleur ne remonte plus les
  > changements de métadonnées ensuite - c'était la cause du gel de l'affichage
  > (titre + pochette figés) après la première mise à jour de la pochette.
- Nombre d'auditeurs en direct, interrogé toutes les 15s sur l'endpoint
  standard Icecast `status-json.xsl` (`IcecastStatusFetcher`). Si l'endpoint
  n'est pas exposé publiquement par le serveur, l'UI affiche simplement
  "-- auditeurs" sans bloquer la lecture.
- Lecture en arrière-plan avec notification système (voir `PlaybackService`).

### Onglet "Podcasts" (`podcast/`)
- Liste des épisodes à partir du flux MRSS :
  `https://oplay.radiomercure.fr/feeds/videos.xml?accountId=4`
- `MrssFeedParser` est un parseur XML tolérant (XmlPullParser, non
  namespace-strict) qui gère à la fois `<media:content>` / `<media:group>`
  (en choisissant la meilleure qualité selon le bitrate annoncé) et le
  `<enclosure>` RSS classique, avec repli automatique de l'un vers l'autre.
  Il lit aussi `<media:thumbnail>` / `<itunes:image>`, la durée
  (`<itunes:duration>` ou `duration` de `media:content`), et `<link>` (page
  web de l'épisode, RSS classique ou Atom `href`), utilisé pour le partage.
- Chaque épisode est marqué automatiquement **VIDÉO** ou **AUDIO** (type MIME,
  ou à défaut extension du fichier).
- `PodcastPlayerActivity` ouvre un lecteur Media3 (`PlayerView`) qui gère
  aussi bien la vidéo (16:9, contrôles superposés) que l'audio (pochette par
  défaut affichée).
- Pull-to-refresh, état de chargement et état vide/erreur gérés.
- **Téléchargement** : bouton par épisode qui délègue à `DownloadManager`
  (fichier enregistré dans le dossier public *Téléchargements*, notification
  système à la fin). Sur API 24-28, la permission `WRITE_EXTERNAL_STORAGE`
  est demandée au runtime si nécessaire (non requise à partir d'API 29).
- **Partage** : bouton par épisode qui ouvre le sélecteur de partage Android
  avec le titre + l'URL de la page de l'épisode, lue depuis la balise RSS
  `<link>` du flux (repli sur l'URL directe du média si le flux ne fournit
  pas de `<link>` pour cet épisode).

### Lecture partagée (`playback/PlaybackService.java`)
Un unique `MediaSessionService` (Media3) héberge l'`ExoPlayer` utilisé à la
fois par le direct et par le lecteur de podcasts, ce qui permet la lecture en
arrière-plan avec les contrôles système (notification, écran de verrouillage,
Bluetooth) sans dupliquer la logique de lecture.

## Architecture

```
fr.svpro.radiomercure/
├── MercurOplayApp.java        Application - crée le canal de notification
├── SplashActivity.java        Écran de démarrage (logo, ~1.4s)
├── MainActivity.java          Hôte du NavHostFragment + BottomNavigationView
├── playback/
│   └── PlaybackService.java   MediaSessionService partagé (ExoPlayer + notif.)
├── live/
│   ├── LiveFragment.java      UI du direct, connexion au PlaybackService
│   ├── CoverArtFetcher.java   Recherche pochette (iTunes Search API)
│   └── IcecastStatusFetcher.java  Sondage du nombre d'auditeurs
├── podcast/
│   ├── PodcastFragment.java   Liste des épisodes (RecyclerView)
│   ├── PodcastAdapter.java
│   ├── PodcastRepository.java Récupération réseau + parsing en arrière-plan
│   ├── MrssFeedParser.java    Parseur MRSS/RSS tolérant
│   ├── Episode.java           Modèle (Serializable, passé via Intent)
│   └── PodcastPlayerActivity.java  Lecteur audio/vidéo plein écran
└── util/
    └── Config.java            URLs centralisées (flux, feed, iTunes, etc.)
```

## Identité visuelle

Les couleurs sont extraites directement du logo fourni (rond bleu/orange
"Mercure") plutôt que de reprendre la palette sombre utilisée sur les
précédents projets, afin de rester fidèle à l'identité propre de cette radio :

| Usage | Couleur |
|---|---|
| Bleu principal | `#0E3D99` |
| Bleu foncé (barre de statut, dégradés) | `#0A2E75` |
| Orange d'accent (bouton lecture, badges) | `#FF4000` |
| Fond | `#F5F7FB` |

Icônes de lancement (legacy + adaptive icon) générées dans toutes les
densités à partir de `ic_launcher.png`. L'écran de démarrage affiche le
bandeau `Splash_Screen.png` fourni.

## Pistes d'amélioration futures

- Ajouter une seconde flux/station si Mercure en propose une deuxième
  (structure déjà prête pour plusieurs `MediaItem`).
- Mise en cache locale du flux podcast (Room ou simple JSON dans
  SharedPreferences) pour un affichage instantané hors connexion.
- Reprise de lecture des podcasts (position sauvegardée), sur le même modèle
  que le save/resume déjà utilisé dans Sudoku/Pendu.
- Ajout d'un widget "Now Playing" sur l'écran d'accueil.
- Vérifier si `status-json.xsl` est bien exposé publiquement par le serveur
  Icecast de Radio Mercure ; sinon, adapter `Config.LIVE_STATUS_URL` vers le
  bon endpoint ou masquer le compteur d'auditeurs.

## Notes techniques

- Le projet n'a pas pu être compilé dans cet environnement (accès réseau
  restreint, sans accès à `google()`/`dl.google.com` pour résoudre AndroidX).
  Ouvrir directement dans Android Studio, laisser Gradle synchroniser les
  dépendances (Media3 1.3.1, Navigation 2.7.7, OkHttp 4.12.0, Glide 4.16.0),
  puis lancer sur un appareil/émulateur.
- Le parsing MRSS est volontairement tolérant car le format exact du flux
  `oplay.radiomercure.fr` n'a pas pu être inspecté directement (le fetch de
  l'URL a été refusé côté outil). Si un épisode n'apparaît pas ou que le
  media/la vignette est manquant, vérifier les noms de balises réels du flux
  et ajuster `MrssFeedParser` en conséquence (les points d'extension sont
  clairement identifiés dans le fichier).
