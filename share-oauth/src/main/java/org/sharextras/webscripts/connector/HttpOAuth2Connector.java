package org.sharextras.webscripts.connector;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;
import org.springframework.extensions.config.RemoteConfigElement.ConnectorDescriptor;
import org.springframework.extensions.config.RemoteConfigElement.EndpointDescriptor;
import org.springframework.extensions.surf.exception.ConnectorServiceException;
import org.springframework.extensions.surf.exception.CredentialVaultProviderException;
import org.springframework.extensions.surf.util.FakeHttpServletResponse;
import org.springframework.extensions.webscripts.Format;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.connector.ConnectorContext;
import org.springframework.extensions.webscripts.connector.ConnectorService;
import org.springframework.extensions.webscripts.connector.Credentials;
import org.springframework.extensions.webscripts.connector.HttpConnector;
import org.springframework.extensions.webscripts.connector.RemoteClient;
import org.springframework.extensions.webscripts.connector.Response;
import org.springframework.extensions.webscripts.connector.ResponseStatus;
import org.springframework.extensions.webscripts.json.JSONWriter;

/**
 * Connector for connecting to OAuth 2.0-protected resources
 * 
 * TODO Return a 401 straight away if there is no user context? The AuthenticatingConnector will always try the first request
 *      unauthenticated otherwise, and this may not always return a 401 if the service supports anonymous access.
 * 
 * @author wabson
 */
public class HttpOAuth2Connector extends HttpConnector
{
    public static final String HEADER_AUTHORIZATION = "Authorization";
    
    public static final String AUTH_METHOD_OAUTH = "OAuth";
    public static final String AUTH_METHOD_BEARER = "Bearer";

    private static final String VAULT_PROVIDER_ID = "oAuth2CredentialVaultProvider";
    private static final String USER_ID = "_alf_USER_ID";

    public static final String PARAM_AUTH_METHOD = "auth-method";
    public static final String PARAM_TOKEN_ENDPOINT = "token-source";

    private static Log logger = LogFactory.getLog(HttpOAuth2Connector.class);
    
    private ApplicationContext applicationContext;
    
    public HttpOAuth2Connector(ConnectorDescriptor descriptor, String endpoint)
    {
        super(descriptor, endpoint);
    }
    
    /**
     * Sets the Spring application context
     * 
     * @param applicationContext    the Spring application context
     */
    public void setApplicationContext(ApplicationContext applicationContext)
    {
        super.setApplicationContext(applicationContext);
        this.applicationContext = applicationContext;
    }
    
    private String getAuthenticationMethod()
    {
        String descriptorMethod = descriptor.getStringProperty(PARAM_AUTH_METHOD);
        return descriptorMethod != null ? descriptorMethod : AUTH_METHOD_OAUTH;
    }
    
    protected boolean hasAccessToken()
    {
        return !(getConnectorSession() == null || getConnectorSession().getParameter(OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN) == null);
    }

    protected boolean hasRefreshToken()
    {
        return !(getConnectorSession() == null || getConnectorSession().getParameter(OAuth2Authenticator.CS_PARAM_REFRESH_TOKEN) == null);
    }

    protected String getAccessToken()
    {
        return getConnectorSession() != null ? getConnectorSession().getParameter(OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN) : null;
    }

    protected String getRefreshToken()
    {
        return getConnectorSession() != null ? getConnectorSession().getParameter(OAuth2Authenticator.CS_PARAM_REFRESH_TOKEN) : null;
    }

