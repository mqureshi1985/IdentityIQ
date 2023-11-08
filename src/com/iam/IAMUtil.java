package com.iam;
 
import java.sql.Connection;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
 
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
 
import openconnector.JsonUtil;
import sailpoint.api.Aggregator;
import sailpoint.api.Identitizer;
import sailpoint.api.IdentityService;
import sailpoint.api.Provisioner;
import sailpoint.api.SailPointContext;
import sailpoint.api.SailPointFactory;
import sailpoint.connector.Connector;
import sailpoint.connector.ConnectorException;
import sailpoint.connector.ConnectorFactory;
import sailpoint.connectorDependencies.WebServicesClient;
import sailpoint.object.Application;
import sailpoint.object.Attributes;
import sailpoint.object.Custom;
import sailpoint.object.EmailOptions;
import sailpoint.object.EmailTemplate;
import sailpoint.object.Field;
import sailpoint.object.Filter;
import sailpoint.object.Identity;
import sailpoint.object.Link;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;
import sailpoint.object.QueryOptions;
import sailpoint.object.ResourceObject;
import sailpoint.object.Rule;
import sailpoint.object.Schema;
import sailpoint.object.TaskResult;
import sailpoint.object.Template;
import sailpoint.tools.GeneralException;
import sailpoint.tools.JdbcUtil;
import sailpoint.tools.Util;
import sailpoint.tools.xml.XMLReferenceResolver;
 
@SuppressWarnings({ "unchecked", "rawtypes" })
public class IAMUtil {
 
  private static final long FILETIME_EPOCH_DIFF = 11644473600000L;
  private static final long FILETIME_ONE_MILLISECOND = 10 * 1000;
  private static Map<String, Object> cfConfig = new HashMap<String, Object>();
  private static Map<String, Object> cfAppConfig = new HashMap<String, Object>();
 
  public static long filetimeToMillis(final long filetime) {
      return (filetime / FILETIME_ONE_MILLISECOND) - FILETIME_EPOCH_DIFF;
  }
 
  public static long millisToFiletime(final long millis) {
      return (millis + FILETIME_EPOCH_DIFF) * FILETIME_ONE_MILLISECOND;
  }
 
  public static String getManagerName(SailPointContext context, Link link) throws GeneralException {
    if (link != null && link.getApplication().isAuthoritative()) {
      String supervisor = (String) link.getAttribute("SUPERVISOR_ID");
      if (Util.isNotNullOrEmpty(supervisor)) {
        return supervisor.trim();
      }
      supervisor = (String) link.getAttribute("Supervisor");
      if (Util.isNotNullOrEmpty(supervisor)) {
        Identity identity = getIdentityByAttribute(context, "displayName", supervisor.trim());
        if (identity != null) {
          return identity.getName();
        }
      }
    }
    return null;
  }
 
  public static String getIdentityLifecycleState(SailPointContext context, Link link) {
    if (link == null || !(link.getApplication().isAuthoritative())) {
      return null;
    }
    String joiningDate = (String) link.getAttribute("START_DATE");
    String leavingDate = (String) link.getAttribute("END_DATE");
    if (Util.isNullOrEmpty(joiningDate)) {
      return null;
    }
    try {
      LocalDate joiningTime = LocalDate.parse(joiningDate, DateTimeFormatter.ofPattern("dd-MMM-yyyy"));
      if (joiningTime.isAfter(LocalDate.now())) {
        return IAMConstants.PRE_HIRE;
      }
    } catch (Exception ex) {
      return null;
    }
 
    if (Util.isNotNullOrEmpty(leavingDate)) {
      try {
        LocalDate leavingTime = LocalDate.parse(leavingDate, DateTimeFormatter.ofPattern("dd-MMM-yyyy"));
        if (leavingTime.isBefore(LocalDate.now())) {
          return IAMConstants.INACTIVE;
        }
      } catch (Exception ex) {
      }
    }
    return IAMConstants.ACTIVE;
  }
 
