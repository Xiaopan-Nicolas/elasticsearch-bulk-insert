package org.pentaho.di.trans.steps.elasticsearchbulk;

import com.huawei.fusioninsight.elasticsearch.transport.common.Configuration;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.transport.TransportAddress;

/**
 * @Author: cunxiaopan
 * @Date: 2019/8/23 4:18 PM
 * @Description:
 */
public class LoadProperties {

  private static final Logger LOG = LogManager.getLogger(LoadProperties.class);
  private static String ipPattern = "((2[0-4]\\d|25[0-5]|[01]?\\d\\d?)\\.){3}(2[0-4]\\d|25[0-5]|[01]?\\d\\d?)";
  private static Properties properties = new Properties();
  public static String keytabPath;

  private static final String ES_FLAG = "es.";
  /**
   * jaas file postfix
   */
  private static final String JAAS_POSTFIX = ".jaas.conf";

  /**
   * IBM jdk login module
   */
  private static final String IBM_LOGIN_MODULE = "com.ibm.security.auth.module.Krb5LoginModule required";

  /**
   * oracle jdk login module
   */
  private static final String SUN_LOGIN_MODULE = "com.sun.security.auth.module.Krb5LoginModule required";

  /**
   * java security login file path
   */
  public static final String JAVA_SECURITY_LOGIN_CONF_KEY = "java.security.auth.login.config";

  private static final String JAVA_SECURITY_KRB5_CONF_KEY = "java.security.krb5.conf";

  /**
   * line operator string
   */
  private static final String LINE_SEPARATOR = System.getProperty("line.separator");

  private static final boolean IS_IBM_JDK = System.getProperty("java.vendor").contains("IBM");

  private static boolean WriteFlag = false;

  public enum Module {
    STORM("StormClient"), KAFKA("KafkaClient"), Elasticsearch("EsClient"), ZOOKEEPER("Client");
    private String name;

    private Module(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }

  public static Configuration loadProperties() throws IOException {
    initProperties();
    Configuration configuration = new Configuration();
    configuration.setClusterName(loadClusterName());
    configuration.setTransportAddress(loadTransportAddress());
    configuration.setSecureMode(loadIsSecureMode());
    if (configuration.isSecureMode()) {
      configuration.setPrincipal(loadPrincipal());
      String configPath = System.getProperty("user.dir") + File.separator + "conf" + File.separator ;
      configuration.setKeyTabPath(configPath);
      configuration.setKrb5Path(configPath);
      keytabPath = configPath;
      setSecurityConfig(configuration.getPrincipal(), keytabPath, configuration.getKrb5Path());
    }
    configuration.setSniff(loadIsSniff());
    LOG.info("configuration:" + configuration);
    return configuration;
  }

  private static void initProperties() {
    try {
      String configPath = System.getProperty("user.dir") + File.separator + "conf" + File.separator + "es-example.properties";
      properties.load(new FileInputStream(new File(configPath)));
    } catch (Exception e) {
      LOG.error("Failed to load properties file ${user.dir}/conf/es-example.properties.");
      throw new IllegalArgumentException();
    }
  }

  public static String loadClusterName() {
    String clusterName = properties.getProperty("cluster.name");
    if (null == clusterName || clusterName.isEmpty()) {
      LOG.error("clusterName is empty, please configure it in ${user.dir}/conf/es-example.properties.");
      throw new IllegalArgumentException();
    }
    return clusterName;
  }

  private static Set<TransportAddress> loadTransportAddress() {
    String serverHosts = properties.getProperty("esServerHosts");
    if (null == serverHosts || serverHosts.isEmpty()) {
      LOG.error("Please configure esServerHosts in ${user.dir}/conf/es-example.properties.");
      LOG.error("The format of esServerHosts is ip1:port1,ip2:port2,ipn,portn");
      throw new IllegalArgumentException();
    }
    String[] hosts = serverHosts.split(",");
    Set<TransportAddress> transportAddresses = new HashSet<>(hosts.length);
    for (String host : hosts) {
      String[] ipAndPort = host.split(":");
      String esClientIP = ipAndPort[0];
      String esClientPort = ipAndPort[1];
      if (!Pattern.matches(ipPattern, esClientIP)) {
        LOG.error("clientIP format is wrong, please configure it in ${user.dir}/conf/es-example.properties");
        throw new IllegalArgumentException();
      }
      if (null == esClientPort || esClientPort.isEmpty()) {
        LOG.error("esClientIPPort is empty, please configure it in ${user.dir}/conf/es-example.properties");
        throw new IllegalArgumentException();
      }
      if (new Integer(esClientPort) % 2 == 0) {
        LOG.warn("esClientIPPort may be wrong, please check it in ${user.dir}/conf/es-example.properties");
      }
      try {
        transportAddresses.add(new TransportAddress(InetAddress.getByName(esClientIP), Integer.valueOf(esClientPort)));
      } catch (Exception e) {
        LOG.error("init esServerHosts occur error : " + e.getCause());
        throw new IllegalArgumentException();
      }
    }
    return transportAddresses;
  }

  private static String loadPath(String path) {
    String loadedPath = properties.getProperty(path);
    if (null == loadedPath || loadedPath.isEmpty()) {
      loadedPath = System.getProperty("user.dir") + File.separator + "conf" + File.separator;
      LOG.warn(path + " is empty, using the default path.");
    }
    return loadedPath;
  }

