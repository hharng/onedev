package io.onedev.server.plugin.sso.openid;

import static io.onedev.server.web.translation.Translation._T;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.wicket.Session;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.flow.RedirectToUrlException;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.SerializeException;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.AuthenticationErrorResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationResponseParser;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.UserInfoErrorResponse;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.server.OneDev;
import io.onedev.server.annotation.Editable;
import io.onedev.server.annotation.Password;
import io.onedev.server.annotation.UrlSegment;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.sso.SsoAuthenticated;
import io.onedev.server.model.support.administration.sso.SsoConnector;
import io.onedev.server.security.TrustCertsSSLSocketFactory;
import io.onedev.server.util.oauth.OAuthUtils;
import io.onedev.server.web.page.admin.ssosetting.SsoProcessPage;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

@Editable(name="OpenID", order=10000, description="Refer to this <a href='https://docs.onedev.io/tutorials/security/sso-with-okta' target='_blank'>tutorial</a> for an example setup")
public class OpenIdConnector extends SsoConnector {

	private static final long serialVersionUID = 1L;
	
	private static final Logger logger = LoggerFactory.getLogger(OpenIdConnector.class);

	private static final String SESSION_ATTR_PROVIDER_METADATA = "endpoints";
	
	private static final String SESSION_ATTR_STATE = "state";
	
	private String configurationDiscoveryUrl;
	
	private String clientId;
	
	private String clientSecret;
	
	private String requestScopes = "openid email profile";
	
	private String groupsClaim;
	
	private String buttonImageUrl;
	
	public OpenIdConnector() {
		buttonImageUrl = "/wicket/resource/" + OpenIdConnector.class.getName() + "/openid.png";
	}

	@Editable(order=100, description="Name of the provider will serve two purpose: "
			+ "<ul>"
			+ "<li>Display on login button"
			+ "<li>Form the authorization callback url which will be <i>&lt;server url&gt;/" + SsoProcessPage.MOUNT_PATH + "/" + SsoProcessPage.STAGE_CALLBACK + "/&lt;name&gt;</i>"
			+ "</ul>")
	@UrlSegment // will be used as part of callback url
	@NotEmpty
	@Override
	public String getName() {
		return super.getName();
	}

	@Override
	public void setName(String name) {
		super.setName(name);
	}
	
	@Editable(order=200, description="Specify configuration discovery url of your OpenID provider, " +
			"for instance: <code>https://openid.example.com/.well-known/openid-configuration</code>. " +
			"Make sure to use HTTPS protocol as OneDev relies on TLS encryption to ensure token " +
			"validity")
	@NotEmpty
	public String getConfigurationDiscoveryUrl() {
		return configurationDiscoveryUrl;
	}

	public void setConfigurationDiscoveryUrl(String configurationDiscoveryUrl) {
		this.configurationDiscoveryUrl = configurationDiscoveryUrl;
	}