  public static List<String> getEntitlementsList(ProvisioningPlan plan, String entitlementAttribute, String operation) throws GeneralException {
    List<String> groups = new ArrayList<String>();
    if (plan != null && Util.isNotNullOrEmpty(entitlementAttribute) && Util.isNotNullOrEmpty(operation)) {
      for (AccountRequest acctReq : Util.safeIterable(plan.getAccountRequests())) {
        for (AttributeRequest attrReq : Util.safeIterable(acctReq.getAttributeRequests(entitlementAttribute))) {
          if (operation.equalsIgnoreCase(attrReq.getOperation().toString())) {
            if (attrReq.getValue() instanceof List) {
              groups.addAll((List) attrReq.getValue());
            }
            groups.add((String) attrReq.getValue());
          }
        }
      }
    }
    return groups;
  }
 
  public static ProvisioningPlan convertEntitlementAttributeRequestToList(ProvisioningPlan plan, String entitlementAttribute) throws GeneralException {
    if (plan != null) {
      for (AccountRequest acctReq : Util.safeIterable(plan.getAccountRequests())) {
        for (AttributeRequest attrReq : Util.safeIterable(acctReq.getAttributeRequests(entitlementAttribute))) {
          if (attrReq.getValue() instanceof String) {
            List<String> attrRequestValue = new ArrayList<String>();
            attrRequestValue.add((String) attrReq.getValue());
            attrReq.setValue(attrRequestValue);
          }
        }
      }
    }
    return plan;
  }
 
  public String getIdentityAttribute(SailPointContext context, String identityName, String attribute) {
    if (Util.isNotNullOrEmpty(identityName)) {
      try {
        Identity identity = context.getObjectByName(Identity.class, identityName);
        if (identity != null) {
          String value = identity.getStringAttribute(attribute);
          context.decache(identity);
          identity = null;
          return value;
        }
      } catch (GeneralException ex) {
      }
    }
    return null;
  }
 
  public Attributes getIdentityModel(SailPointContext context, Identity identity, Attributes identityModel) throws GeneralException {
    identityModel.putAll(identity.getAttributes());
 
    List<Link> links = identity.getLinks();
    Map linkMap = new HashMap();
    for (Link link : links) {
      linkMap.put(link.getApplicationName(), link.getAttributes().getMap());
    }
    identityModel.put("links", linkMap);
    Identity manager = identity.getManager();
    if (manager != null) {
      identityModel.put("manager", manager.getAttributes().getMap());
    }
    return identityModel;
  }
 
  public void removeLink(SailPointContext context, String identityName, String applicationName, String nativeIdentity) throws GeneralException {
    ProvisioningPlan plan = new ProvisioningPlan();
    Identity identity = context.getObjectByName(Identity.class, identityName);
    if (null != identity) {
      plan.setIdentity(identity);
      AccountRequest accReq = new AccountRequest();
      accReq.setOperation(AccountRequest.Operation.Delete);
      accReq.setNativeIdentity(nativeIdentity);
      accReq.setApplication(applicationName);
      plan.add(accReq);
      Provisioner p = new Provisioner(context);
      p.setLocalUpdate(true);
      p.execute(plan);
    }
  }
 
  public static String getLinkNativeIdentity(SailPointContext context, String appName, String identityName) throws GeneralException {
    Identity identity = context.getObjectByName(Identity.class, identityName);
    List<Link> links = identity.getLinks();
    for (Link link : links) {
      if (link.getApplicationName().equalsIgnoreCase(appName)) {
        return link.getNativeIdentity();
      }
    }
    return null;
  }
 
  public static String getNativeIdentity(SailPointContext context, String appName, Identity identity) throws GeneralException {
    String nativeId = "";
 
    Application app = context.getObjectByName(Application.class, appName);
 
    Schema schema = app.getAccountSchema();
    String niField = schema.getIdentityAttribute();
 
    List<Template> templates = app.getTemplates();
    Template updateTemp = null;
 
    if (templates != null && templates.size() > 0) {
      for (Template temp : templates) {
        Template.Usage usage = temp.getUsage();
        if (usage.equals(Template.Usage.Create)) {
          updateTemp = temp;
          break;
        }
      }
 
      if (updateTemp != null) {
        List<Field> fields = updateTemp.getFields();
        if (fields != null && fields.size() > 0) {
          for (Field field : fields) {
            String fieldName = field.getName();
 
            if (niField.compareTo(fieldName) == 0) {
              Rule rule = field.getFieldRule();
 
              HashMap<String, Object> params = new HashMap<String, Object>();
 
              params.put("context", context);
              params.put("identity", identity);
              params.put("field", field);
 
              try {
                nativeId = (String) context.runRule(rule, params);
              } catch (Exception re) {
                continue;
              }
            }
          }
        }
      }
    }
    return nativeId;
  }
 
