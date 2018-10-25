package test.common;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import static java.util.Arrays.asList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.jms.ConnectionFactory;
import junit.framework.TestCase;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.persistence.impl.journal.OperationContextImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMRegistry;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptorFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.security.ActiveMQJAASSecurityManager;
import org.apache.activemq.artemis.utils.FileUtil;
import org.apache.activemq.artemis.utils.ReusableLatch;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import store.forward.StoreAndForwardConnectionFactory;

public class ArtemisTestSupport extends TestCase {

    static {
        String path = System.getProperty("java.security.auth.login.config");
        if (path == null) {
            URL resource = ArtemisTestSupport.class.getClassLoader().getResource("security.jaas");
            if (resource != null) {
                path = resource.getFile();
                System.setProperty("java.security.auth.login.config", path);
            }
        }
    }

    public static final String TARGET_TMP = "./target/tmp";
    public static final String STORE_LOCATION_PATH = TARGET_TMP +"/jms-store";

    public EmbeddedActiveMQ master;
    private Path masterBrokerXML;
    public EmbeddedActiveMQ slave;
    private Path slaveBrokerXML;

    public String masterPort;
    public String slavePort;
    private static final String LOCALHOST = "localhost";
    private String testDir;

    @Rule
    public TemporaryFolder temporaryFolder;

    @Rule
    // This Custom rule will remove any files under ./target/tmp
    // including anything created previously by TemporaryFolder
    public RemoveFolder folder = new RemoveFolder(TARGET_TMP);

    public EmbeddedActiveMQ getMasterBroker() {
        return master;
    }

    public EmbeddedActiveMQ getSlaveBroker() {
        return slave;
    }

    @Before
    @Override
    public void setUp() throws Exception {
        File parent = new File(TARGET_TMP);
        parent.mkdirs();
        temporaryFolder = new TemporaryFolder(parent);

        masterPort = "61616";
        slavePort = "61617";
        temporaryFolder.create();
        testDir = temporaryFolder.getRoot().getAbsolutePath();

        clearDataRecreateServerDirs();
        OperationContextImpl.clearContext();

        InVMRegistry.instance.clear();


        master = new EmbeddedActiveMQ();
        slave = new EmbeddedActiveMQ();


        // set these system properties to use in the relevant broker.xml files
        System.setProperty("master-data-dir", getTestDirfile().toPath() + "/master-data");
        System.setProperty("slave-data-dir", getTestDirfile().toPath() + "/slave-data");
        System.setProperty("master-port", masterPort);
        System.setProperty("slave-port", slavePort);

        Path masterBrokerXML = getTestDirfile().toPath().resolve("master.xml");
        Path slaveBrokerXML = getTestDirfile().toPath().resolve("slave.xml");
        URL masterOrginalXML = ArtemisTestSupport.class.getClassLoader().getResource("master-original.xml");
        URL slaveOrginalXML = ArtemisTestSupport.class.getClassLoader().getResource("slave-original.xml");
        Files.copy(masterOrginalXML.openStream(), masterBrokerXML);
        Files.copy(slaveOrginalXML.openStream(), slaveBrokerXML);

        master.setConfigResourcePath(masterBrokerXML.toUri().toString());
        ActiveMQJAASSecurityManager securityManager = new ActiveMQJAASSecurityManager("PropertiesLogin");
        master.setSecurityManager(securityManager);
        master.start();

        assertTrue(Wait.waitFor(() -> (master.getActiveMQServer().isActive()), 5000, 100));

        slave.setConfigResourcePath(slaveBrokerXML.toUri().toString());
        //ActiveMQJAASSecurityManager securityManager = new ActiveMQJAASSecurityManager("PropertiesLogin");
        slave.setSecurityManager(securityManager);
        slave.start();
        assertTrue(Wait.waitFor(() -> slave.getActiveMQServer().isReplicaSync(), 15000, 200));

        super.setUp();
    }

    /**
     * @return the testDir
     */
    protected final String getTestDir() {
        return testDir;
    }

    protected final File getTestDirfile() {
        return new File(testDir);
    }


    private Map<String, TransportConfiguration> connectorConfigurations(String masterPort, String slavePort) {
        Map<String, TransportConfiguration> configurationMap = new HashMap<>();
        configurationMap.put("master", connectorTransportConfiguration(masterPort));
        configurationMap.put("slave", connectorTransportConfiguration(slavePort));
        return configurationMap;
    }


    private HashSet<TransportConfiguration> getAcceptorTransportConfigurations(String port) {
        HashSet<TransportConfiguration> transports = new HashSet<>();
        transports.add(acceptorTransportConfiguration(port));
        return transports;
    }