    @Override
    public Response call(String uri, ConnectorContext context, HttpServletRequest req, HttpServletResponse res)
    {
        String endpointId = getEndpointId(uri, req);
        try
        {
            Response resp = null;
            if (!hasAccessToken())
            {
                loadTokens(endpointId, req);
            }

            if (hasAccessToken())
            {
                // Wrap the response object, since it gets committed straight away, and we may need to retry
                FakeHttpServletResponse wrappedRes = new FakeHttpServletResponse(res);
                
                // First call
                if (logger.isDebugEnabled())
                    logger.debug("Loading resource " + uri + " - first attempt");
                resp = callInternal(uri, context, req, wrappedRes);
                
                if (logger.isDebugEnabled())
                    logger.debug("Response status " + resp.getStatus().getCode() + " " + resp.getStatus().getCodeName());
                
                // We could have a revoked or expired access token cached which has been updated in the repo
                
                if (resp.getStatus().getCode() == ResponseStatus.STATUS_UNAUTHORIZED || 
                        resp.getStatus().getCode() == ResponseStatus.STATUS_FORBIDDEN)
                {
                    if (logger.isDebugEnabled())
                        logger.debug("Loading resource " + uri + " - second attempt");

                    String accessToken = getAccessToken();
                    loadTokens(endpointId, req);
                    
                    // Retry the operation - second call, only if a different access token was found
                    if (hasAccessToken())
                    {
                        if (!getAccessToken().equals(accessToken))
                        {
                            resp = callInternal(uri, context, req, res);
                            if (logger.isDebugEnabled())
                                logger.debug("Response status " + resp.getStatus().getCode() + " " + resp.getStatus().getCodeName());
                        }
                    }
                    else
                    {
                        writeError(res, ResponseStatus.STATUS_UNAUTHORIZED, 
                                "NO_TOKEN", 
                                "No access token is present",
                                null);
                        return null;
                    }
                }
                else
                {
                    copyResponseContent(wrappedRes, res, true);
                }

                // TRY TOKEN REFRESH

                if (resp.getStatus().getCode() == ResponseStatus.STATUS_UNAUTHORIZED)
                {
                    try
                    {
                        String oldToken = getAccessToken();
                        String newToken = doRefresh(endpointId);
                        if (newToken != null && !newToken.equals(oldToken))
                        {
                            connectorSession.setParameter(OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN, newToken);
                            saveTokens(endpointId, req);
                            resp = callInternal(uri, context, req, res);
                        }
                    }
                    catch (TokenRefreshException e)
                    {
                        writeError(res, ResponseStatus.STATUS_INTERNAL_SERVER_ERROR, 
                                "ERR_REFRESH_TOKEN", 
                                "Unable to refresh token",
                                e);
                    }
                }
            }
            else
            {
                writeError(res, ResponseStatus.STATUS_UNAUTHORIZED, 
                        "NO_TOKEN", 
                        "No access token is present",
                        null);
                return null;
                
            }
            
            return resp;
        }
        catch (CredentialVaultProviderException e)
        {
            writeError(res, ResponseStatus.STATUS_INTERNAL_SERVER_ERROR, 
                    "ERR_CREDENTIALSTORE", 
                    "Unable to load credential store",
                    e);
            return null;
        }
        catch (ConnectorServiceException e)
        {
            writeError(res, ResponseStatus.STATUS_INTERNAL_SERVER_ERROR, 
                    "ERR_FETCH_CREDENTIALS", 
                    "Unable to retrieve OAuth credentials from credential vault",
                    e);
            return null;
        }
        catch (IOException e)
        {
            writeError(res, ResponseStatus.STATUS_INTERNAL_SERVER_ERROR, 
                    "ERR_COPY_RESPONSE", 
                    "Error encountered copying outputstream",
                    e);
            return null;
        }
    }

    protected Response callInternal(String uri, ConnectorContext context, HttpServletRequest req, HttpServletResponse res)
    {
        try
        {
            return super.call(uri, context, req, res);
        }
        catch (Throwable t)
        {
            writeError(res, ResponseStatus.STATUS_INTERNAL_SERVER_ERROR, 
                    "ERR_CALLOUT", 
                    "Encountered error when attempting to reload",
                    t);
            return null;
        }
    }

    private String getUserId(HttpSession session)
    {
        return (String) session.getAttribute(USER_ID);
    }

    private OAuth2CredentialVault getCredentialVault(String endpointId, HttpServletRequest request, boolean load) 
            throws CredentialVaultProviderException, ConnectorServiceException
    {
        HttpSession session = request.getSession(false);
        if (session != null)
        {
            String userId = getUserId(session);
            ConnectorService connectorService = getConnectorService();

            OAuth2CredentialVault vault = (OAuth2CredentialVault)connectorService.getCredentialVault(session, userId, VAULT_PROVIDER_ID);
            if (load)
            {
                vault.load(endpointId, connectorService.getConnector("alfresco", userId, session));
            }
            return vault;
        }
        else
        {
            logger.error("Session should not be null!");
            return null;
        }
    }

