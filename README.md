# juribook-api-gateway

Point d'entrée unique de **JuriBook** pour le frontend : route chaque requête vers le bon microservice, applique le CORS une seule fois pour toute la plateforme, et fait un contrôle de présence/validité du JWT en amont des services (chaque service revalide indépendamment le rôle, cette gateway ne fait qu'un premier filtrage).

## Stack

- Java 21 · Spring Boot 4.1.0 · Maven
- **Spring Cloud Gateway Server WebMVC** (module basé sur Servlet/Tomcat, pas la variante réactive WebFlux classique - routes déclarées via `spring.cloud.gateway.server.webmvc.routes`)
- Pas de Spring Security ici (volontaire, cf. Notes techniques) - juste un `jakarta.servlet.Filter` classique pour le contrôle JWT
- Port : **8080**

## Structure du projet

```
src/main/java/juribook/api_gateway/
├── ApiGatewayApplication.java
├── config/
│   └── CorsConfig.java             # Les 2 filtres CORS de la gateway, enregistrés explicitement côte à côte (cf. Notes techniques)
├── filter/
│   └── GatewayJwtFilter.java       # jakarta.servlet.Filter classique - PAS du Spring Security
└── security/ (ou selon organisation réelle)
    ├── JwtValidationService.java   # Validation de la signature/expiration du JWT (lecture seule)
    └── PublicRouteMatcher.java     # Liste des routes publiques (pas de JWT exigé), match méthode + path
src/main/resources/
└── application.yaml                # Déclaration des routes vers chaque microservice
```

## Rôle de la gateway

### Ce qu'elle fait

1. **Routage** : chaque requête `/api/{service}/**` est proxifiée vers le microservice correspondant, déclaré dans `application.yaml` (`AUTH_SERVICE_URL`, `LAWYER_SERVICE_URL`, `BOOKING_SERVICE_URL`, `NOTIFICATION_SERVICE_URL`, `AUDIT_SERVICE_URL`).
2. **CORS centralisé** : une seule configuration CORS pour toute la plateforme (cf. ci-dessous), le frontend n'a qu'une seule origine à gérer (`localhost:8080`) au lieu d'une par microservice.
3. **Contrôle JWT de premier niveau** : `GatewayJwtFilter` vérifie la présence et la validité d'un token sur les routes non publiques (`PublicRouteMatcher`), avant même que la requête n'atteigne le microservice cible. **Ne vérifie pas le rôle**, chaque service revalide `hasRole(...)` indépendamment sur ses propres routes protégées, la gateway ne fait qu'un premier filtre d'authentification.
4. **Laisse passer les `OPTIONS`** sans exiger de token, quelle que soit la route, nécessaire pour que le preflight CORS du navigateur aboutisse avant même la logique d'authentification.

### Ce qu'elle NE fait PAS

- Pas de rate limiting, pas de circuit breaker, pas de retry automatique.
- Pas de vérification de rôle (`hasRole`), déléguée entièrement aux microservices.
- Pas de transformation de payload, de réponse, ou d'agrégation de plusieurs appels (pas de pattern BFF).

---

## CORS - la partie la plus disputée de ce service

### Pourquoi deux filtres, pas un seul

`CorsConfig.java` enregistre **deux** filtres explicitement, dans cet ordre garanti (`FilterRegistrationBean` + `.setOrder(...)` des deux côtés, pas de mélange avec un `@Component`+`@Order` qui laisserait planer un doute sur l'ordre réel appliqué par Spring) :

1. **`DedupeCorsHeaderFilter`** (ordre `HIGHEST_PRECEDENCE`), enveloppe la réponse Servlet, la première valeur posée pour chaque en-tête CORS gagne, toute tentative ultérieure est ignorée silencieusement.
2. **`CorsFilter`** (Spring standard, ordre `HIGHEST_PRECEDENCE + 1`), pose les en-têtes CORS de la gateway elle-même.

### Pourquoi le déduplicateur est nécessaire

Chaque microservice (`auth-service`, `lawyer-service`, `booking-service`, `audit-service`) a **sa propre** configuration CORS, héritée de l'époque où le frontend leur parlait directement, avant l'introduction de cette gateway. Résultat : sur une requête proxifiée, la réponse du microservice porte déjà `Access-Control-Allow-Origin`, et la gateway en ajoute un second par-dessus, le navigateur voit deux valeurs (même identiques) et rejette toute la réponse (`"...header contains multiple values ... but only one is allowed"`).

**Deux options possibles** : retirer la config CORS de chaque microservice (la gateway suffit désormais), ou dédupliquer côté gateway. **Option retenue : dédupliquer**, pour ne pas toucher aux 4 autres repos.

### Le piège de la casse (bug réel corrigé pendant cette session)

La réponse recopiée par le proxy peut porter le même en-tête CORS avec une casse **différente** selon le producteur d'origine (`Access-Control-Allow-Origin` posé par la gateway elle-même vs `access-control-allow-origin` recopié tel quel depuis un microservice en aval, les noms d'en-têtes HTTP sont censés être insensibles à la casse, mais une comparaison Java naïve (`Set.of(...).contains(name)`) est sensible à la casse par défaut). Diagnostiqué via `curl -i`, qui a montré les deux variantes côte à côte dans la même réponse. `DedupeCorsHeaderFilter` utilise un `TreeSet` avec `String.CASE_INSENSITIVE_ORDER` pour comparer les noms d'en-têtes, pas un `Set.of(...)` simple.

---

## Contrôle JWT - `GatewayJwtFilter`

