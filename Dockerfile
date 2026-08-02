# ═══════════════════════════════════════════════════════════
#  Dockerfile - juribook-api-gateway (production)
#  Multi-stage build : Maven → JRE Alpine, non-root, healthcheck
# ═══════════════════════════════════════════════════════════

# ── Étape 1 : Build avec Maven ────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copier le pom.xml en premier pour profiter du cache Docker :
# les dépendances Maven ne sont re-téléchargées que si pom.xml change
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copier le code source et compiler
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Étape 2 : Runtime, minimal et non-root ────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# curl est nécessaire pour le healthcheck (absent par défaut sur
# eclipse-temurin:*-jre-alpine). Utilisateur dédié, non-root — ne
# jamais faire tourner un service en production avec les droits
# root dans le conteneur.
RUN apk add --no-cache curl && \
    addgroup -S juribook && adduser -S juribook -G juribook

COPY --from=build --chown=juribook:juribook /app/target/*.jar app.jar

USER juribook

EXPOSE 8080

# Définir les options JVM pour limiter l'utilisation de la mémoire à 75% de la RAM disponible
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Healthcheck pour vérifier que l'application est bien démarrée et répond
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Lancer l'application avec les options JVM définies dans JAVA_OPTS
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]