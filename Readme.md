

# Plateforme de Détection de Fraude : Une Architecture Hybride "Privacy-First"

Ce projet fournit une plateforme complète de détection de fraude, basée sur une architecture de microservices et un concept unique d'architecture hybride "Privacy-First". Il combine la sécurité du traitement des données sur l'infrastructure du client avec la flexibilité d'une gestion et d'un scoring centralisés dans le cloud.

L'ensemble de l'environnement est conteneurisé à l'aide de Docker et géré avec un unique fichier Docker Compose, garantissant une installation simple et rapide en une seule commande.

## Table des Matières

1.  [Philosophie du Projet : L'Architecture Hybride](#philosophie-du-projet--larchitecture-hybride)
2.  [Flux Architectural](#flux-architectural)
3.  [Prérequis](#prérequis)
4.  [Installation et Configuration](#installation-et-configuration)
5.  [Exécution du Testeur Interactif du SDK Java](#exécution-du-testeur-interactif-du-sdk-java)
6.  [Référence des Services et Identifiants](#référence-des-services-et-identifiants)
7.  [Gestion de l'Environnement](#gestion-de-lenvironnement)
8.  [Annexe : Configuration Manuelle de Kong via Konga](#annexe--configuration-manuelle-de-kong-via-konga)

## Philosophie du Projet : L'Architecture Hybride

Le cœur de ce projet est un puissant **SDK Java** qui s'exécute directement sur l'infrastructure du client. Cette conception est fondamentalement axée sur la confidentialité et la sécurité :

1.  **Traitement des Données en Local :** Le SDK utilise un modèle **ONNX local pour la vectorisation**. Cela signifie qu'il traite les données de transaction brutes et sensibles sur la machine du client, les transformant en un vecteur numérique anonymisé. **Les données sensibles ne quittent jamais l'infrastructure du client.**

2.  **Configuration Centralisée :** Au démarrage, le SDK "appelle à la maison" (*phones home*) une **API Portail** centrale et sécurisée. Depuis cette API, il télécharge sa configuration complète : l'emplacement des modèles ML, les points d'accès (*endpoints*) de scoring et, surtout, les règles métier spécifiques au client.

3.  **Scoring Anonymisé :** Seul le vecteur anonymisé est envoyé sur le réseau à des microservices de scoring légers et spécialisés. Cela garantit que les services centraux ne voient jamais les données brutes des clients, uniquement leur représentation mathématique.

4.  **Sécurité et Orchestration par Kong :** Toute la communication entre le SDK et les services centraux est acheminée via la **passerelle API Kong**, qui assure la sécurité (validation des JWT) et la gestion du trafic.

## Flux Architectural

Les composants de la plateforme interagissent dans une séquence soigneusement orchestrée :

1.  **Portail Client en Self-Service :** Une application web Python/FastAPI permet aux clients de s'enregistrer eux-mêmes. Ce portail automatise la création d'un "Consumer" dans la passerelle Kong, génère un JWT sécurisé pour eux et fournit une interface graphique pour gérer entièrement leurs propres règles métier (stockées dans une base de données PostgreSQL dédiée).

2.  **Initialisation du SDK :** Lorsque le SDK Java démarre, il utilise son JWT pour s'authentifier auprès de l'API Portail et télécharge sa configuration unique.

3.  **Le Processus de Scoring en Deux Étapes :**
    *   **Étape 1 (Locale) :** Le SDK traite une transaction brute (par exemple, les détails d'un virement bancaire) localement, en utilisant un modèle ONNX téléchargé pour créer un vecteur sécurisé et anonymisé.
    *   **Étape 2 (À Distance) :** Le SDK envoie *uniquement le vecteur* via Kong au microservice de scoring spécialisé approprié (par exemple, `bank_ml_scorer`).

4.  **Scoring Spécialisé :** Le microservice FastAPI léger reçoit le vecteur et le note à l'aide d'un modèle de classification (par exemple, XGBoost) chargé depuis le registre de modèles MLflow. Le score final et la décision sont retournés au SDK.

## Prérequis

Avant de commencer, assurez-vous d'avoir installé les logiciels suivants sur votre machine :

*   **Docker & Docker Compose :** La plateforme de conteneurisation.
    *   [➡️ **Installer Docker Desktop**](https://docs.docker.com/get-docker/)

*   **Git & Git LFS :** Pour cloner le dépôt et gérer les fichiers volumineux.
    *   [➡️ **Installer Git LFS**](https://git-lfs.github.com/)

*   **Java JDK 17 (Kit de développement Java) :**
    *   [➡️ **Installer Eclipse Temurin 17 (OpenJDK)**](https://adoptium.net/temurin/releases/?version=17) - Une distribution open-source de confiance.

*   **Un IDE Java Open-Source** supportant Maven :
    *   [➡️ **Installer IntelliJ IDEA Community Edition**](https://www.jetbrains.com/idea/download/) (Recommandé)
    *   [➡️ **Installer Eclipse IDE for Java Developers**](https://www.eclipse.org/downloads/packages/)

## Installation et Configuration

Suivez ces étapes pour faire fonctionner l'ensemble de la plateforme sur votre machine locale.

### 1. Cloner le Dépôt

Cette commande clonera le projet et téléchargera automatiquement les fichiers de données volumineux gérés par Git LFS.

```bash
git clone https://github.com/Anassidbella/fraud-detection-platforme.git
cd fraud-detection-platforme
```

### 2. Construire et Démarrer les Conteneurs Docker

Tous les services sont gérés via Docker Compose. La configuration principale se trouve dans le répertoire `kong-stack`.

```bash
# Naviguer vers le répertoire contenant le fichier docker-compose.yml
cd kong-stack

# Construire toutes les images des services (peut prendre du temps la première fois)
docker-compose build

# Démarrer tous les services en mode détaché (en arrière-plan)
docker-compose up -d
```

À ce stade, toute la plateforme est en cours d'exécution. Vous pouvez vérifier l'état des conteneurs avec `docker-compose ps`.

## Exécution du Testeur Interactif du SDK Java

Pour voir le flux "Privacy-First" en action, vous pouvez exécuter le testeur interactif inclus directement depuis votre IDE.

#### Étape 1 : Ouvrir le Projet et Synchroniser les Dépendances Maven

C'est l'étape la plus importante. Votre IDE va automatiser l'installation de toutes les bibliothèques nécessaires au SDK.

1.  Lancez IntelliJ IDEA (ou un autre IDE).
2.  Allez dans `File > Open...` et sélectionnez le répertoire `fraud-detection-platforme/TXC-FRAUD-SDK`.
3.  L'IDE va immédiatement reconnaître le fichier `pom.xml`, qui est la "recette" du projet Java.
4.  Il lancera automatiquement un processus appelé **"synchronisation Maven"**. Vous verrez probablement une barre de progression en bas à droite de l'écran.
5.  **Patientez un instant.** L'IDE est en train de télécharger toutes les dépendances listées dans le `pom.xml` (le client MLflow, ONNX Runtime, etc.) depuis internet et de les organiser pour le projet.
6.  Une fois terminé, toutes les bibliothèques sont prêtes à l'emploi. **Aucune installation manuelle de dépendances n'est nécessaire.**

#### Étape 2 : Localiser et Exécuter `InteractiveTester.java`

1.  Dans l'explorateur de projet, naviguez vers `src/main/java/org/example/`.
2.  Trouvez le fichier nommé `InteractiveTester.java`.
3.  Faites un **clic droit** sur le fichier et sélectionnez **"Run 'InteractiveTester.main()'"**.

L'application démarrera dans la console de votre IDE, se connectera à MLflow/MinIO pour télécharger le modèle, puis vous invitera à saisir les détails d'une transaction pour voir le résultat du scoring en temps réel.

## Référence des Services et Identifiants

| Service              | Port  | URL                   | Identifiants (User / Pass)  | Rôle                                                |
| -------------------- | ----- | --------------------- | --------------------------- | --------------------------------------------------- |
| Proxy Kong           | 8000  | `http://localhost:8000` | (Contrôlé par JWT)          | Point d'entrée principal pour les requêtes API.     |
| **UI Airflow**       | 8080  | `http://localhost:8080` | `admin` / `admin`           | UI pour la gestion des pipelines de données.        |
| **UI MLflow**        | 5000  | `http://localhost:5000` | -                           | UI pour le suivi des expériences et modèles ML.     |
| **UI Konga**         | 1337  | `http://localhost:1337` | (À créer au premier usage)  | Interface web pour observer la configuration de Kong.|
| **Console MinIO**    | 9001  | `http://localhost:9001` | `minioadmin` / `minioadmin` | UI pour naviguer dans le stockage d'artefacts S3.   |
| API Portail          | 5003  | `http://localhost:5003` | (Contrôlé par JWT)          | Hub central pour la configuration des clients.      |

## Gestion de l'Environnement

Toutes les commandes doivent être exécutées depuis le répertoire `kong-stack`.

*   **Voir le statut de tous les conteneurs en cours d'exécution :**
    ```bash
    docker-compose ps
    ```

*   **Afficher les logs d'un service spécifique (par exemple, `portal_api`) :**
    ```bash
    docker-compose logs -f portal_api
    ```

*   **Arrêter et supprimer tous les conteneurs, réseaux et volumes :**
    ```bash
    docker-compose down -v
    ```
    **Attention :** L'option `-v` supprimera définitivement toutes les données, y compris le contenu des bases de données.

## Annexe : Configuration Manuelle de Kong via Konga

Cette section décrit comment configurer manuellement la passerelle Kong à l'aide de l'interface utilisateur Konga. C'est une excellente méthode pour comprendre le fonctionnement interne de la passerelle et visualiser la configuration.

### Étape 1 : Connexion de Konga à l'instance Kong

La première étape est de connecter Konga au panneau d'administration de Kong.

1.  Accédez à l'interface de Konga : `http://localhost:1337`.
2.  Créez un compte administrateur si c'est votre première visite.
3.  Allez dans la section **CONNECTIONS**.
4.  Cliquez sur **NEW CONNECTION**.
5.  Remplissez les informations comme suit :
    *   **Name** : `Kong` (ou un nom de votre choix).
    *   **Kong Admin URL** : `http://kong-gateway-instance:8001`

    > **Explication :** On utilise `kong-gateway-instance` car c'est le nom du service Kong défini dans `docker-compose.yml`. Docker dispose d'un DNS interne qui permet aux conteneurs du même réseau (`app-network`) de communiquer entre eux en utilisant leurs noms de service comme noms d'hôte. Le port `8001` est le port d'administration de Kong.

6.  Cliquez sur **CREATE CONNECTION** puis activez-la en cliquant sur **ACTIVATE**.

### Étape 2 : Création des Services

Un "Service" dans Kong est une entité qui représente une API ou un microservice en amont (par exemple, votre `portal_api`).

1.  Dans le menu de gauche, cliquez sur **SERVICES**.
2.  Cliquez sur **ADD NEW SERVICE**.
3.  Créez les services suivants, un par un, en renseignant le nom du conteneur (`Host`) et son port d'écoute interne (`Port`) :

| Name (Nom du Service Kong) | Host (Nom du conteneur cible) | Port |
| :--- | :--- | :--- |
| `bank_MLscorer` | `bank_ml_scorer` | `5004` |
| `portal_service` | `portal_api` | `5003` |
| `ecommerce_MLscorer` | `ecommerce_ml_scorer` | `5002` |
| `mobile_money_MLscorer` | `mobile_money_ml_scorer`| `5001` |

> **Explication :** Le champ `Host` doit correspondre au nom du conteneur du microservice, et le `Port` au port sur lequel l'application écoute *à l'intérieur* de ce conteneur. Kong utilisera ces informations pour router le trafic correctement (ex: vers `http://bank_ml_scorer:5004`).

### Étape 3 : Création des Routes

Une "Route" définit comment les requêtes externes sont acheminées vers un Service.

1.  Allez dans la section **ROUTES**.
2.  Cliquez sur **ADD NEW ROUTE**.
3.  Créez les routes suivantes en les associant au bon service :

| Service Associé | Nom de la Route (optionnel) | Hosts | Paths | Strip Path |
|:---|:---|:---|:---|:---|
| `bank_MLscorer` | `bank_service_route` | `localhost` | `/bank/` | **Enabled** |
| `ecommerce_MLscorer`| `ecommerce_route` | `localhost` | `/ecommerce/` | **Enabled** |
| `mobile_money_MLscorer`| `mobileMoney_service_route` | `localhost`| `/mobile_money/`| **Enabled** |
| `portal_service` | `portal_sdk_config_route` | - | `/runtime-config/`| **Enabled** |
| `portal_service` | `portal_api_rules_secure` | - | `/rules` | **Disabled** |
| `portal_service` | `portal_ui_login` | - | `/login-ui` | **Disabled** |
| `portal_service` | `portal_ui_register`| - | `/` | **Disabled** |
| `portal_service` | `portal_ui_rules` | - | `/rules-ui` | **Disabled** |

> #### **Comprendre l'option `Strip Path`**
> *   **Enabled (Activé) :** Kong supprime le préfixe du chemin avant de transférer la requête. Exemple : une requête vers `http://localhost:8000/bank/score` est transmise au service `bank_MLscorer` en tant que `/score`. C'est utile pour ne pas avoir à gérer le préfixe `/bank/` dans le code du microservice.
> *   **Disabled (Désactivé) :** Kong transfère le chemin complet. Exemple : une requête vers `http://localhost:8000/rules` est transmise au service `portal_service` en tant que `/rules`. C'est nécessaire lorsque le routeur de l'application backend attend le chemin complet pour fonctionner.

### Étape 4 : Sécurisation des Routes avec le Plugin JWT

Le plugin JWT est utilisé pour protéger les routes qui nécessitent une authentification. Selon la configuration, certaines routes sont publiques et d'autres sont sécurisées.

#### Routes Sécurisées (Plugin JWT **ACTIVÉ**)

Ces routes sont critiques et ne doivent être accessibles qu'aux clients authentifiés (comme le SDK).

| Nom de la Route | Objectif |
|:---|:---|
| `bank_service_route` | Protège le microservice de scoring bancaire. |
| `ecommerce_route` | Protège le microservice de scoring e-commerce. |
| `mobileMoney_service_route` | Protège le microservice de scoring mobile money. |
| `portal_sdk_config_route` | Sécurise l'accès à la configuration dynamique du SDK. |
| `portal_api_rules_secure` | Sécurise l'API de gestion des règles métier. |

**Pour sécuriser une de ces routes :**
1. Allez dans la section **ROUTES** et cliquez sur le nom de la route.
2. Allez sur l'onglet **Plugins**.
3. Cliquez sur **ADD PLUGIN**, recherchez et sélectionnez **JWT**.
4. Cliquez sur **ADD PLUGIN** pour l'activer avec sa configuration par défaut.

#### Routes Publiques (Plugin JWT **NON ACTIVÉ**)

Ces routes permettent l'accès à l'interface utilisateur du portail (connexion, enregistrement) et ne nécessitent pas de jeton JWT.

| Nom de la Route | Objectif |
|:---|:---|
| `portal_ui_register` | Permet l'accès à la page d'enregistrement des nouveaux clients. |
| `portal_ui_login` | Permet l'accès à la page de connexion de l'interface utilisateur. |
| `portal_ui_rules` | Permet d'afficher l'interface de gestion des règles (l'authentification est gérée par l'application elle-même, probablement via des cookies de session). |

> Une fois le plugin JWT activé sur une route, toute requête vers cette route sans un jeton JWT valide dans l'en-tête `Authorization` sera rejetée par Kong avec une erreur `401 Unauthorized`. Les jetons JWT sont générés par le `portal_api` lors de l'enregistrement d'un nouveau client (Consumer).