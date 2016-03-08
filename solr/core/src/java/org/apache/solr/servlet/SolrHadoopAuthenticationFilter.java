package org.apache.solr.servlet;
/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements. See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

import org.apache.commons.lang.reflect.FieldUtils;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.AuthInfo;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.imps.DefaultACLProvider;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.hadoop.security.authentication.server.AuthenticationFilter;
import org.apache.hadoop.security.authentication.server.AuthenticationHandler;
import org.apache.hadoop.security.authentication.server.AuthenticationToken;
import org.apache.hadoop.security.authentication.server.KerberosAuthenticationHandler;
import org.apache.hadoop.security.authentication.server.PseudoAuthenticationHandler;
import org.apache.hadoop.security.authentication.util.SignerException;
import org.apache.hadoop.security.authentication.util.SignerSecretProvider;
import org.apache.hadoop.security.authentication.util.Signer;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticationFilter;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticationHandler;
import org.apache.hadoop.security.token.delegation.web.HttpUserGroupInformation;
import org.apache.hadoop.security.token.delegation.web.KerberosDelegationTokenAuthenticationHandler;
import org.apache.hadoop.security.token.delegation.web.PseudoDelegationTokenAuthenticationHandler;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod;

import static org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticationFilter.PROXYUSER_PREFIX;

import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkACLProvider;
import org.apache.solr.common.cloud.ZkCredentialsProvider;
import org.apache.solr.common.cloud.ZkCredentialsProvider.ZkCredentials;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.ConfigSolr;
import org.apache.solr.core.HdfsDirectoryFactory;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.util.HdfsUtil;
import org.apache.zookeeper.data.ACL;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import org.apache.solr.servlet.SolrRequestParsers;
import org.apache.solr.servlet.authentication.AuthenticationHandlerUtil;
import org.apache.solr.servlet.authentication.LdapAuthenticationHandler;
import org.apache.solr.servlet.authentication.MultiSchemeAuthenticationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authentication filter that extends Hadoop-auth AuthenticationFilter to override
 * the configuration loading.
 */
public class SolrHadoopAuthenticationFilter extends DelegationTokenAuthenticationFilter {
  private static Logger LOG = LoggerFactory.getLogger(SolrHadoopAuthenticationFilter.class);
  public static final String SOLR_PREFIX = "solr.authentication.";
  public static final String SOLR_PROXYUSER_PREFIX = "solr.security.proxyuser.";

  private boolean skipAuthFilter = false;
  
  // The ProxyUserFilter can't handle options, let's handle it here
  private HttpServlet optionsServlet;
  private CuratorFramework curatorFramework;
  private String zkHost;
  // Extract the private field declared in the super-class. This is required to override
  // the <code>getToken</code> method.
  Signer signerCopy;

  private static String superUser = System.getProperty("solr.authorization.superuser", "solr");

  /**
   * Request attribute constant for the user name.
   */
  public static final String USER_NAME = "solr.user.name";

  /**
   * Request attribute constant for the ProxyUser name.
   */
  public static final String DO_AS_USER_NAME = "solr.do.as.user.name";

  /**
   * Http param for requesting ProxyUser support.
   */
  public static final String DO_AS_PARAM = "doAs";

  /**
   * Delegation token kind
   */
  public static final String TOKEN_KIND = "solr-dt";

  /**
   * Initialize the filter.
   *
   * @param filterConfig filter configuration.
   * @throws ServletException thrown if the filter could not be initialized.
   */
  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    // be consistent with HdfsDirectoryFactory
    String kerberosEnabledProp = System.getProperty(HdfsDirectoryFactory.KERBEROS_ENABLED);
    if (kerberosEnabledProp != null && StrUtils.parseBoolean(kerberosEnabledProp)) {
      Configuration conf = getConf();
      conf.set("hadoop.security.authentication", "kerberos");
      UserGroupInformation.setConfiguration(conf);
    }
    if (filterConfig != null) {
      SolrResourceLoader loader = new SolrResourceLoader(SolrResourceLoader.locateSolrHome());
      ConfigSolr cfg = SolrDispatchFilter.loadConfigSolr(loader);
      zkHost = cfg.getZkHost();
      if (isZkEnabled()) { // needs to be set before super.init
        final int connectionTimeoutMs = 30000; // this value is currently harded in solr, see SOLR-7561.
        filterConfig.getServletContext().setAttribute("signer.secret.provider.zookeeper.curator.client",
          getCuratorClient(connectionTimeoutMs, cfg.getZkClientTimeout()));
      }
      // the filterConfig is only null for simple unit tests and the superclasses expect
      // a non-null filterConfig.
      super.init(filterConfig);
    } else {
      zkHost = System.getProperty("zkHost");
    }
    optionsServlet = new HttpServlet() {};
    optionsServlet.init();
    // ensure the admin requests add the request
    SolrRequestParsers.DEFAULT.setAddRequestHeadersToContext(true);

