# MercurOplay

Application Android (Java) pour la radio FM **Mercure**, avec écoute du direct
(Icecast) et un lecteur de podcasts/vidéos à partir d'un flux MRSS.

- **Namespace** : `fr.svpro.radiomercure`
- **Nom de l'app** : MercurOplay
- **Java 17, minSdk 24, targetSdk 34**
- **Navigation** : `BottomNavigationView` + Jetpack Navigation Component, 2 onglets (Direct / Podcasts)

## Fonctionnalités

### Onglet "Direct" (`live/`)
- **Démarrage neutre** : à l'ouverture de l'onglet, rien n'est lancé -
  `ic_placeholder_cover` est affiché, le titre reste sur "Radio Mercure", et
  ni le `PlaybackService` ni `CoverArtFetcher`/le flux ICY ne sont sollicités.
  Le service, la préparation du flux et toute la synchronisation
  cover/artiste/titre ne démarrent qu'au premier appui sur le bouton lecture
  (`LiveFragment#connectAndPlay`, appelé depuis `togglePlayback`).
  > Limite connue : si on quitte l'onglet Direct pendant la lecture (ex. pour
  > aller sur Podcasts) puis qu'on y revient, le Fragment est recréé et
  > raffiche l'état neutre tant qu'on n'a pas retouché le bouton lecture,
  > même si le flux continue réellement en arrière-plan (notification à
  > jour). Un nouvel appui reconnecte immédiatement et resynchronise tout.
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

### Écran "À propos" (`AboutActivity`)
- Accessible via l'icône ⓘ en haut à droite de l'écran principal (visible sur
  les deux onglets), à côté du `BottomNavigationView`.
- Affiche le logo de l'application (`ic_launcher`) et le logo **SVPRO**
  fourni (`res/drawable-nodpi/logo_svpro.png`) côte à côte.
- **Version** lue dynamiquement depuis `BuildConfig.VERSION_NAME`
  (donc `versionName` du `app/build.gradle`) - jamais codée en dur, se met à
  jour automatiquement à chaque nouvelle version buildée. Nécessite
  `buildFeatures { buildConfig true }` (ajouté dans `app/build.gradle`).
- Lien **Conditions d'utilisation** :
  `https://oplay.radiomercure.fr/about/instance/home`
- Lien **Licence GNU** : `https://www.gnu.org/licenses/gpl-3.0.html`
  (GPL v3 - la demande ne précisait pas la version exacte de la licence GNU
  visée ; à ajuster dans `strings.xml` (`about_license_url`) si une autre
  licence GNU était voulue, par ex. LGPL ou AGPL).
- Les deux liens s'ouvrent dans le navigateur via `Intent.ACTION_VIEW`, avec
  un message d'erreur silencieux (`Toast`) si aucune app ne peut les gérer.
- Bloc "Développeur" reprenant l'identité du logo SVPRO (Samuel Vermeulen,
  Consultant Informatique &amp; Internet).

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

### Onglet "Chaînes" (`peertube/`)
- **Accès** : 3ᵉ onglet du menu de navigation du bas (`BottomNavigationView`),
  avec la même icône 🎬 que l'ancien bouton flottant. Deux destinations dans
  le nav graph : `PeerTubeChannelsFragment` (liste des chaînes, onglet
  top-level) puis `PeerTubeVideosFragment` (vidéos d'une chaîne, atteint via
  une action Navigation avec argument `channelName`/`channelDisplayName` -
  le bouton retour dans l'en-tête et le bouton système "retour" font tous les
  deux `popBackStack()` normalement, comme n'importe quelle destination
  Navigation Component imbriquée).
- **Authentification** : chaque appel envoie un token d'accès OAuth2 en
  en-tête `Authorization: Bearer`, géré par `PeerTubeAuthStore` (persisté
  dans `SharedPreferences`, initialisé depuis `Config.PEERTUBE_USER_TOKEN`/
  `PEERTUBE_REFRESH_TOKEN`). **Rafraîchissement automatique** : si un appel
  échoue en 401, `PeerTubeApiClient` appelle `POST /users/token` avec
  `grant_type=refresh_token` (via `client_id`/`client_secret` +
  `refresh_token`), persiste les nouveaux tokens reçus, puis rejoue l'appel
  original une seule fois. Si le rafraîchissement échoue aussi (refresh_token
  expiré, ~2 semaines par défaut chez PeerTube), l'erreur d'origine est
  remontée à l'écran.
  > ⚠️ Ces identifiants sont actuellement en dur dans `Config.java` (valeurs
  > de départ uniquement - les tokens réels vivent ensuite dans
  > `SharedPreferences`). Acceptable pour un usage interne/personnel, mais à
  > éviter si ce build est un jour publié plus largement (Play Store, dépôt
  > public) sans les déplacer vers un stockage plus sûr.
