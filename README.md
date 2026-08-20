# MercurOplay

Application Android (Java) pour la radio FM **Mercure**, avec écoute du direct
(Icecast) et un lecteur de podcasts/vidéos à partir d'un flux MRSS.

- **Namespace** : `fr.svpro.radiomercure`
- **Nom de l'app** : MercurOplay
- **Java 17, minSdk 24, targetSdk 34**
- **Navigation** : `BottomNavigationView` + Jetpack Navigation Component, 4 onglets (Direct / Podcasts / Chaînes / Contact - ce dernier ouvre un formulaire, pas un vrai fragment de navigation)

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

### Mise à jour automatique (`util/AppUpdateHelper.java`)
- Utilise l'API **Google Play In-App Updates** (`com.google.android.play:app-update`)
  en flux **flexible** : quand Play signale une nouvelle version, elle se
  télécharge silencieusement en arrière-plan pendant que l'app reste
  utilisable normalement (contrairement au flux "immediate", plein écran et
  bloquant, qu'on n'a pas retenu ici pour ne pas interrompre l'écoute en
  direct).
- Une fois le téléchargement terminé, un `Snackbar` ("Une mise à jour a été
  téléchargée" + bouton "Redémarrer") propose d'installer - l'app ne
  redémarre jamais toute seule sans action de la personne.
- Vérification faite à chaque `onResume()` de `MainActivity` (`checkForUpdate`
  + `resumeUpdateIfNeeded`, cette seconde méthode rattrapant le cas où le
  téléchargement s'est terminé pendant que l'app était en arrière-plan).
- N'a aucun effet si l'app n'a pas été installée depuis le Play Store (ex.
  build de test installé via `adb install`/ZIP direct) : `getAppUpdateInfo()`
  échoue simplement, silencieusement, sans jamais impacter le reste de l'app.
- Pour passer en flux **immediate** (mise à jour obligatoire, écran bloquant)
  sur une version particulière, il suffit de changer
  `AppUpdateType.FLEXIBLE` en `AppUpdateType.IMMEDIATE` dans
  `AppUpdateHelper#startFlexibleUpdateIfAvailable` - la logique de
  vérification reste identique.

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
- Une 3ᵉ ligne dans la carte de liens ("Nous contacter") ouvre l'écran de
  contact ci-dessous.

### Écran "Nous contacter" (`contact/ContactActivity`)
- Accessible de deux façons : depuis l'écran "À propos" (ligne "Nous
  contacter"), et directement via un 4ᵉ onglet "Contact" dans le menu de
  navigation du bas. Comme ce n'est pas un vrai fragment/destination
  `NavController` (juste un formulaire dans une Activity), l'appui sur cet
  onglet est intercepté dans `MainActivity` pour lancer `ContactActivity`
  sans perturber la sélection visuelle des 3 autres onglets (qui reste gérée
  normalement par `NavigationUI`).
- Champs : **Nom ou Association**\*, **Adresse mail**\*, **Téléphone**
  (facultatif), **Sujet**\* (menu déroulant Material "Exposed Dropdown Menu",
  items définis dans `strings.xml` → `contact_subjects`), **Message**\*, et
  un **document à joindre** facultatif (images, PDF, MS Office, LibreOffice/
  OpenDocument, texte brut - liste de mimetypes exacte dans
  `ContactActivity.ALLOWED_ATTACHMENT_MIME_TYPES`, sélection via
  `ActivityResultContracts.OpenDocument`).
- Validation avant envoi : nom/email/sujet/message requis (erreurs affichées
  directement sur les `TextInputLayout`), email vérifié via
  `Patterns.EMAIL_ADDRESS`. Le téléphone et la pièce jointe restent
  optionnels.
- **Destinataire** : jamais affiché dans l'UI - lu depuis
  `Config.CONTACT_EMAIL`.
  > ⚠️ Aucune adresse précise n'a été fournie pour ce champ ; une valeur
  > passe-partout (`contact@radiomercure.fr`) a été mise en place à ajuster
  > dans `Config.java`.
- **Envoi** : l'app ne dispose pas de backend mail, donc le formulaire
  compose un `Intent.ACTION_SEND` (type `message/rfc822`, pour être
  proposé uniquement aux applications de messagerie) pré-rempli avec
  destinataire/sujet/corps (et la pièce jointe le cas échéant via
  `EXTRA_STREAM` + permission de lecture accordée), puis ouvre le
  sélecteur d'application. L'envoi effectif se termine dans l'app de
  messagerie choisie par la personne - MercurOplay ne peut pas savoir si le
  message a réellement été envoyé une fois le sélecteur ouvert, donc le
  formulaire n'est ni vidé ni fermé automatiquement après ce point.