    private TransportConfiguration connectorTransportConfiguration(String port) {
        Map<String, Object> connectionParams = new HashMap<String, Object>();
        connectionParams.put(TransportConstants.PORT_PROP_NAME, port);

        return new TransportConfiguration(NettyConnectorFactory.class.getName(), connectionParams);
    }

    private TransportConfiguration acceptorTransportConfiguration(String port) {
        Map<String, Object> connectionParams = new HashMap<String, Object>();
        connectionParams.put(TransportConstants.PORT_PROP_NAME, port);
        connectionParams.put(TransportConstants.PROTOCOLS_PROP_NAME, "CORE,AMQP,OPENWIRE");
        return new TransportConfiguration(NettyAcceptorFactory.class.getName(), connectionParams);
    }


    @After
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        try {
            master.stop();
        } finally {
            slave.stop();
        }
        System.clearProperty("master-port");
        System.clearProperty("slave-port");

        System.clearProperty("master-data-dir");
        System.clearProperty("slave-data-dir");
        folder.after();
        temporaryFolder.delete();
    }

    public ConnectionFactory createCoreConnectionFactory() {
        ActiveMQConnectionFactory activeMQConnectionFactory = new ActiveMQConnectionFactory("(tcp://" + LOCALHOST + ":"+ masterPort + "," + "tcp://" + LOCALHOST + ":"+ slavePort + ")?ha=true");
        return new TrackerConnectionFactory(activeMQConnectionFactory);
    }

    public ConnectionFactory createCoreConnectionFactory(String user, String password) {
        ActiveMQConnectionFactory activeMQConnectionFactory = new ActiveMQConnectionFactory("(tcp://" + LOCALHOST + ":"+ masterPort + "," + "tcp://" + LOCALHOST + ":"+ slavePort + ")?ha=true", user, password);
        return new TrackerConnectionFactory(activeMQConnectionFactory);
    }

    public ConnectionFactory createAMQPConnectionFactory(String user, String password) {
        JmsConnectionFactory jmsConnectionFactory = new JmsConnectionFactory( user, password, "failover:(amqp://"+LOCALHOST+":"+masterPort+",amqp://"+LOCALHOST+":"+slavePort+")");
        return new TrackerConnectionFactory(jmsConnectionFactory);
    }

    public ConnectionFactory createConnectionFactoryStoreAndForward(String username, String password, List<String> addresses) throws Exception {
        ActiveMQConnectionFactory activeMQConnectionFactory = new ActiveMQConnectionFactory("(tcp://" + LOCALHOST + ":"+ masterPort + "," + "tcp://" + LOCALHOST + ":"+ slavePort + ")?ha=true", username, password);
        StoreAndForwardConnectionFactory storeAndForwardConnectionFactory = new StoreAndForwardConnectionFactory(activeMQConnectionFactory, STORE_LOCATION_PATH, addresses);
        return new TrackerConnectionFactory(storeAndForwardConnectionFactory);
    }

    public static String getUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return Integer.toString(socket.getLocalPort());
        }
    }

    protected final void setTestDir(String testDir) {
        this.testDir = testDir;
    }

    protected final void clearDataRecreateServerDirs() {
        clearDataRecreateServerDirs(0, false);
    }

    protected final void clearDataRecreateServerDirs(int index, boolean slave) {
        clearDataRecreateServerDirs(getTestDir(), index, slave);
    }

    protected void clearDataRecreateServerDirs(final String testDir1, int index, boolean slave) {
        // Need to delete the root

        File file = new File(testDir1);
        deleteDirectory(file);
        file.mkdirs();

        recreateDataDirectories(testDir1, index, slave);
    }

    protected void recreateDataDirectories(String testDir1, int index, boolean slave) {
        recreateDirectory(getJournalDir(testDir1, index, slave));
        recreateDirectory(getBindingsDir(testDir1, index, slave));
        recreateDirectory(getPageDir(testDir1, index, slave));
        recreateDirectory(getLargeMessagesDir(testDir1, index, slave));
        recreateDirectory(getClientLargeMessagesDir(testDir1));
        recreateDirectory(getTemporaryDir(testDir1));
    }

    /**
     * @return the journalDir
     */
    public String getJournalDir() {
        return getJournalDir(0, false);
    }

    protected static String getJournalDir(final String testDir1) {
        return testDir1 + "/journal";
    }

    public String getJournalDir(final int index, final boolean slave) {
        return getJournalDir(getTestDir(), index, slave);
    }

    public static String getJournalDir(final String testDir, final int index, final boolean slave) {
        return getJournalDir(testDir) + directoryNameSuffix(index, slave);
    }

    /**
     * @return the bindingsDir
     */
    protected String getBindingsDir() {
        return getBindingsDir(0, false);
    }

    /**
     * @return the bindingsDir
     */
    protected static String getBindingsDir(final String testDir1) {
        return testDir1 + "/bindings";
    }

    /**
     * @return the bindingsDir
     */
    protected String getBindingsDir(final int index, final boolean slave) {
        return getBindingsDir(getTestDir(), index, slave);
    }

    public static String getBindingsDir(final String testDir, final int index, final boolean slave) {
        return getBindingsDir(testDir) + directoryNameSuffix(index, slave);
    }

    /**
     * @return the pageDir
     */
    protected String getPageDir() {
        return getPageDir(0, false);
    }

    protected File getPageDirFile() {
        return new File(getPageDir());

    }

    /**
     * @return the pageDir
     */
    protected static String getPageDir(final String testDir1) {
        return testDir1 + "/page";
    }

    protected String getPageDir(final int index, final boolean slave) {
        return getPageDir(getTestDir(), index, slave);
    }

    public static String getPageDir(final String testDir, final int index, final boolean slave) {
        return getPageDir(testDir) + directoryNameSuffix(index, slave);
    }

    /**
     * @return the largeMessagesDir
     */
    protected String getLargeMessagesDir() {
        return getLargeMessagesDir(0, false);
    }

    /**
     * @return the largeMessagesDir
     */
    protected static String getLargeMessagesDir(final String testDir1) {
        return testDir1 + "/large-msg";
    }

    protected String getLargeMessagesDir(final int index, final boolean slave) {
        return getLargeMessagesDir(getTestDir(), index, slave);
    }

    public static String getLargeMessagesDir(final String testDir, final int index, final boolean slave) {
        return getLargeMessagesDir(testDir) + directoryNameSuffix(index, slave);
    }

    private static String directoryNameSuffix(int index, boolean slave) {
        if (index == -1)
            return "";
        return index + "-" + (slave ? "B" : "L");
    }

    /**
     * @return the clientLargeMessagesDir
     */
    protected String getClientLargeMessagesDir() {
        return getClientLargeMessagesDir(getTestDir());
    }

    /**
     * @return the clientLargeMessagesDir
     */
    protected String getClientLargeMessagesDir(final String testDir1) {
        return testDir1 + "/client-large-msg";
    }

    /**
     * @return the temporaryDir
     */
    protected final String getTemporaryDir() {
        return getTemporaryDir(getTestDir());
    }

    /**
     * @return the temporaryDir
     */
    protected String getTemporaryDir(final String testDir1) {
        return testDir1 + "/temp";
    }

    protected static final void recreateDirectory(final String directory) {
        File file = new File(directory);
        deleteDirectory(file);
        file.mkdirs();
    }

    protected static final boolean deleteDirectory(final File directory) {
        return FileUtil.deleteDirectory(directory);
    }

    //Use this to reload a different broker xml, to mimick config change
    public void reloadMaster(String newBrokerXML) throws InterruptedException, IOException {
        reloadMaster(ArtemisTestSupport.class.getClassLoader().getResource(newBrokerXML));
    }

    //Use this to reload a different broker xml, to mimick config change
    public void reloadMaster(URL resource) throws InterruptedException, IOException {
        reload(master, resource);
    }

    //Use this to reload a different broker xml, to mimick config change
    public void reloadSlave(String newBrokerXML) throws InterruptedException, IOException {
        reloadSlave(ArtemisTestSupport.class.getClassLoader().getResource(newBrokerXML));
    }

    //Use this to reload a different broker xml, to mimick config change
    public void reloadSlave(URL resource) throws InterruptedException, IOException {
        reload(slave, resource);
    }


    public static void reload(EmbeddedActiveMQ broker, URL resource) throws InterruptedException, IOException {
        URL configurationUrl = broker.getActiveMQServer().getConfiguration().getConfigurationUrl();

        final ReusableLatch reloadLatch = new ReusableLatch(1);
        Runnable liveTick = reloadLatch::countDown;
        broker.getActiveMQServer().getReloadManager().setTick(liveTick);

        reloadLatch.await(10, TimeUnit.SECONDS);
        File brokerXML = new File(configurationUrl.getFile());
        Files.copy(resource.openStream(), brokerXML.toPath(), StandardCopyOption.REPLACE_EXISTING);
        brokerXML.setLastModified(System.currentTimeMillis() + 1000);
        reloadLatch.countUp();
        broker.getActiveMQServer().getReloadManager().setTick(liveTick);
        reloadLatch.await(10, TimeUnit.SECONDS);
    }
}