  private static boolean loadIsSecureMode() {
    return !properties.getProperty("isSecureMode").equals("false");
  }

  private static boolean loadIsSniff() {
    return !properties.getProperty("isSniff").equals("false");
  }

  private static String loadPrincipal() {
    String principal = properties.getProperty("principal");
    if (null == principal || principal.isEmpty()) {
      LOG.error("Please configure principal in ${user.dir}/conf/es-example.properties.");
      throw new IllegalArgumentException();
    }
    return principal;
  }

  public static void setSecurityConfig(String principal, String keytabPath, String krb5Path) throws IOException {
    // jaas.conf
    if(null == principal || null == keytabPath){
      LOG.error("Please check your principal or keytabPath.");
    }
    String configPath = System.getProperty("user.dir") + File.separator + "conf" + File.separator;
    String jaasPath = configPath +  ES_FLAG + "user" + JAAS_POSTFIX;
    // windows路径下分隔符替换
    jaasPath = jaasPath.replace("\\", "\\\\");
    keytabPath = keytabPath.replace("\\", "\\\\");
    // 删除jaas文件
    if (new File(jaasPath).exists()) {
      if (WriteFlag == false) {
        deleteJaasFile(jaasPath);
        writeJaasFile(jaasPath, principal, keytabPath);
        System.setProperty(JAVA_SECURITY_LOGIN_CONF_KEY, jaasPath);
        WriteFlag = true;
      }
    } else {
      writeJaasFile(jaasPath, principal, keytabPath);
      System.setProperty(JAVA_SECURITY_LOGIN_CONF_KEY, jaasPath);
      WriteFlag = true;
    }
    String jaas = System.getProperty(JAVA_SECURITY_LOGIN_CONF_KEY);
    if (jaas == null) {
      LOG.error(JAVA_SECURITY_LOGIN_CONF_KEY + " is null.");
      throw new IOException(JAVA_SECURITY_LOGIN_CONF_KEY + " is null.");
    }

    System.setProperty("es.security.indication", "true");
    // krb5ConfFile
    String krb5ConfFile = configPath + "krb5.conf";
    System.setProperty(JAVA_SECURITY_KRB5_CONF_KEY, krb5ConfFile);
    String ret = System.getProperty(JAVA_SECURITY_KRB5_CONF_KEY);
    if (ret == null) {
      LOG.error(JAVA_SECURITY_KRB5_CONF_KEY + " is null.");
      throw new IOException(JAVA_SECURITY_KRB5_CONF_KEY + " is null.");
    }
    if (!ret.equals(krb5ConfFile)) {
      LOG.error(JAVA_SECURITY_KRB5_CONF_KEY + " is " + ret + " is not " + krb5ConfFile + ".");
      throw new IOException(JAVA_SECURITY_KRB5_CONF_KEY + " is " + ret + " is not " + krb5ConfFile + ".");
    }
  }

  private static void deleteJaasFile(String jaasPath) throws IOException {
    File jaasFile = new File(jaasPath);

    if (jaasFile.exists()) {
      if (!jaasFile.delete()) {
        throw new IOException("Failed to delete exists jaas file.");
      }
    }
  }

  private static void writeJaasFile(String jaasPath, String principal, String keytabPath) throws IOException {
    FileWriter writer = new FileWriter(new File(jaasPath));
    try {
      writer.write(getJaasConfContext(principal, keytabPath));
      writer.flush();
    } catch (IOException e) {
      throw new IOException("Failed to create jaas.conf File");
    } finally {
      writer.close();
    }
  }

  private static String getJaasConfContext(String principal, String keytabPath) {
    Module[] allModule = Module.values();
    StringBuilder builder = new StringBuilder();
    for (Module modlue : allModule) {
      builder.append(getModuleContext(principal, keytabPath, modlue));
    }
    return builder.toString();
  }

  private static String getModuleContext(String userPrincipal, String keyTabPath, Module module) {
    StringBuilder builder = new StringBuilder();
    if (IS_IBM_JDK) {
      builder.append(module.getName()).append(" {").append(LINE_SEPARATOR);
      builder.append(IBM_LOGIN_MODULE).append(LINE_SEPARATOR);
      builder.append("credsType=both").append(LINE_SEPARATOR);
      builder.append("principal=\"" + userPrincipal + "\"").append(LINE_SEPARATOR);
      builder.append("useKeytab=\"" + keyTabPath + "\"").append("user.keytab\"").append(LINE_SEPARATOR);
      builder.append("debug=true;").append(LINE_SEPARATOR);
      builder.append("};").append(LINE_SEPARATOR);
    } else {
      builder.append(module.getName()).append(" {").append(LINE_SEPARATOR);
      builder.append(SUN_LOGIN_MODULE).append(LINE_SEPARATOR);
      builder.append("useKeyTab=true").append(LINE_SEPARATOR);
      builder.append("keyTab=\"" + keyTabPath).append("user.keytab\"").append(LINE_SEPARATOR);
      builder.append("principal=\"" + userPrincipal + "\"").append(LINE_SEPARATOR);
      builder.append("useTicketCache=false").append(LINE_SEPARATOR);
      builder.append("storeKey=true").append(LINE_SEPARATOR);
      builder.append("debug=true;").append(LINE_SEPARATOR);
      builder.append("};").append(LINE_SEPARATOR);
    }

    return builder.toString();
  }
}