- **Signature** : `contact_signature` dans `strings.xml`
  (`"\n\n---\nEnvoyé depuis l'application MercurOplay"`), toujours ajoutée en
  fin de message, après le contenu saisi.

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
  > 🐛 **Correctif** : `getString(clé, Config.X)` ne retombe sur la valeur de
  > `Config` que si la clé n'a *jamais* été écrite en `SharedPreferences`.
  > Concrètement, changer les tokens codés en dur dans `Config.java` (par ex.
  > en générer de nouveaux côté PeerTube) n'avait auparavant aucun effet sur
  > un appareil ayant déjà tourné avec l'ancienne paire : elle restait stockée
  > et masquait silencieusement la nouvelle - et si cet ancien refresh_token
  > avait entre-temps été invalidé côté serveur, chaque tentative de
  > rafraîchissement échouait pour de bon (symptôme : "l'access_token ne se
  > renouvelle jamais malgré le refresh_token"). `PeerTubeAuthStore` marque
  > désormais la paire persistée avec le refresh_token de `Config` qui l'a
  > semée ; si cette valeur change (nouveau build avec des identifiants
  > différents), la paire stockée est considérée périmée et réinitialisée
  > depuis `Config` au lieu de rester bloquée indéfiniment.
  > 🐛 **Correctif (token revoqué au bout de ~24h malgré le refresh)** :
  > `PeerTubeApiClient` était instancié séparément par chacun des 3 écrans
  > (chaînes, vidéos, lecteur), chacun avec son propre `OkHttpClient`. Si deux
  > appels expiraient à peu près en même temps sur deux écrans différents
  > (ex. navigation rapide chaîne → vidéos → lecteur), les deux lisaient le
  > *même* refresh_token encore valide et déclenchaient chacun leur propre
  > rafraîchissement en parallèle. Or PeerTube fait tourner le refresh_token à
  > chaque utilisation : le premier appel réussit et le fait pivoter, mais le
  > second - qui utilise la valeur désormais déjà consommée - échoue ; si le
  > serveur applique une détection de réutilisation (pratique standard
  > OAuth2), il peut alors révoquer toute la chaîne de tokens, ce qui
  > correspond exactement à devoir tout régénérer à la main côté serveur.
  > `PeerTubeApiClient` est maintenant un **singleton** (`getInstance(Context)`),
  > partagé par les 3 écrans, dont `refreshAccessToken` met en file d'attente
  > les appels concurrents plutôt que de laisser partir un second
  > rafraîchissement en parallèle : un seul appel HTTP de rafraîchissement est
  > jamais en vol à la fois pour toute l'app, et tous les appelants qui
  > arrivent pendant qu'il est en cours attendent son résultat au lieu de
  > redéclencher leur propre requête.
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
├── contact/
│   └── ContactActivity.java    Formulaire de contact (envoi via chooser e-mail)
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
    ├── Config.java            URLs centralisées (flux, feed, iTunes, PeerTube, etc.)
    └── AppUpdateHelper.java   Mise à jour via Google Play In-App Updates
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
- **Tester la mise à jour in-app** : l'API Play Core ne fonctionne que pour
  une app installée depuis le Play Store, avec un `versionCode` réellement
  supérieur publié sur une track (interne/fermée/production). Un simple
  `adb install` d'un APK plus récent ne déclenche rien. Pour tester, publier
  une version sur la track de test interne du Play Console, installer la
  version précédente depuis ce lien, puis publier la nouvelle version.

### Correctif : les appels API échouaient sur API ≤ 28 (Android 9 et antérieur)

**Cause** : Android n'active **TLS 1.3 par défaut qu'à partir de l'API 29**
(Android 10) - avant ça (donc sur toute la plage minSdk 24-28 de l'app), le
fournisseur TLS de la plateforme se limite à TLS 1.2. Si le serveur (via son
reverse-proxy nginx/Caddy) impose ou préfère TLS 1.3 - un profil de
sécurité "moderne" assez courant aujourd'hui - la négociation TLS échoue
purement et simplement sur ces appareils, avant même que la requête HTTP ne
parte. Ce n'est pas propre à l'API PeerTube : ça aurait touché n'importe
quel appel HTTPS de l'app sur ces versions si le même serveur/proxy est
utilisé ailleurs.

**Correctif** : ajout de **Conscrypt** (`org.conscrypt:conscrypt-android`),
installé comme fournisseur de sécurité prioritaire au démarrage de l'app
(`MercurOplayApp#installModernTlsProvider`, via
`Security.insertProviderAt(Conscrypt.newProvider(), 1)`). Conscrypt apporte
le support TLS 1.3 dès l'API 21, indépendamment du fournisseur TLS natif de
la plateforme. L'installation se fait une seule fois, globalement pour tout
le process - OkHttp (tous les clients du projet), Glide et Media3
en bénéficient automatiquement sans changement de leur côté, puisqu'ils
résolvent tous le contexte TLS par défaut de la JVM. En cas d'échec
d'installation (device très ancien/atypique), le code retombe silencieusement
sur le fournisseur TLS de la plateforme plutôt que de crasher au démarrage.