Un `jakarta.servlet.Filter` classique (**pas** une `SecurityFilterChain` Spring Security, choix volontaire, cf. Notes techniques) :

```java
if (isOptionsRequest) {
    chain.doFilter(request, response);  // laisse toujours passer, même sans token
    return;
}
if (publicRouteMatcher.isPublic(method, path)) {
    chain.doFilter(request, response);  // route publique, pas de vérification
    return;
}
// sinon : vérifie la présence + validité du token via JwtValidationService
// token absent/invalide → 401, sans même router vers le microservice
```

`PublicRouteMatcher` maintient la liste des routes ne nécessitant aucun token (ex: `POST /api/auth/login`, `POST /api/auth/register`, `GET /api/lawyers`...), avec un matching méthode + path (`AntPathMatcher`).

---

## Lancer en local (hors Docker)

```bash
# Prérequis : les microservices cibles doivent être accessibles aux URLs
# déclarées dans application.yaml (localhost:8081/8082/8083/8084/8085 par défaut)
mvn clean spring-boot:run
```

> ⚠️ Toujours `mvn clean spring-boot:run` après une modification de `CorsConfig.java` ou tout filtre, un `mvn spring-boot:run` sans `clean` peut réutiliser un bytecode obsolète sans recompiler, ce qui a causé une bonne partie de la confusion pendant le debug CORS de cette session (le fichier corrigé sur disque, mais l'ancien comportement qui persistait quand même à l'exécution).

## Lancer via Docker Compose

```bash
# Depuis juribook-docker/docker/
docker compose up -d api-gateway
```

## Health check

[http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

---

## Variables d'environnement

| Variable | Description | Valeur par défaut |
|---|---|---|
| `AUTH_SERVICE_URL` | URL de l'auth-service | `http://localhost:8081` |
| `LAWYER_SERVICE_URL` | URL du lawyer-service | `http://localhost:8082` |
| `BOOKING_SERVICE_URL` | URL du booking-service | `http://localhost:8083` |
| `NOTIFICATION_SERVICE_URL` | URL du notification-service | `http://localhost:8084` |
| `AUDIT_SERVICE_URL` | URL de l'audit-service | `http://localhost:8085` |
| `JWT_SECRET` | Secret JWT partagé avec l'auth-service | valeur de dev |

---

## Notes techniques

### Pourquoi pas de Spring Security ici

Contrairement aux microservices (qui utilisent tous `SecurityConfig` + `SecurityFilterChain`), la gateway se contente d'un `jakarta.servlet.Filter` classique pour le contrôle JWT. Ce choix a une conséquence directe sur le CORS : il n'y a pas de `SecurityFilterChain` dans laquelle accrocher `.cors(...)` comme le font les microservices, d'où le besoin d'un `CorsFilter` Spring standard enregistré séparément, sans risque du conflit `CorsFilter` externe vs `SecurityFilterChain` documenté côté `auth-service` (puisqu'il n'y a justement pas de Spring Security ici pour entrer en conflit).

### `@WebMvcTest` — le bon package, trouvé par inspection directe du jar

Package réel confirmé pour Spring Boot 4.1.0 : `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, ainsi qu'un starter de test dédié et réel, `spring-boot-starter-webmvc-test` (distinct du générique `spring-boot-starter-test`), à ajouter en plus si des tests `@WebMvcTest` sont écrits pour ce service. Confirmé par inspection directe des jars du dépôt Maven local (`jar tf ... | Select-String WebMvcTest`) après plusieurs suppositions erronées, cf. les README `audit-service`/`lawyer-service` pour l'historique complet du piège.

### Spring Cloud Gateway Server WebMVC, pas la variante réactive

Ce projet utilise le module **WebMVC** de Spring Cloud Gateway (`spring.cloud.gateway.server.webmvc.routes`), basé sur Servlet/Tomcat classique, pas le module réactif historique (`spring.cloud.gateway.routes`, basé sur WebFlux/Netty). Les deux ont des syntaxes de configuration et des mécanismes de filtrage différents ; en cas de recherche de documentation, bien vérifier qu'elle concerne la variante WebMVC, plus récente et moins répandue dans les ressources en ligne existantes.

### Comptes de test créés en base directement (pas d'inscription admin via API)

Il n'existe aucune route d'inscription pour un compte `ADMIN` (`/api/auth/register` est réservé aux clients, `/register/lawyer` aux avocats), un premier compte admin doit être inséré directement en base (`INSERT INTO users ...` dans `authdb`, avec un hash BCrypt valide en préfixe `$2a$`), cf. le README d'`auth-service`.

---

## Limites connues

- **Pas de rate limiting ni de circuit breaker**, un microservice en panne fait simplement échouer les requêtes qui le concernent, sans dégradation gracieuse.
- **Pas de vérification de rôle au niveau gateway**, un token valide mais avec un rôle insuffisant passe la gateway et se fait refuser par le microservice cible (comportement voulu, mais ça veut dire que la gateway ne peut pas économiser un aller-retour réseau inutile dans ce cas).
- **CORS actuellement limité à `http://localhost:5173`** (origine du frontend en développement), à étendre/paramétrer pour un environnement de production avec un vrai nom de domaine.
- **Chaque microservice garde sa propre configuration CORS**, rendue redondante par la gateway mais jamais retirée (cf. section CORS ci-dessus), un microservice appelé directement (hors gateway, par erreur ou en dev local) continuerait à répondre correctement en CORS de son côté, ce qui masque le fait que la gateway est censée être le seul point d'entrée prévu.