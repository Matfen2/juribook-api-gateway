package juribook.api_gateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Valide qu'un JWT est bien signé et non expiré, ne vérifie jamais de
 * rôle (cf. GatewayJwtFilter et le README pour la justification de ce
 * choix de scope). Ne génère jamais de token, contrairement à
 * auth-service, lecture seule ici.
 */
@Service
public class JwtValidationService {

    private final SecretKey key;

    public JwtValidationService(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws io.jsonwebtoken.JwtException si le token est invalide,
     * mal signé, ou expiré, laissé remonter tel quel, le filtre
     * appelant décide de la réponse HTTP à renvoyer.
     */
    public void validate(String token) {
        Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
    }
}