    protected void loadTokens(String endpointId, HttpServletRequest request) throws CredentialVaultProviderException, ConnectorServiceException
    {
        logger.debug("Loading OAuth tokens for endpoint " + endpointId);
        
        OAuth2CredentialVault vault = getCredentialVault(endpointId, request, true);
        Credentials oauthCredentials = vault.retrieve(endpointId);
        if (oauthCredentials != null)
        {
            if (oauthCredentials.getProperty(OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN) != null)
            {
                connectorSession.setParameter(OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN, 
                        oauthCredentials.getProperty(OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN).toString());
                connectorSession.setParameter(OAuth2Authenticator.CS_PARAM_REFRESH_TOKEN, 
                        oauthCredentials.getProperty(OAuth2Authenticator.CS_PARAM_REFRESH_TOKEN).toString());
            }
        }
    }

    protected void saveTokens(String endpointId, HttpServletRequest request) throws CredentialVaultProviderException, ConnectorServiceException
    {
        logger.debug("Saving OAuth tokens for endpoint " + endpointId);
        HttpSession session = request.getSession(false);
        if (session != null)
        {
            String userId = getUserId(session);
            ConnectorService connectorService = getConnectorService();

            OAuth2CredentialVault vault = getCredentialVault(endpointId, request, true);
            Credentials oauthCredentials = vault.retrieve(endpointId);
            if (oauthCredentials != null)
            {
                oauthCredentials.setProperty(
                        OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN, 
                        connectorSession.getParameter(OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN)
                );
                oauthCredentials.setProperty(
                        OAuth2Authenticator.CS_PARAM_REFRESH_TOKEN, 
                        connectorSession.getParameter(OAuth2Authenticator.CS_PARAM_REFRESH_TOKEN)
                );
                vault.save(connectorService.getConnector("alfresco", userId, session));
            }
        }
    }

    private void copyResponseContent(FakeHttpServletResponse source, HttpServletResponse dest, boolean flush) throws IOException
    {
        dest.setStatus(source.getStatus());
        dest.setCharacterEncoding(source.getCharacterEncoding());
        // Copy headers over
        for (Object hdrname : source.getHeaderNames())
        {
            dest.setHeader((String) hdrname, (String) source.getHeader((String) hdrname));
        }
        dest.getOutputStream().write(source.getContentAsByteArray());
        if (flush)
        {
            dest.flushBuffer();
        }
    }

    private void writeError(HttpServletResponse resp, int status, String id, String message, Throwable e)
    {
        resp.setStatus(status);
        resp.setContentType(Format.JSON.mimetype());
        try
        {
            JSONWriter writer = new JSONWriter(resp.getWriter());
            writer.startObject();
            writer.startValue("error").startObject();
            writer.writeValue("id", id);
            writer.writeValue("message", message);
            if (e != null)
            {
                writer.startValue("exception").startObject();
                writer.writeValue("message", e.getMessage());
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                writer.writeValue("stackTrace", sw.toString());
                writer.endObject();
            }
            writer.endObject();
            writer.endObject();
            resp.flushBuffer();
        }
        catch (IOException e1)
        {
            // Unable to get writer from response
            e1.printStackTrace();
        }
    }

    /* (non-Javadoc)
     * @see org.alfresco.connector.HttpConnector#stampCredentials(org.alfresco.connector.RemoteClient, org.alfresco.connector.ConnectorContext)
     */
    @Override
    protected void applyRequestAuthentication(RemoteClient remoteClient, ConnectorContext context)
    {
        String accessToken = null;
        
        // if this connector is managing session info
        if (getConnectorSession() != null)
        {
            // apply alfresco ticket from connector session - i.e. previous login attempt
            accessToken = (String)getConnectorSession().getParameter(OAuth2Authenticator.CS_PARAM_ACCESS_TOKEN);
        }
        
        if (accessToken != null)
        {
            String authorization = getAuthenticationMethod() + " " + accessToken;
            if (logger.isDebugEnabled())
                logger.debug("Adding Authorization header " + authorization);
            Map<String, String> headers = new HashMap<String, String>(1);
            headers.put(HEADER_AUTHORIZATION, authorization);
            remoteClient.setRequestProperties(headers);
        }
    }