  public static Identity getIdentityByAttribute(SailPointContext context, String attName, String attValue) throws GeneralException {
    Identity identity = null;
    if (Util.isNotNullOrEmpty(attName) && Util.isNotNullOrEmpty(attValue)) {
      QueryOptions options = new QueryOptions();
      Filter idFilter = Filter.ignoreCase(Filter.eq(attName.trim(), attValue.trim()));
      options.addFilter(idFilter);
      Iterator<Identity> iter = context.search(Identity.class, options);
      while (iter != null && iter.hasNext()) {
        identity = iter.next();
        break;
      }
    }
    return identity;
  }
 
  public static String getIdentityLinkAttribute(SailPointContext context, Identity identity, String appName, String attribute) throws GeneralException {
    IdentityService is = new IdentityService(context);
    Application app = context.getObject(Application.class, appName);
    List<Link> links = is.getLinks(identity, app);
    if (links == null || links.isEmpty()) {
      return "";
    }
    Link link = (Link) links.get(0);
    String attrValue = (String) link.getAttribute(attribute);
    if (Util.isNullOrEmpty(attrValue)) {
      return "";
    }
    return attrValue;
  }
 
  public static boolean isNewLinkChanged(SailPointContext context, Identity previousIdentity, Identity newIdentity, Custom mappingObj) throws GeneralException {
    boolean flag = false;
 
    List<String> checkLinks = (List) mappingObj.get("Trigger Compare Links");
    Attributes linkSchemas = (Attributes) mappingObj.get("Trigger Compare Links Schemas");
    IdentityService is = new IdentityService(context);
    Application app;
 
    if (checkLinks == null || checkLinks.isEmpty()) {
      return false;
    }
 
    for (String checkLink : checkLinks) {
      app = context.getObject(Application.class, checkLink);
 
      List<Link> prevLinks = is.getLinks(previousIdentity, app);
      List<Link> newLinks = is.getLinks(newIdentity, app);
 
      if (prevLinks == null || prevLinks.isEmpty() || newLinks == null || newLinks.isEmpty()) {
        continue;
      }
 
      Link prevLink = prevLinks.get(0);
      Link newLink = newLinks.get(0);
 
      List<String> linkAttrs = linkSchemas.getList(checkLink);
 
      if (linkAttrs == null || linkAttrs.isEmpty()) {
        continue;
      }
 
      for (String attrName : linkAttrs) {
 
        Object prevVal = prevLink.getAttribute(attrName);
        Object newVal = newLink.getAttribute(attrName);
 
        flag = isFieldValueUpdated(prevVal, newVal);
 
        if (flag) {
          break;
        }
      }
      if (flag) {
        break;
      }
 
    }
    return flag;
  }
 
  public static boolean isFieldValueUpdated(Object oldVal, Object val) {
    boolean isUpdate = false;
    if (val != null && oldVal != null && val instanceof List && oldVal instanceof List) {
 
      if (!((List) val).containsAll((List) oldVal) || !((List) oldVal).containsAll((List) val)) {
        isUpdate = true;
      }
    } else {
      if (val != null && !(val + "").trim().equals("") && (oldVal == null || !val.equals(oldVal))) {
        isUpdate = true;
      } else if ((val == null || val == "") && oldVal != null) {
        isUpdate = true;
      }
    }
    if ((val instanceof List && ((List) val).isEmpty() && oldVal == null)) {
      isUpdate = false;
    }
    return isUpdate;
  }
 
  public static Identity getIdentityByADAccountName(SailPointContext context, String acctName) throws GeneralException {
    acctName = acctName.trim();
    QueryOptions qo = new QueryOptions();
    Filter myFilter = Filter.and(Filter.eq("application.name", IAMConstants.AD), Filter.ignoreCase(Filter.eq("sAMAccountName", acctName)));
    qo.addFilter(myFilter);
    Iterator<Object[]> iter = context.search(Link.class, qo, "id");
    Identity foundIdentity = null;
    while (iter.hasNext()) {
      Object[] row = iter.next();
      String id = (String) row[0];
      foundIdentity = context.getUniqueObject(Identity.class, Filter.eq("links.id", id));
      break;
    }
    return foundIdentity;
  }
 