    // Extract the private field declared in the super-class. This is required to override
    // the <code>getToken</code> method.
    try {
      this.signerCopy = (Signer)FieldUtils.readField(this, "signer", true);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Initialization failed due to " + e.getLocalizedMessage(), e);
    }
  }

  /**
   * Destroy the filter.
   */
  @Override
  public void destroy() {
    optionsServlet.destroy();
    optionsServlet = null;
    if (curatorFramework != null) curatorFramework.close();
    curatorFramework = null;
    signerCopy = null;
    super.destroy();
  }

  /**
   * Return the ProxyUser Configuration.  System properties beginning with
   * {#SOLR_PROXYUSER_PREFIX} will be added to the configuration.
   */
  @Override
  protected Configuration getProxyuserConfiguration(FilterConfig filterConfig)
      throws ServletException {
    Configuration conf = new Configuration(false);
    for (Enumeration e = System.getProperties().propertyNames(); e.hasMoreElements();) {
      String key = e.nextElement().toString();
      if (key.startsWith(SOLR_PROXYUSER_PREFIX)) {
        conf.set(PROXYUSER_PREFIX + "." + key.substring(SOLR_PROXYUSER_PREFIX.length()),
          System.getProperty(key));
      }
    }
    // superuser must be able to proxy any user in order to properly
    // forward requests
    final String superUserGroups = PROXYUSER_PREFIX + "." + superUser + ".groups";
    final String superUserHosts = PROXYUSER_PREFIX + "." + superUser + ".hosts";
    if (conf.get(superUserGroups) == null && conf.get(superUserHosts) == null) {
      conf.set(superUserGroups, "*");
      conf.set(superUserHosts, "*");
    } else {
      LOG.warn("Not automatically granting proxy privileges to superUser: " + superUser
        + " because user groups or user hosts already set for superUser");
    }
    return conf;
  }

  private String getZkChroot() {
    return zkHost != null?
      zkHost.substring(zkHost.indexOf("/"), zkHost.length()) : "/solr";
  }

  protected void setDefaultDelegationTokenProp(Properties properties, String name, String value) {
    String currentValue = properties.getProperty(name);
    if (null == currentValue) {
      properties.setProperty(name, value);
    } else if (!value.equals(currentValue)) {
      LOG.debug("Default delegation token configuration overriden.  Default: " + value
        + " Actual: " + currentValue);
    }
  }

  /**
   * Convert Solr Zk Credentials/ACLs to Curator versions
   */
  protected static class SolrZkToCuratorCredentialsACLs {
    private final ACLProvider aclProvider;
    private final List<AuthInfo> authInfos;

    public SolrZkToCuratorCredentialsACLs() {
      SecureProviderSolrZkClient zkClient = new SecureProviderSolrZkClient();
      final ZkACLProvider zkACLProvider = zkClient.getZkACLProvider();
      this.aclProvider = createACLProvider(zkClient);
      this.authInfos = createAuthInfo(zkClient);
    }

    public ACLProvider getACLProvider() { return aclProvider; }
    public List<AuthInfo> getAuthInfos() { return authInfos; }

    private ACLProvider createACLProvider(SecureProviderSolrZkClient zkClient) {
      final ZkACLProvider zkACLProvider = zkClient.getZkACLProvider();
      return new ACLProvider() {
        @Override
        public List<ACL> getDefaultAcl() {
          return zkACLProvider.getACLsToAdd(null);
        }

        @Override
        public List<ACL> getAclForPath(String path) {
          List<ACL> acls = zkACLProvider.getACLsToAdd(path);
          return acls;
        }
      };
    }

    private List<AuthInfo> createAuthInfo(SecureProviderSolrZkClient zkClient) {
      List<AuthInfo> ret = new LinkedList<AuthInfo>();
      ZkCredentialsProvider credentialsProvider =
        zkClient.getZkCredentialsProvider();
      for (ZkCredentials zkCredentials : credentialsProvider.getCredentials()) {
        ret.add(new AuthInfo(zkCredentials.getScheme(), zkCredentials.getAuth()));
      }
      return ret;
    }
  }

  /**
   * SolrZkClient with accessible ZkACLProvider and ZkCredentialsProvider
   */
  private static class SecureProviderSolrZkClient extends SolrZkClient {
    public ZkACLProvider getZkACLProvider () {
      return createZkACLProvider();
    }

    public ZkCredentialsProvider getZkCredentialsProvider() {
      return createZkCredentialsToAddAutomatically();
    }
  }

  protected CuratorFramework getCuratorClient(int connectionTimeoutMs,
      int sessionTimeoutMs) throws ServletException {
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    String zkChroot = getZkChroot();
    String zkNamespace = zkChroot.startsWith("/") ? zkChroot.substring(1) : zkChroot;
    String zkConnectionString = zkHost != null ? zkHost.substring(0, zkHost.indexOf("/"))  : "localhost:2181";
    SolrZkToCuratorCredentialsACLs curatorToSolrZk = new SolrZkToCuratorCredentialsACLs();

    boolean useSASL = System.getProperty("java.security.auth.login.config") != null;
    if (useSASL) {
      LOG.info("Connecting to ZooKeeper with SASL/Kerberos");
      HttpClientUtil.createClient(null); // will set jaas configuration if proper parameters set.
    } else {
      LOG.info("Connecting to ZooKeeper without authentication");
    }
    curatorFramework = CuratorFrameworkFactory.builder()
      .namespace(zkNamespace)
      .connectString(zkConnectionString)
      .retryPolicy(retryPolicy)
      .aclProvider(curatorToSolrZk.getACLProvider())
      .authorization(curatorToSolrZk.getAuthInfos())
      .sessionTimeoutMs(sessionTimeoutMs)
      .connectionTimeoutMs(connectionTimeoutMs)
      .build();
    curatorFramework.start();
    return curatorFramework;
  }

  /**
   * Returns the System properties to be used by the authentication filter.
   * <p/>
   * All properties from the System properties with names that starts with {@link #SOLR_PREFIX} will
   * be returned. The keys of the returned properties are trimmed from the {@link #SOLR_PREFIX}
   * prefix, for example the property name 'solr.authentication.type' will
   * be just 'type'.
   *
   * @param configPrefix configuration prefix, this parameter is ignored by this implementation.
   * @param filterConfig filter configuration, this parameter is ignored by this implementation.
   * @return all System properties prefixed with {@link #SOLR_PREFIX}, without the
   * prefix.
   */
  @Override
  protected Properties getConfiguration(String configPrefix, FilterConfig filterConfig) {
    Properties props = new Properties();

    //setting the cookie path to root '/' so it is used for all resources.
    props.setProperty(AuthenticationFilter.COOKIE_PATH, "/");

    for (String name : System.getProperties().stringPropertyNames()) {
      if (name.startsWith(SOLR_PREFIX)) {
        String value = System.getProperty(name);
        name = name.substring(SOLR_PREFIX.length());
        props.setProperty(name, value);
      }
    }

    if (isZkEnabled()) {
      setDefaultDelegationTokenProp(props, "token.validity", "36000");
      setDefaultDelegationTokenProp(props, "signer.secret.provider", "zookeeper");
      setDefaultDelegationTokenProp(props, "zk-dt-secret-manager.enable", "true");

      String chrootPath = getZkChroot();
      //Note - Curator complains if the znodeWorkingPath starts with /
      String relativePath = chrootPath.startsWith("/") ? chrootPath.substring(1) : chrootPath;
      setDefaultDelegationTokenProp(props,
        "zk-dt-secret-manager.znodeWorkingPath", relativePath + "/zkdtsm");
      setDefaultDelegationTokenProp(props,
        "signer.secret.provider.zookeeper.path",  "/token");

    } else {
      LOG.info("zkHost is null, not setting ZK-related delegation token properties");
    }

    // Ensure we use the DelegationToken-supported versions
    // of the authentication handlers and that old behavior
    // (simple authentication) is preserved if properties not specified
    String authType = props.getProperty(AUTH_TYPE);
    if (authType == null) {
      props.setProperty(AUTH_TYPE, PseudoDelegationTokenAuthenticationHandler.class.getName());
      if (props.getProperty(PseudoAuthenticationHandler.ANONYMOUS_ALLOWED) == null) {
        props.setProperty(PseudoAuthenticationHandler.ANONYMOUS_ALLOWED, "true");
      }
    } else if (authType.equals(PseudoAuthenticationHandler.TYPE)) {
      props.setProperty(AUTH_TYPE,
        PseudoDelegationTokenAuthenticationHandler.class.getName());
    } else if (authType.equals(KerberosAuthenticationHandler.TYPE)) {
      props.setProperty(AUTH_TYPE,
        KerberosDelegationTokenAuthenticationHandler.class.getName());
    }

    props.setProperty(DelegationTokenAuthenticationHandler.TOKEN_KIND, TOKEN_KIND);

    return props;
  }

  /**
   * Enforces authentication using Hadoop-auth AuthenticationFilter.
   *
   * @param request http request.
   * @param response http response.
   * @param filterChain filter chain.
   * @throws IOException thrown if an IO error occurs.
   * @throws ServletException thrown if a servlet error occurs.
   */
  @Override
  public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain filterChain)
      throws IOException, ServletException {

    FilterChain filterChainWrapper = new FilterChain() {
      @Override
      public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse)
          throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        if (httpRequest.getMethod().equals("OPTIONS")) {
          optionsServlet.service(request, response);
        }
        else {
          httpRequest.setAttribute(USER_NAME, httpRequest.getRemoteUser());
          UserGroupInformation ugi = HttpUserGroupInformation.get();
          if (ugi != null && ugi.getAuthenticationMethod() == AuthenticationMethod.PROXY) {
            UserGroupInformation realUserUgi = ugi.getRealUser();
            if (realUserUgi != null) {
              httpRequest.setAttribute(DO_AS_USER_NAME, realUserUgi.getShortUserName());
            }
          }
          filterChain.doFilter(servletRequest, servletResponse);
        }
      }
    };

    super.doFilter(request, response, filterChainWrapper);
  }

  /**
   * This method is overridden to incorporate {@link MultiSchemeAuthenticationHandler} in Solr.
   * With {@link MultiSchemeAuthenticationHandler} the value of the 'type' attribute in the
   * {@link AuthenticationToken} does not match the {@linkplain MultiSchemeAuthenticationHandler#TYPE}
   * (as expected by the super-class implementation). Instead this value would match the actual type
   * of authentication mechanism being used (e.g. {@linkplain LdapAuthenticationHandler#TYPE} or
   * {@linkplain KerberosAuthenticationHandler#TYPE}.
   *
   * This implementation extracts the value(s) of actual authentication mechanisms configured (e.g. ldap or
   * kerberos) and use them to match the token type. It requires a private variable declared in the
   * superclass - signer. This variable is extracted using Java reflection mechanism during the startup.
   */
  @Override
  protected AuthenticationToken getToken(HttpServletRequest request)
      throws IOException, AuthenticationException {
    AuthenticationToken token = null;
    String tokenStr = null;
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (cookie.getName().equals(AuthenticatedURL.AUTH_COOKIE)) {
          tokenStr = cookie.getValue();
          try {
            tokenStr = signerCopy.verifyAndExtract(tokenStr);
          } catch (SignerException ex) {
            throw new AuthenticationException(ex);
          }
          break;
        }
      }
    }
    if (tokenStr != null) {
      token = AuthenticationToken.parse(tokenStr);
      Collection<String> tokenTypes = AuthenticationHandlerUtil.getTypes(getAuthenticationHandler());
      boolean match = false;
      for (String tokenType : tokenTypes) {
        if (tokenType.equalsIgnoreCase(token.getType())) {
          match = true;
          break;
        }
      }
      if (!match) {
        throw new AuthenticationException("Invalid AuthenticationToken type " + token.getType()
                  + " : expected one of " + tokenTypes);
      }
      if (token.isExpired()) {
        throw new AuthenticationException("AuthenticationToken expired");
      }
    }
    return token;

  }

  private Configuration getConf() {
    Configuration conf = new Configuration();
    String confDir = System.getProperty(HdfsDirectoryFactory.CONFIG_DIRECTORY);
    HdfsUtil.addHdfsResources(conf, confDir);
    return conf;
  }

  private boolean isZkEnabled() {
    return null != zkHost;
  }
}