    // TODO replace AuthenticationException with something else
    protected String doRefresh(String endpointId) throws TokenRefreshException
    {
        String refreshToken = connectorSession.getParameter(OAuth2Authenticator.CS_PARAM_REFRESH_TOKEN);
        ConnectorService connectorService = getConnectorService();
        EndpointDescriptor epd = connectorService.getRemoteConfig().getEndpointDescriptor(endpointId);

        // First try to get the client-id and access-token-url from the endpoint, then from the connector
        // TODO Make these strings constants in a Descriptor sub-class or interface
        String clientId = epd.getStringProperty("client-id");
        String tokenUrl = epd.getStringProperty("access-token-url");
        if (clientId == null)
        {
            clientId = descriptor.getStringProperty("client-id");
        }
        if (tokenUrl == null)
        {
            tokenUrl = descriptor.getStringProperty("access-token-url");
        }
        /*
        RemoteClient remoteClient = buildRemoteClient(tokenUrl);
        
        // POST to the request new access token URL
        remoteClient.setRequestContentType(OAuth2Authenticator.MIMETYPE_URLENCODED);
        String body = MessageFormat.format(OAuth2Authenticator.POST_LOGIN, URLEncoder.encodeUriComponent(refreshToken), 
                URLEncoder.encodeUriComponent(clientId));
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Content-Length", "" + body.length());
        headers.put("Accept", Format.JSON.mimetype());
        remoteClient.setRequestProperties(headers);

        if (logger.isDebugEnabled())
            logger.debug("Calling refresh token URL " + tokenUrl + " with data " + body);

        Response response = remoteClient.call(tokenUrl, body);
        int statusCode = response.getStatus().getCode();
        String tokenResp = response.getResponse();
        */

        HttpClient client = new HttpClient();
        PostMethod method = new PostMethod(tokenUrl);
        method.addParameter("grant_type", "refresh_token");
        method.addParameter("refresh_token", refreshToken);
        method.addParameter("client_id", clientId);
        method.addRequestHeader("Accept", Format.JSON.mimetype());
        
        int statusCode;
        try
        {
            statusCode = client.executeMethod(method);
            byte[] responseBody = method.getResponseBody();
            String tokenResp = new String(responseBody, Charset.forName("UTF-8"));

            
            if (statusCode == Status.STATUS_OK)
            {
                String accessToken;
                try
                {
                    JSONObject json = new JSONObject(tokenResp);
                    accessToken = json.getString("access_token");
                } 
                catch (JSONException jErr)
                {
                    // the ticket that came back could not be parsed
                    // this will cause the entire handshake to fail
                    throw new TokenRefreshException(
                            "Unable to retrieve access token from provider response", jErr);
                }
                
                if (logger.isDebugEnabled())
                    logger.debug("Parsed access token: " + accessToken);
                
                return accessToken;
            }
            else
            {
                if (logger.isDebugEnabled())
                    logger.debug("Token refresh failed, received response code: " + statusCode);
                    logger.debug("Received response " + tokenResp);
                return null;
            }
        }
        catch (HttpException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return null;
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return null;
        }
    }
    
    public String getEndpointId()
    {
        return descriptor.getStringProperty(PARAM_TOKEN_ENDPOINT);
    }

    private String getEndpointId(String uri, HttpServletRequest request)
    {
        return getEndpointId() != null ? getEndpointId() : 
            request.getPathInfo().replaceAll(uri, "").replaceAll("/proxy/", "");
        
    }

    private ConnectorService getConnectorService()
    {
        return (ConnectorService) applicationContext.getBean("connector.service");
    }
}

class TokenRefreshException extends Exception
{
    private static final long serialVersionUID = 7258987860003313538L;

    public TokenRefreshException()
    {
    }

    public TokenRefreshException(String message)
    {
        super(message);
    }

    public TokenRefreshException(Throwable cause)
    {
        super(cause);
    }

    public TokenRefreshException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public TokenRefreshException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace)
    {
        super(message, cause, enableSuppression, writableStackTrace);
    }

}