- **Récupération des chaînes** (`PeerTubeApiClient#fetchChannels`) : un seul
  appel à `GET /users/me`, dont la réponse inclut directement le tableau
  `videoChannels[]` - inutile d'appeler `/accounts/{name}/video-channels`
  séparément.
- **Récupération des vidéos** (`PeerTubeApiClient#fetchChannelVideos`) :
  `GET /video-channels/{name}/videos`, réponse paginée `{total, data: []}`.
- **Lecture dans l'application** (`PeerTubePlayerActivity`, même principe que
  `PodcastPlayerActivity` : `PlayerView` + `PlaybackService` partagé). La
  liste des vidéos ne contient pas de source jouable directement : un tap sur
  une vidéo déclenche `PeerTubeApiClient#fetchPlaybackSource`, qui résout en
  priorité le master HLS (`streamingPlaylists[0].playlistUrl`, streaming
  adaptatif) et sinon le meilleur fichier Web Video progressif - les deux
  sont nativement compatibles Media3 (HLS déjà inclus dans les dépendances).
- **Partage** : toujours l'URL de la page du média (`{instance}/w/{shortUUID}`,
  déjà présente dans la réponse de liste - donc aucun appel réseau
  supplémentaire n'est nécessaire pour partager).
- **Téléchargement** : la liste des vidéos ne contient pas les fichiers
  téléchargeables (`files`/`streamingPlaylists`), seule la fiche détaillée
  d'une vidéo (`GET /videos/{id}`) les fournit. Un appui sur "Télécharger"
  déclenche donc un appel de résolution (`PeerTubeApiClient#fetchDownloadUrl`)
  qui choisit le fichier Web Video (mp4 progressif) de plus haute résolution
  disponible s'il y en a, sinon la meilleure piste HLS, avant de lancer
  `DownloadManager` (même mécanisme que pour les podcasts, y compris la
  permission runtime sur API 24-28). Si `downloadEnabled` est à `false` sur la
  vidéo, le téléchargement est refusé avec un message explicite.
- Les champs de miniatures/avatars (`thumbnails[]`/`avatars[]` vs les anciens
  `thumbnailPath`/`path` dépréciés) sont lus de façon tolérante aux deux
  générations de l'API PeerTube, sur le même principe que `MrssFeedParser`.

### Lecture partagée (`playback/PlaybackService.java`)
Un unique `MediaSessionService` (Media3) héberge l'`ExoPlayer` utilisé par le
direct, le lecteur de podcasts et le lecteur PeerTube, ce qui permet la
lecture en arrière-plan avec les contrôles système (notification, écran de
verrouillage, Bluetooth) sans dupliquer la logique de lecture.

## Architecture

```
fr.svpro.radiomercure/
├── MercurOplayApp.java        Application - crée le canal de notification
├── SplashActivity.java        Écran de démarrage (logo, ~1.4s)
├── MainActivity.java          Hôte du NavHostFragment + BottomNavigationView
├── AboutActivity.java          Écran "À propos" (logos, version, liens)
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
├── peertube/
│   ├── PeerTubeChannelsFragment.java  Onglet "Chaînes" (top-level)
│   ├── PeerTubeVideosFragment.java    Vidéos d'une chaîne (téléchargement/partage)
│   ├── PeerTubePlayerActivity.java    Lecteur vidéo plein écran (HLS/progressif)
│   ├── PeerTubeApiClient.java  Client REST PeerTube (OkHttp + JSON tolérant + refresh)
│   ├── PeerTubeAuthStore.java  Persistance des tokens OAuth2 (SharedPreferences)
│   ├── PtChannelAdapter.java
│   ├── PtVideoAdapter.java
│   ├── PtChannel.java          Modèle chaîne
│   └── PtVideo.java            Modèle vidéo
└── util/
    └── Config.java            URLs centralisées (flux, feed, iTunes, PeerTube, etc.)
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