  public static Connection getApplicationDBConnection(SailPointContext context, String appName) throws GeneralException {
    Application app = context.getObject(Application.class, appName);
    if (null != app) {
      String driverClass = (String) app.getAttributeValue("driverClass");
      String jdbcUrl = (String) app.getAttributeValue("url");
      String jdbcUser = (String) app.getAttributeValue("user");
      String jdbcPassword = (String) app.getAttributeValue("password");
      if ((null == driverClass) || (null == jdbcUrl) || (null == jdbcUser) || (null == jdbcPassword)) {
        throw (new GeneralException("Incomplete connection information. Required JDBC authentication parameter is missing."));
      }
      Map<String, String> jdbcMap = new HashMap<String, String>();
      jdbcMap.put("driverClass", driverClass);
      jdbcMap.put("url", jdbcUrl);
      jdbcMap.put("user", jdbcUser);
      jdbcMap.put("password", context.decrypt(jdbcPassword));
      return JdbcUtil.getConnection(jdbcMap);
    }
    return null;
  }
 
  private static boolean isAliasAvailable(SailPointContext context, String alias) throws GeneralException {
    int count = 0;
    // Check in IIQ.
    Filter samNameFilter = Filter.or(Filter.eq("sAMAccountName", alias), Filter.ignoreCase(Filter.like("email", alias, Filter.MatchMode.START)));
    count = context.countObjects(Identity.class, new QueryOptions().addFilter(samNameFilter));
    if (count > 0) {
      return false;
    }
    return true;
  }
 
  public static String generateEmailAlias(SailPointContext context, Identity identity) throws GeneralException {
    String alias = "";
    if (identity == null) {
      return "";// alias
    }
    String firstName = identity.getFirstname();
    String lastName = identity.getLastname();
    if (lastName.contains(" ")) {
      lastName = lastName.substring(lastName.lastIndexOf(" "), lastName.length()).trim();
    }
    lastName = lastName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim();
    firstName = firstName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim();
 
    for (int i = 0; i < firstName.length(); i++) {
      if (Character.isWhitespace(firstName.charAt(i))) {
        continue;
      }
      alias = alias + firstName.charAt(i);
      if (isAliasAvailable(context, alias + lastName)) {
        alias = alias + lastName;
        break;
      }
    }
    return alias;
  }
 
  public static String aggregateAccount(SailPointContext context, String accountName, String applicationName) throws GeneralException, ConnectorException {
    Application appObject = context.getObjectByName(Application.class, applicationName);
    String appConnName = appObject.getConnector();
    Connector appConnector = sailpoint.connector.ConnectorFactory.getConnector(appObject, null);
    if (null == appConnector) {
      throw new GeneralException("Failed to construct an instance of connector [" + appConnName + "]");
    }
 
    ResourceObject rObj = null;
    try {
      rObj = (ResourceObject) appConnector.getObject("account", accountName, null);
    } catch (sailpoint.connector.ObjectNotFoundException onfe) {
      throw new GeneralException("Connector could not find account: [" + accountName + "] in application  [" + applicationName + "]");
    }
 
    if (null == rObj) {
      throw new GeneralException("ERROR: Could not get ResourceObject for account: " + accountName);
    }
 
    Rule customizationRule = appObject.getCustomizationRule();
    if (null != customizationRule) {
      try {
        HashMap ruleArgs = new HashMap();
        ruleArgs.put("context", context);
        ruleArgs.put("object", rObj);
        ruleArgs.put("application", appObject);
        ruleArgs.put("connector", appConnector);
        ruleArgs.put("state", new HashMap());
 
        ResourceObject newRObj = (ResourceObject) context.runRule(customizationRule, ruleArgs, null);
        if (null != newRObj) {
          rObj = newRObj;
        }
      } catch (Exception ex) {
      }
    }
 
    Attributes argMap = new Attributes();
    argMap.put("promoteAttributes", "true");
    argMap.put("correlateEntitlements", "true");
    argMap.put("noOptimizeReaggregation", "true");
    argMap.put("checkDeleted", "true");
 
    Aggregator agg = new Aggregator(context, argMap);
    TaskResult taskResult = agg.aggregate(appObject, rObj);
    if (null == taskResult) {
      throw new GeneralException("ERROR: Null taskResult returned from aggregate() call.");
    }
    System.out.println(taskResult.toXml());
    return "Success";
  }
 
