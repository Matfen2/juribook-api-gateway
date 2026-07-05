package juribook.api_gateway.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Authentification centralisée (api-gateway), un jakarta.servlet.Filter
 * classique plutôt qu'un HandlerFilterFunction spécifique à Spring
 * Cloud Gateway : tourne au niveau conteneur Servlet, donc AVANT que
 * le routage de la gateway ne décide vers quel service transmettre.
 * Même famille de mécanisme que JwtAuthenticationFilter dans les 5
 * autres services (cohérence de style), mais volontairement PAS de
 * Spring Security ici, juste une vérification de token, pas de
 * gestion de rôle (cf. PublicRouteMatcher et le README pour le détail
 * du choix de scope "authentification, pas autorisation").
 *
 * Si le token est valide, la requête est transmise TELLE QUELLE
 * (en-tête Authorization inchangé) — chaque service en aval refait sa
 * propre validation et vérifie les rôles exactement comme aujourd'hui.
 * Défense en profondeur assumée : passer par la gateway ne dispense
 * jamais un service de sa propre vérification.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayJwtFilter implements Filter {

    private final JwtValidationService jwtValidationService;
    private final PublicRouteMatcher publicRouteMatcher;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String method = httpRequest.getMethod();
        String path = httpRequest.getRequestURI();

        if (publicRouteMatcher.isPublic(method, path)) {
            chain.doFilter(request, response);
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Requête rejetée (token manquant) : {} {}", method, path);
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token manquant");
            return;
        }

        String token = authHeader.substring(7);
        try {
            jwtValidationService.validate(token);
        } catch (JwtException e) {
            log.debug("Requête rejetée (token invalide ou expiré) : {} {} — {}", method, path, e.getMessage());
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token invalide ou expiré");
            return;
        }

        chain.doFilter(request, response);
    }
}