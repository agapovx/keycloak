package org.keycloak.authentication.authenticators.client;

import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.List;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.ClientAuthenticationFlowContext;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.crypto.RSAProvider;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.Urls;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class JWTClientAuthenticator extends AbstractClientAuthenticator {

    protected static Logger logger = Logger.getLogger(JWTClientAuthenticator.class);

    public static final String PROVIDER_ID = "client-signed-jwt";
    public static final String CERTIFICATE_ATTR = "jwt.credential.certificate";

    public static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.DISABLED
    };

    @Override
    public void authenticateClient(ClientAuthenticationFlowContext context) {
        MultivaluedMap<String, String> params = context.getHttpRequest().getDecodedFormParameters();

        String clientAssertionType = params.getFirst(OAuth2Constants.CLIENT_ASSERTION_TYPE);
        String clientAssertion = params.getFirst(OAuth2Constants.CLIENT_ASSERTION);

        if (clientAssertionType == null) {
            Response challengeResponse = ClientAuthUtil.errorResponse(Response.Status.BAD_REQUEST.getStatusCode(), "invalid_client", "Parameter client_assertion_type is missing");
            context.challenge(challengeResponse);
            return;
        }

        if (!clientAssertionType.equals(OAuth2Constants.CLIENT_ASSERTION_TYPE_JWT)) {
            Response challengeResponse = ClientAuthUtil.errorResponse(Response.Status.BAD_REQUEST.getStatusCode(), "invalid_client", "Parameter client_assertion_type has value '"
                    + clientAssertionType + "' but expected is '" + OAuth2Constants.CLIENT_ASSERTION_TYPE_JWT + "'");
            context.challenge(challengeResponse);
            return;
        }

        if (clientAssertion == null) {
            Response challengeResponse = ClientAuthUtil.errorResponse(Response.Status.BAD_REQUEST.getStatusCode(), "invalid_client", "client_assertion parameter missing");
            context.failure(AuthenticationFlowError.INVALID_CLIENT_CREDENTIALS, challengeResponse);
            return;
        }

        try {
            JWSInput jws = new JWSInput(clientAssertion);
            JsonWebToken token = jws.readJsonContent(JsonWebToken.class);

            RealmModel realm = context.getRealm();
            String clientId = token.getIssuer();
            if (clientId == null) {
                throw new RuntimeException("Can't identify client. Issuer missing on JWT token");
            }

            context.getEvent().client(clientId);
            ClientModel client = realm.getClientByClientId(clientId);
            if (client == null) {
                context.failure(AuthenticationFlowError.CLIENT_NOT_FOUND, null);
                return;
            } else {
                context.setClient(client);
            }

            if (!client.isEnabled()) {
                context.failure(AuthenticationFlowError.CLIENT_DISABLED, null);
                return;
            }

            // Get client key and validate signature
            String encodedCertificate = client.getAttribute(CERTIFICATE_ATTR);
            if (encodedCertificate == null) {
                Response challengeResponse = ClientAuthUtil.errorResponse(Response.Status.BAD_REQUEST.getStatusCode(), "unauthorized_client", "Client '" + clientId + "' doesn't have certificate configured");
                context.failure(AuthenticationFlowError.CLIENT_CREDENTIALS_SETUP_REQUIRED, challengeResponse);
                return;
            }

            X509Certificate clientCert = KeycloakModelUtils.getCertificate(encodedCertificate);
            PublicKey clientPublicKey = clientCert.getPublicKey();
            boolean signatureValid;
            try {
                signatureValid = RSAProvider.verify(jws, clientPublicKey);
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("Signature on JWT token failed validation", cause);
            }
            if (!signatureValid) {
                throw new RuntimeException("Signature on JWT token failed validation");
            }

            // Validate other things
            String audience = token.getAudience();
            String expectedAudience = Urls.realmIssuer(context.getUriInfo().getBaseUri(), realm.getName());
            if (audience == null) {
                throw new RuntimeException("Audience is null on JWT");
            }
            if (!audience.equals(expectedAudience)) {
                throw new RuntimeException("Token audience doesn't match domain. Realm audience is '" + expectedAudience + "' but audience from token is '" + audience + "'");
            }

            if (!token.isActive()) {
                throw new RuntimeException("Token is not active");
            }

            context.success();
        } catch (Exception e) {
            logger.error("Error when validate client assertion", e);
            Response challengeResponse = ClientAuthUtil.errorResponse(Response.Status.BAD_REQUEST.getStatusCode(), "unauthorized_client", "Client authentication with signed JWT failed: " + e.getMessage());
            context.failure(AuthenticationFlowError.INVALID_CLIENT_CREDENTIALS, challengeResponse);
        }
    }

    @Override
    public boolean requiresClient() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, ClientModel client) {
        return client.getAttribute(CERTIFICATE_ATTR) != null;
    }

    @Override
    public String getDisplayType() {
        return "Signed Jwt";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public boolean isConfigurablePerClient() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public String getHelpText() {
        return "Validates client based on signed JWT issued by client and signed with the Client private key";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return new LinkedList<>();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