  public static void refreshIdentity(SailPointContext context, String identityName) throws GeneralException {
    Identity identity = context.getObject(Identity.class, identityName);
    if (identity != null) {
      Map map = new HashMap();
      map.put("promoteAttributes", true);
      map.put("refreshLinks", true);
 
      Attributes args = new Attributes();
      args.setMap(map);
      Identitizer idtz = new Identitizer(context, args);
      idtz.refresh(identity);
      context.saveObject(identity);
      context.commitTransaction();
    }
  }
 
  public static Object getConfig(String key) throws GeneralException {
    Object config = cfConfig.get(key);
    if (config != null) {
      return config;
    }
    SailPointContext context = SailPointFactory.getCurrentContext();
    if (context == null) {
      throw new GeneralException("Unable to get sailpoint context.");
    }
    Custom mappingObj = getConfigMappingObject(context);
    Map configAttributes = (Map) mappingObj.getAttributes().get("Config");
    cfConfig.putAll(configAttributes);
    return cfConfig.get(key);
  }
 
  public static String testConnection(SailPointContext context, String appName) throws GeneralException {
    Application app = (Application) context.getObjectById(Application.class, appName);
    if (app != null) {
      try {
        Connector connector = ConnectorFactory.getConnector(app, null);
        connector.testConfiguration();
      } catch (Exception e) {
        return e.getMessage();
      }
    }
    return "Success";
  }
 
  // Method to send an email
  public static boolean sendNotificationEmail(SailPointContext context, Map senderMap, String tplName, String emailDest) {
    boolean flag = false;
    try {
      EmailTemplate template = context.getObjectByName(EmailTemplate.class, tplName);
      if (null == template) {
        return flag;
      }
      template = (EmailTemplate) template.deepCopy((XMLReferenceResolver) context);
      if (null == template) {
        return flag;
      }
 
      if (Util.isNullOrEmpty(emailDest)) {
        emailDest = "iamadmin@gib.com";
      }
      List<String> toAddr = Util.csvToList(emailDest);
      EmailOptions ops = new EmailOptions();
      ops.setTo(toAddr);
      for (Object varName : senderMap.keySet()) {
        ops.setVariable(varName.toString(), (String) senderMap.get(varName));
      }
      IAMLogger.debug(IAMLogger.JOINER, "Going to Send Email!");
      context.sendEmailNotification(template, ops);
      IAMLogger.debug(IAMLogger.JOINER, "Email Sent!");
    } catch (Exception e) {
      IAMLogger.error(IAMLogger.SAILPOINT, e.getMessage());
      return flag;
    }
    flag = true;
    return flag;
  }
 
  private static Hashtable<String, String> getADConnectionDetails(SailPointContext context) throws GeneralException {
    Application adApp = (Application) context.getObjectByName(Application.class, "Active Directory");
    List<Map> domainSettings = (List) adApp.getAttributeValue("domainSettings");
    Map domainSetting = null;
    String organizationalUnit = null;
    for (Map map : domainSettings) {
      String domainDN = (String) map.get("domainDN");
      organizationalUnit = domainDN;
      domainSetting = map;
      break;
    }
 
    String server = (String) ((List) domainSetting.get("servers")).get(0);
    String port = (String) domainSetting.get("port");
    String username = (String) domainSetting.get("user");
    String password = (String) domainSetting.get("password");
    password = context.decrypt(password);
 
    Hashtable<String, String> env = new Hashtable<String, String>();
    env.put(Context.SECURITY_AUTHENTICATION, "simple");
    env.put(Context.SECURITY_PRINCIPAL, username);
    env.put(Context.SECURITY_CREDENTIALS, password);
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
    env.put(Context.PROVIDER_URL, "LDAP://" + server + ":" + port);
    env.put("organizationalUnit", organizationalUnit);
    return env;
  }
 
