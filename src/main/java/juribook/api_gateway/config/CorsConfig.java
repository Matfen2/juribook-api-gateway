package juribook.api_gateway.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Configuration CORS de l'api-gateway — les deux filtres enregistrés
 * explicitement dans le MÊME fichier, avec un ordre garanti par
 * FilterRegistrationBean des deux côtés (plutôt que de mélanger un
 * @Component+@Order et un FilterRegistrationBean séparé, qui laissait
 * planer un doute sur l'ordre relatif réellement appliqué par Spring).
 *
 * 1. DedupeCorsHeaderFilter (order 1)  — enveloppe la réponse en premier
 * 2. CorsFilter             (order 2)  — pose les en-têtes CORS de la gateway
 *
 * Contexte (Option B) : chaque service (auth/lawyer/booking/audit) a SA
 * PROPRE config CORS (héritée d'avant la gateway), et pose donc AUSSI ses
 * propres en-têtes CORS sur sa réponse. Quand la gateway recopie cette
 * réponse en y ajoutant en plus les siens, Access-Control-Allow-Origin
 * se retrouve avec 2 valeurs identiques → le navigateur rejette quand
 * même toute la réponse ("multiple values ... but only one is allowed").
 *
 * DedupeCorsHeaderFilter enveloppe la réponse pour qu'une seule valeur
 * par en-tête CORS survive, quel que soit qui essaie de le poser après
 * coup (la gateway elle-même, ou la recopie de la réponse du service
 * en aval).
 */
@Configuration
public class CorsConfig {

    private static final List<String> ALLOWED_ORIGINS = List.of("http://localhost:5173", "https://juribook.fr", "https://www.juribook.fr");
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "Accept");

    // ── 1. Filtre de déduplication — doit s'exécuter EN PREMIER ──
    @Bean
    public FilterRegistrationBean<DedupeCorsHeaderFilter> dedupeCorsHeaderFilter() {
        FilterRegistrationBean<DedupeCorsHeaderFilter> registration =
                new FilterRegistrationBean<>(new DedupeCorsHeaderFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    // ── 2. Filtre CORS de la gateway — s'exécute juste après ──
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(ALLOWED_ORIGINS);
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * Enveloppe la réponse Servlet : la première valeur posée pour chaque
     * en-tête CORS gagne, toute tentative ultérieure (venant du service en
     * aval, recopiée par le proxy de la gateway) est ignorée silencieusement.
     *
     * PAS annoté @Component : enregistré exclusivement via le
     * FilterRegistrationBean ci-dessus, pour éviter tout risque de double
     * enregistrement (scan de composants + bean explicite).
     */
    static class DedupeCorsHeaderFilter implements Filter {

        /**
         * ⚠️ Comparaison insensible à la casse (TreeSet + CASE_INSENSITIVE_ORDER) —
         * bug corrigé le 8 juillet 2026 : curl a révélé que la réponse contenait
         * "Access-Control-Allow-Origin" (posé par cette gateway) ET
         * "access-control-allow-origin" en minuscules (recopié depuis la réponse
         * d'auth-service par le mécanisme de proxy). Un Set.of() classique
         * (sensible à la casse) ne reconnaissait pas les deux noms comme le même
         * en-tête, et laissait donc passer le doublon silencieusement.
         */
        private static final Set<String> CORS_HEADERS_TO_DEDUPE;
        static {
            java.util.TreeSet<String> caseInsensitive = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            caseInsensitive.addAll(List.of(
                    "Access-Control-Allow-Origin",
                    "Access-Control-Allow-Credentials",
                    "Access-Control-Expose-Headers",
                    "Access-Control-Allow-Methods",
                    "Access-Control-Allow-Headers"
            ));
            CORS_HEADERS_TO_DEDUPE = java.util.Collections.unmodifiableSet(caseInsensitive);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (response instanceof HttpServletResponse httpResponse) {
                chain.doFilter(request, new DedupingResponseWrapper(httpResponse));
            } else {
                chain.doFilter(request, response);
            }
        }

        private static class DedupingResponseWrapper extends HttpServletResponseWrapper {

            DedupingResponseWrapper(HttpServletResponse response) {
                super(response);
            }

            @Override
            public void addHeader(String name, String value) {
                if (CORS_HEADERS_TO_DEDUPE.contains(name) && containsHeader(name)) {
                    return;
                }
                super.addHeader(name, value);
            }

            @Override
            public void setHeader(String name, String value) {
                // setHeader remplace déjà toute valeur existante par construction
                // (contrairement à addHeader) — rien à dédupliquer, mais on
                // s'assure explicitement qu'un setHeader ultérieur écrase bien
                // toute valeur posée via addHeader avant lui.
                super.setHeader(name, value);
            }
        }
    }
}