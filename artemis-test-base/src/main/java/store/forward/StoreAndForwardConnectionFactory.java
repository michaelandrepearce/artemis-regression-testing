package store.forward;

import java.util.*;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.config.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.core.settings.impl.DeletionPolicy;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import java.io.Closeable;

public class StoreAndForwardConnectionFactory implements Closeable, ConnectionFactory {

    private final ActiveMQConnectionFactory remoteConnectionFactory;
    private final ActiveMQConnectionFactory cspConnectionFactory;
    private final Configuration configuration;

    private volatile EmbeddedActiveMQ embeddedActiveMQ;

    public StoreAndForwardConnectionFactory(ActiveMQConnectionFactory remoteConnectionFactory, String storeLocation, String address) {
        this(remoteConnectionFactory, storeLocation, Collections.singletonList(address));
    }

    public StoreAndForwardConnectionFactory(ActiveMQConnectionFactory remoteConnectionFactory, String storeLocation, String... addresses) {
        this(remoteConnectionFactory, storeLocation, Arrays.asList(addresses));
    }

    public StoreAndForwardConnectionFactory(ActiveMQConnectionFactory remoteConnectionFactory, String storeLocation, List<String> addresses) {
        this(remoteConnectionFactory, storeLocation, addresses, null);
    }

    public StoreAndForwardConnectionFactory(ActiveMQConnectionFactory remoteConnectionFactory, String storeLocation, List<String> addresses, TransformerConfiguration bridgeTransformerConfiguration) {
        this.remoteConnectionFactory = remoteConnectionFactory;
        this.cspConnectionFactory = new ActiveMQConnectionFactory("vm://0");
        this.configuration = createDefaultConfiguration(remoteConnectionFactory, storeLocation, addresses, bridgeTransformerConfiguration);
    }

    private final Configuration createDefaultConfiguration(
            ActiveMQConnectionFactory remoteConnectionFactory,
            String storeLocation,
            List<String> addresses,
            TransformerConfiguration transformerConfiguration) {
        Configuration config = new ConfigurationImpl();

        config.setJournalDirectory(storeLocation+ "/journal");
        config.setBindingsDirectory(storeLocation+ "/bindings");
        config.setPagingDirectory(storeLocation+ "/paging");
        config.setLargeMessagesDirectory(storeLocation + "/large-messages");
        config.setJournalType(JournalType.MAPPED);
        config.setJournalDatasync(false);
        config.setJournalBufferTimeout_NIO(0);
        config.setPersistenceEnabled(true);

        HashSet<TransportConfiguration> transports = new HashSet<>();
        //This wouldn't be needed if we could fully use Server interfaces to implement all uses,
        // but this requires time, so for the cases we dont cover the producer will revert to going over VM transport.
        transports.add(new TransportConfiguration(InVMAcceptorFactory.class.getName()));
        config.setAcceptorConfigurations(transports);

        List<CoreAddressConfiguration> addressConfigurations = addresses.stream()
            .map(address -> new CoreAddressConfiguration()
                .setName(address)
                .addRoutingType(RoutingType.MULTICAST)
                .setQueueConfigurations(
                        Collections.singletonList(
                                new CoreQueueConfiguration()
                                        .setAddress(address)
                                        .setName(address)
                                        .setRoutingType(RoutingType.MULTICAST)
                        )
                )
            ).collect(Collectors.toList());

        config.setAddressConfigurations(addressConfigurations);

        AddressSettings addressSettings = new AddressSettings();
        addressSettings.setAutoDeleteQueues(false);
        addressSettings.setAutoDeleteAddresses(false);
        addressSettings.setAutoCreateAddresses(false);
        addressSettings.setAutoCreateQueues(false);

        addressSettings.setConfigDeleteAddresses(DeletionPolicy.FORCE);
        addressSettings.setConfigDeleteQueues(DeletionPolicy.FORCE);

        Map<String, AddressSettings> addressSettingsMap = new HashMap<>();
        addressSettingsMap.put("#", addressSettings);
        config.setAddressesSettings(addressSettingsMap);
        config.setSecurityEnabled(false);
        config.setClusterUser(remoteConnectionFactory.getUser());
        config.setClusterPassword(remoteConnectionFactory.getPassword());

        config.setThreadPoolMaxSize(2);
        config.setScheduledThreadPoolMaxSize(1);

        int c = 0;
        for (TransportConfiguration tc : remoteConnectionFactory.getStaticConnectors()) {
            config.addConnectorConfiguration("remote-connection-" + c, tc);
            c++;
        }

        List<BridgeConfiguration> bridgeConfigurations = addresses.stream()
                .map(address -> {
                    BridgeConfiguration bridgeConfiguration = new BridgeConfiguration();
                    bridgeConfiguration.setForwardingAddress(address);
                    bridgeConfiguration.setQueueName(address);
                    bridgeConfiguration.setName("csp-bridge-" + address);
                    bridgeConfiguration.setUseDuplicateDetection(true);
                    bridgeConfiguration.setConfirmationWindowSize(1000);
                    bridgeConfiguration.setStaticConnectors(new ArrayList<>(config.getConnectorConfigurations().keySet()));
                    bridgeConfiguration.setUser(config.getClusterUser());
                    bridgeConfiguration.setPassword(config.getClusterPassword());
                    bridgeConfiguration.setTransformerConfiguration(transformerConfiguration);
                    return bridgeConfiguration;
                }).collect(Collectors.toList());

        config.setBridgeConfigurations(bridgeConfigurations);

        return config;
    }

    private EmbeddedActiveMQ create() {
        EmbeddedActiveMQ embeddedJMS = new EmbeddedActiveMQ();
        embeddedJMS.setConfiguration(configuration);
        return embeddedJMS;
    }

    private synchronized void start() {
        if (embeddedActiveMQ == null) {
            embeddedActiveMQ = create();
            try {
                embeddedActiveMQ.start();
            } catch (Exception e) {
                embeddedActiveMQ = null;
            }
        }
    }

    @Override
    public synchronized void close() {
        if (embeddedActiveMQ != null) {
            try {
                embeddedActiveMQ.stop();
            } catch (Exception e) {
            } finally {
                embeddedActiveMQ = null;
            }
        }
    }

    public synchronized EmbeddedActiveMQ getEmbeddedActiveMQ() {
        return embeddedActiveMQ;
    }

    @Override
    public Connection createConnection() throws JMSException {
        start();
        return cspConnectionFactory.createConnection();
    }

    @Override
    public Connection createConnection(String s, String s1) throws JMSException {
        start();
        return cspConnectionFactory.createConnection(s, s1);
    }

    @Override
    public JMSContext createContext() {
        start();
        return cspConnectionFactory.createContext();
    }

    @Override
    public JMSContext createContext(String s, String s1) {
        start();
        return cspConnectionFactory.createContext(s, s1);
    }

    @Override
    public JMSContext createContext(String s, String s1, int i) {
        start();
        return cspConnectionFactory.createContext(s, s1, i);
    }

    @Override
    public JMSContext createContext(int i) {
        start();
        return cspConnectionFactory.createContext(i);
    }

    public Configuration getConfiguration() {
        return configuration;
    }
}