  public static boolean checkIfOUExits(SailPointContext context, String ouToSeek) throws GeneralException {
    Hashtable<String, String> env = getADConnectionDetails(context);
    String organizationalUnit = env.get("organizationalUnit");
    env.remove("organizationalUnit");
    try {
      LdapContext ctx = new InitialLdapContext(env, null);
      String searchFilter = "(&(objectCategory=organizationalunit)(distinguishedName=" + ouToSeek + "))";
      SearchControls searchControls = new SearchControls();
      searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
      String[] returnAttributes = { "distinguishedName" };
      searchControls.setReturningAttributes(returnAttributes);
 
      NamingEnumeration<SearchResult> results = ctx.search(organizationalUnit, searchFilter, searchControls);
      while (results.hasMoreElements()) {
        return true;
      }
    } catch (javax.naming.NamingException ne) {
    }
    return false;
  }
 
  public static Map<String, String> findUserInADByAccountName(SailPointContext context, String accountName) throws GeneralException {
    Map<String, String> user = new HashMap<String, String>();
    Hashtable<String, String> env = getADConnectionDetails(context);
    String organizationalUnit = env.get("organizationalUnit");
    env.remove("organizationalUnit");
    try {
      LdapContext ctx = new InitialLdapContext(env, null);
 
      String searchFilter = "(&(objectClass=user)(sAMAccountName=" + accountName + "))";
 
      SearchControls searchControls = new SearchControls();
      searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
      String[] returnAttributes = { "sAMAccountName", "distinguishedName", "mail", "userAccountControl" };
      searchControls.setReturningAttributes(returnAttributes);
 
      NamingEnumeration<SearchResult> results = ctx.search(organizationalUnit, searchFilter, searchControls);
 
      SearchResult searchResult = null;
      while (results.hasMoreElements()) {
        searchResult = (SearchResult) results.nextElement();
        javax.naming.directory.Attribute sAMAttribute = searchResult.getAttributes().get("sAMAccountName");
        if (sAMAttribute != null) {
          String sAMAccountName = (String) sAMAttribute.get();
          if (sAMAccountName.equalsIgnoreCase(accountName)) {
            user.put("sAMAccountName", sAMAccountName);
            javax.naming.directory.Attribute dn = searchResult.getAttributes().get("distinguishedname");
            javax.naming.directory.Attribute email = searchResult.getAttributes().get("mail");
            javax.naming.directory.Attribute userAccountControl = searchResult.getAttributes().get("userAccountControl");
            if (dn != null) {
              user.put("dn", (String) dn.get());
            }
            if (email != null) {
              user.put("email", (String) email.get());
            }
            if (userAccountControl != null) {
              user.put("isDisabled", "false");
              if ("514".equals(userAccountControl.get())) {
                user.put("isDisabled", "true");
              }
            }
            break;
          }
        }
        continue;
      }
    } catch (javax.naming.NamingException ne) {
    }
    return user;
  }
 
  public static Object getAppConfig(String appName, String key) throws GeneralException {
    Object appConfig = cfAppConfig.get(appName);
    if (appConfig != null) {
      return ((Map) appConfig).get(key);
    }
    SailPointContext context = SailPointFactory.getCurrentContext();
    if (context == null) {
      throw new GeneralException("Unable to get sailpoint context.");
    }
    Custom mappingObj = getConfigMappingObject(context);
    Map appConfigAttributes = (Map) mappingObj.getAttributes().get(appName);
    if (appConfigAttributes != null) {
      cfAppConfig.put(appName, appConfigAttributes);
    }
    return getAppConfig(appName, key);
  }
 
  public static Custom getCustomObject(SailPointContext context, String name) throws GeneralException {
    Custom mappingObj = context.getObjectByName(Custom.class, name);
    return mappingObj;
  }
 
  public static Custom getConfigMappingObject(SailPointContext context) throws GeneralException {
    Custom mappingObj = context.getObjectByName(Custom.class, "GIB Config Mappings");
    return mappingObj;
  }
 
  public static void reloadCache(SailPointContext context) throws GeneralException {
    cfConfig.clear();
    cfAppConfig.clear();
  }
}