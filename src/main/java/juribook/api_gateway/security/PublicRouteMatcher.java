package juribook.api_gateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * Whitelist des routes publiques — recopiée manuellement à partir des
 * règles .permitAll() déjà déclarées dans les SecurityConfig de chaque
 * service (auth/lawyer/booking ; notification et audit n'ont aucune
 * route publique en dehors d'actuator/swagger).
 *
 * ⚠️ MAINTENANCE : cette liste peut dériver de la réalité si un service
 * change ses propres règles sans que cette classe soit mise à jour en
 * miroir. Aucun mécanisme de synchronisation automatique, cf. le
 * README pour la discussion complète de ce compromis.
 */
@Component
public class PublicRouteMatcher {

    private record PublicRoute(HttpMethod method, String pattern) {}

    private static final List<PublicRoute> PUBLIC_ROUTES = List.of(
        // ── auth-service ──
        new PublicRoute(HttpMethod.POST, "/api/auth/register"),
        new PublicRoute(HttpMethod.POST, "/api/auth/register/lawyer"),
        new PublicRoute(HttpMethod.POST, "/api/auth/login"),
        new PublicRoute(HttpMethod.POST, "/api/auth/refresh"),
        new PublicRoute(HttpMethod.GET,  "/api/users/*/contact"), // inter-services

        // ── lawyer-service ──
        new PublicRoute(HttpMethod.GET, "/api/lawyers"),
        new PublicRoute(HttpMethod.GET, "/api/lawyers/*"),
        new PublicRoute(HttpMethod.GET, "/api/lawyers/*/reviews"),
        new PublicRoute(HttpMethod.GET, "/api/specialties"),

        // ── booking-service ──
        new PublicRoute(HttpMethod.GET, "/api/lawyers/*/availabilities"),
        new PublicRoute(HttpMethod.GET, "/api/lawyers/*/slots"),
        new PublicRoute(HttpMethod.GET, "/api/waitlist/*"),
        new PublicRoute(HttpMethod.GET, "/api/bookings/*"),

        // ── observabilité / documentation, tous services ──
        new PublicRoute(HttpMethod.GET, "/actuator/health")
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public boolean isPublic(String method, String path) {
        return PUBLIC_ROUTES.stream().anyMatch(route ->
            route.method().name().equals(method) && pathMatcher.match(route.pattern(), path)
        );
    }
}