	@Editable(order=1000, description="OpenID client identification will be assigned by your OpenID "
			+ "provider when registering this OneDev instance as client application")
	@NotEmpty
	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	@Editable(order=1100, description="OpenID client secret will be generated by your OpenID "
			+ "provider when registering this OneDev instance as client application")
	@Password
	@NotEmpty
	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}
	
	@Override
	public SsoAuthenticated processLoginResponse() {
		HttpServletRequest request = (HttpServletRequest) RequestCycle.get().getRequest().getContainerRequest();
		try {
			AuthenticationResponse authenticationResponse = AuthenticationResponseParser.parse(
					new URI(request.getRequestURI() + "?" + request.getQueryString()));
			if (authenticationResponse instanceof AuthenticationErrorResponse) {
				throw buildException(authenticationResponse.toErrorResponse().getErrorObject()); 
			} else {
				AuthenticationSuccessResponse authenticationSuccessResponse = authenticationResponse.toSuccessResponse();
				
				String state = (String) Session.get().getAttribute(SESSION_ATTR_STATE);
				
				if (state == null || !state.equals(authenticationSuccessResponse.getState().getValue()))
					throw new AuthenticationException(_T("Unsolicited OIDC authentication response"));
				
				AuthorizationGrant codeGrant = new AuthorizationCodeGrant(
						authenticationSuccessResponse.getAuthorizationCode(), getCallbackUri());

				ClientID clientID = new ClientID(getClientId());
				com.nimbusds.oauth2.sdk.auth.Secret clientSecret = new com.nimbusds.oauth2.sdk.auth.Secret(getClientSecret());
				ClientAuthentication clientAuth = createTokenRequestAuthentication(clientID, clientSecret);
				TokenRequest tokenRequest = new TokenRequest(
						new URI(getCachedProviderMetadata().getTokenEndpoint()), clientAuth, codeGrant);
				
				HTTPRequest httpRequest = tokenRequest.toHTTPRequest();
				httpRequest.setSSLSocketFactory(TrustCertsSSLSocketFactory.getDefault());
				httpRequest.setAccept(ContentType.APPLICATION_JSON.toString());
				HTTPResponse httpResponse = httpRequest.send();
				TokenResponse tokenResponse = parseOIDCTokenResponse(httpResponse);
				
				if (tokenResponse.indicatesSuccess()) 
					return processTokenResponse((OIDCTokenResponse)tokenResponse.toSuccessResponse());
				else 
					throw buildException(tokenResponse.toErrorResponse().getErrorObject());
			}
		} catch (ParseException | URISyntaxException|SerializeException|IOException e) {
			throw new RuntimeException(e);
		}
	}

	protected TokenResponse parseOIDCTokenResponse(HTTPResponse response) throws ParseException {
		return OIDCTokenResponseParser.parse(response);
	}

	protected ClientAuthentication createTokenRequestAuthentication(ClientID id, com.nimbusds.oauth2.sdk.auth.Secret secret) {
		return new ClientSecretBasic(id, secret);
	}

	@Nullable
	private String getStringValue(Object jsonValue) {
		if (jsonValue instanceof String) {
			return (String) jsonValue;
		} else if (jsonValue instanceof JSONArray) {
			JSONArray jsonArray = (JSONArray) jsonValue;
			if (!jsonArray.isEmpty())
				return (String) jsonArray.iterator().next();
			else
				return null;
		} else {
			return null;
		}
	}
	
	protected SsoAuthenticated processTokenResponse(OIDCTokenResponse tokenResponse) {
		try {
			JWT idToken = tokenResponse.getOIDCTokens().getIDToken();
			JWTClaimsSet claims = idToken.getJWTClaimsSet();
			
			if (!claims.getIssuer().equals(getCachedProviderMetadata().getIssuer()))
				throw new AuthenticationException(_T("Inconsistent issuer in provider metadata and ID token"));
			
			DateTime now = new DateTime();
			
			if (claims.getIssueTime() != null && claims.getIssueTime().after(now.plusSeconds(10).toDate()))
				throw new AuthenticationException(_T("Invalid issue date of ID token"));
			
			if (claims.getExpirationTime() != null && now.toDate().after(claims.getExpirationTime()))
				throw new AuthenticationException(_T("ID token was expired"));

			String subject = claims.getSubject();
			String email = claims.getStringClaim("email");
			String userName = claims.getStringClaim("preferred_username");
			String fullName = claims.getStringClaim("name");
			List<String> groups;
			if (getGroupsClaim() != null) {
				var groupsArray = claims.getStringArrayClaim(getGroupsClaim());
				if (groupsArray != null)
					groups = Lists.newArrayList(groupsArray);
				else 
					groups = null;
			} else {
				groups = null;
			}
			
			var accessToken = tokenResponse.getOIDCTokens().getBearerAccessToken();
			if (email == null || userName == null || fullName == null 
					|| getGroupsClaim() != null && groups == null) {

				UserInfoRequest userInfoRequest = new UserInfoRequest(
						new URI(getCachedProviderMetadata().getUserInfoEndpoint()), accessToken);
				var httpRequest = userInfoRequest.toHTTPRequest();
				httpRequest.setSSLSocketFactory(TrustCertsSSLSocketFactory.getDefault());
				var httpResponse = httpRequest.send();

				if (httpResponse.getStatusCode() == HTTPResponse.SC_OK) {
					JSONObject json = httpResponse.getBodyAsJSONObject();
					if (!subject.equals(json.get("sub")))
						throw new AuthenticationException(_T("OIDC error: Inconsistent sub in ID token and userinfo"));

					if (email == null) 
						email = getStringValue(json.get("email"));
					if (email == null)
						throw new AuthenticationException(_T("OIDC error: No email claim returned"));

					if (userName == null) 
						userName = getStringValue(json.get("preferred_username"));
					if (userName == null)
						userName = email;

					if (fullName == null)
						fullName = getStringValue(json.get("name"));

					if (getGroupsClaim() != null && groups == null) {
						var jsonArray = (JSONArray) json.get(getGroupsClaim());
						if (jsonArray != null) {
							groups = new ArrayList<>();
							for (Object group: jsonArray)
								groups.add((String) group);
						} else {
							logger.warn(_T("No groups claim returned"));
						}
					}
				} else {
					throw buildException(UserInfoErrorResponse.parse(httpResponse).getErrorObject());
				}
			}
			userName = StringUtils.substringBefore(userName, "@");
			if (groups != null)
				groups = convertGroups(accessToken, groups);
			return new SsoAuthenticated(userName, email, fullName, groups, null, this);
		} catch (Exception e) {
			throw ExceptionUtils.unchecked(e);
		}
	}
	
	protected List<String> convertGroups(BearerAccessToken accessToken, List<String> groups) {
		return groups;
	}
	
	protected RuntimeException buildException(ErrorObject error) {
		return new AuthenticationException(OAuthUtils.getErrorMessage(error));
	}

	@Override
	public void initiateLogin() {
		try {
			ClientID clientID = new ClientID(getClientId());
			
			State state = new State("OIDC-" + UUID.randomUUID());
			Session.get().setAttribute(SESSION_ATTR_STATE, state.getValue());
			Session.get().setAttribute(SESSION_ATTR_PROVIDER_METADATA, discoverProviderMetadata());
			
			String scopes = getRequestScopes();
			
			AuthenticationRequest request = new AuthenticationRequest(
					new URI(getCachedProviderMetadata().getAuthorizationEndpoint()),
				    new ResponseType("code"), Scope.parse(scopes), clientID, getCallbackUri(),
				    state, new Nonce());
			throw new RedirectToUrlException(request.toURI().toString());
		} catch (URISyntaxException|SerializeException e) {
			throw new RuntimeException(e);
		}		
	}

	@Editable(order=10000, group = "More Settings", description = "Specify OpenID scopes to request")
	@NotEmpty
	public String getRequestScopes() {
		return requestScopes;
	}

	public void setRequestScopes(String requestScopes) {
		this.requestScopes = requestScopes;
	}

	@Editable(order=10100, group = "More Settings", description="Optionally specify the OpenID claim to retrieve " +
			"groups of authenticated user. Depending on the provider, you may need to request additional scopes " +
			"above to make this claim available")
	public String getGroupsClaim() {
		return groupsClaim;
	}

	public void setGroupsClaim(String groupsClaim) {
		this.groupsClaim = groupsClaim;
	}
	
	@Editable(order=10200, group="More Settings", description="Specify image on the login button")
	@NotEmpty
	@Override
	public String getButtonImageUrl() {
		return buttonImageUrl;
	}

	public void setButtonImageUrl(String buttonImageUrl) {
		this.buttonImageUrl = buttonImageUrl;
	}
	
	protected ProviderMetadata discoverProviderMetadata() {
		try {
			JsonNode json = OneDev.getInstance(ObjectMapper.class).readTree(
					new URI(getConfigurationDiscoveryUrl()).toURL());
			return new ProviderMetadata(
					json.get("issuer").asText(),
					json.get("authorization_endpoint").asText(),
					json.get("token_endpoint").asText(), 
					json.get("userinfo_endpoint").asText());
		} catch (IOException | URISyntaxException e) {
			if (e.getMessage() != null) {
				logger.error(_T("Error discovering OIDC metadata"), e);
				throw new AuthenticationException(e.getMessage());
			} else {
				throw new RuntimeException(e);
			}
		}
		
	}
	
	protected ProviderMetadata getCachedProviderMetadata() {
		ProviderMetadata metadata = (ProviderMetadata) Session.get().getAttribute(SESSION_ATTR_PROVIDER_METADATA);
		if (metadata == null)
			throw new AuthenticationException(_T("Unsolicited OIDC response"));
		return metadata;
	}
	
	@Override
	public URI getCallbackUri() {
		String serverUrl = OneDev.getInstance(SettingManager.class).getSystemSetting().getServerUrl();
		try {
			return new URI(serverUrl + "/" + SsoProcessPage.MOUNT_PATH + "/" 
					+ SsoProcessPage.STAGE_CALLBACK + "/" + getName());
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

}
