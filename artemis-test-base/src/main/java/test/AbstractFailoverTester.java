package test;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import static java.util.Arrays.asList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.ConnectionMetaData;
import javax.jms.JMSException;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import store.forward.StoreAndForwardConnectionFactory;
import test.common.ArtemisTestSupport;
import test.common.Wait;

public abstract class AbstractFailoverTester extends ArtemisTestSupport {
    
    public String DESTINATION_ADDRESS = "com_ig_trade_v0_l2_execution_report";
    public String SUBSCRIPTION = "com_ig_trade_v0_l2_execution_report/test";
    public static final String USERNAME = "mario_jms_username";
    public static final String PASSWORD = "mario_jms_password";


    private static final DateTimeFormatter TEST_RUN_ID_FORMATTER = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");
    
    private static final Object SESSION_LOCK = new Object();
    private static final int NUMBER_OF_THREADS = 1;
    private static final int NUMBER_OF_MESSAGES_TO_SEND = 300;
    private static final double MESSAGES_PER_SECOND = 30.0;

    private static final Logger LOG = LoggerFactory.getLogger(AbstractFailoverTester.class);

    public void testMeFailover(ConnectionFactory producerConnectionFactory) throws Exception {
        ConnectionFactory consumerConnectionFactory = createCoreConnectionFactory(USERNAME, PASSWORD);
        testMeFailover(producerConnectionFactory, consumerConnectionFactory);
    }

    public void testMeFailover(ConnectionFactory producerConnectionFactory, ConnectionFactory consumerConnectionFactory) throws Exception {
        testMe(producerConnectionFactory, consumerConnectionFactory, (i) -> {

            if (i == 50) {
                try {
                    new Thread(() -> {
                        try {
                            getMasterBroker().stop();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void testMe(ConnectionFactory producerConnectionFactory) throws Exception {
        testMe(producerConnectionFactory, null);
    }

    public void testMe(ConnectionFactory producerConnectionFactory, Consumer<Integer> consumer) throws Exception {
        ConnectionFactory consumerConnectionFactory = createCoreConnectionFactory(USERNAME, PASSWORD);
        testMe(producerConnectionFactory, consumerConnectionFactory, consumer);
    }

    public void testMe(ConnectionFactory producerConnectionFactory, ConnectionFactory consumerConnectionFactory, Consumer<Integer> consumer) throws Exception {
        final ConnectionMetaData producerMetaData;
        final ConnectionMetaData consumerMetaData;
        try (Connection producerConnection = producerConnectionFactory.createConnection();
             Connection consumerConnection = producerConnectionFactory.createConnection()) {
            producerMetaData = producerConnection.getMetaData();
            consumerMetaData = consumerConnection.getMetaData();
        }

        final AtomicInteger messagesReceived = new AtomicInteger();
        final AtomicInteger messagesSent = new AtomicInteger(0);
        final AtomicInteger messagesFailed = new AtomicInteger(0);

        AtomicBoolean running = new AtomicBoolean(true);

        DefaultMessageListenerContainer defaultMessageListenerContainer = new DefaultMessageListenerContainer();
        defaultMessageListenerContainer.setConnectionFactory(consumerConnectionFactory);
        defaultMessageListenerContainer.setPubSubDomain(true);
        defaultMessageListenerContainer.setDestinationName(DESTINATION_ADDRESS);
        defaultMessageListenerContainer.setSubscriptionDurable(true);
        defaultMessageListenerContainer.setSubscriptionName(SUBSCRIPTION);
        defaultMessageListenerContainer.setSubscriptionShared(true);
        defaultMessageListenerContainer.setMessageListener((MessageListener) message -> {
            messagesReceived.incrementAndGet();
            try {
                LOG.info("Received message {}", ((TextMessage)message).getText());
            } catch (JMSException e) {
                LOG.error(e.getMessage(), e);
            }
        });
        defaultMessageListenerContainer.setAutoStartup(true);
        defaultMessageListenerContainer.afterPropertiesSet();
        defaultMessageListenerContainer.start();




        LOG.info("User dir is: {}", System.getProperty("user.dir"));
        LOG.info("Initialising...");
        final ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_THREADS);
        final RateLimiter rateLimiter = RateLimiter.create(MESSAGES_PER_SECOND);

        CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory(producerConnectionFactory);
        cachingConnectionFactory.afterPropertiesSet();

        JmsTemplate jmsTemplate = new JmsTemplate(cachingConnectionFactory);
        jmsTemplate.setDefaultDestinationName(DESTINATION_ADDRESS);
        jmsTemplate.setPubSubDomain(true);
        jmsTemplate.afterPropertiesSet();

        final String testRunId = newTestRunId();

        LOG.info("Initialised");
        LOG.info("Waiting some arbitrary time for the Metrics producer...");
        Thread.sleep(2000L);

        printTestHeader(producerMetaData, consumerMetaData, testRunId);

        final CountDownLatch countDownLatch = new CountDownLatch(NUMBER_OF_MESSAGES_TO_SEND);
        for (int i = 0; i < NUMBER_OF_MESSAGES_TO_SEND; i++) {
            final String payload = createMessagePayload(testRunId, i + 1);
            if (consumer != null) {
                consumer.accept(i);
            }
            rateLimiter.acquire();
            executorService.execute(() -> {
                try {
                    synchronized (SESSION_LOCK) {
                        jmsTemplate.send(session1 -> session1.createTextMessage(payload));
                    }
                    LOG.info("Sent message {}", payload);
                    messagesSent.incrementAndGet();
                } catch (Exception e) {
                    LOG.error("Failed to send message: " + payload, e);
                    messagesFailed.incrementAndGet();
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        countDownLatch.await();

        Wait.waitFor(() -> messagesReceived.get() == messagesSent.get(), 30000);

        running.set(false);

        MoreExecutors.shutdownAndAwaitTermination(executorService, 5L, SECONDS);
        defaultMessageListenerContainer.stop();

        printTestCompleted(producerMetaData, consumerMetaData, testRunId, messagesSent, messagesFailed, messagesReceived);

        assertEquals(messagesSent.get(), messagesReceived.get());

        if (consumerConnectionFactory instanceof AutoCloseable) {
            ((AutoCloseable) consumerConnectionFactory).close();
        }
        if (producerConnectionFactory instanceof AutoCloseable) {
            ((AutoCloseable) producerConnectionFactory).close();
        }
        LOG.info("Bye");
    }
    

    
    private static String createMessagePayload(String testRunId, int messageSeqNo) {
        return "TEST-" + testRunId + '-' + messageSeqNo;
    }
    
    private static String newTestRunId() {
        return LocalDateTime.now().format(TEST_RUN_ID_FORMATTER);
    }
    
    private static void printTestHeader(ConnectionMetaData producer, ConnectionMetaData consumer, String testRunId) throws JMSException {
        LOG.info("===================================");
        LOG.info("Test run ID={} starting", testRunId);
        LOG.info("Producer provider={} version={}", producer.getJMSProviderName(), producer.getProviderVersion());
        LOG.info("Consumer provider={} version={}", consumer.getJMSProviderName(), consumer.getProviderVersion());
        LOG.info("Messages to send: {}", NUMBER_OF_MESSAGES_TO_SEND);
        LOG.info("Number of threads: {}", NUMBER_OF_THREADS);
        LOG.info("Message rate: {}/second", MESSAGES_PER_SECOND);
        LOG.info("===================================");
    }
    
    private static void printTestCompleted(ConnectionMetaData producer, ConnectionMetaData consumer, String testRunId, AtomicInteger messagesSent, AtomicInteger messagesFailed, AtomicInteger messagesReceived) throws JMSException {
        LOG.info("====================================");
        LOG.info("Test run ID={} completed", testRunId);
        LOG.info("Producer provider={} version={}", producer.getJMSProviderName(), producer.getProviderVersion());
        LOG.info("Consumer provider={} version={}", consumer.getJMSProviderName(), consumer.getProviderVersion());
        LOG.info("Messages sent: {}/{}", messagesSent, NUMBER_OF_MESSAGES_TO_SEND);
        LOG.info("Messages failed: {}/{}", messagesFailed, NUMBER_OF_MESSAGES_TO_SEND);
        LOG.info("Messages received: {}/{}", messagesReceived, messagesSent);
        LOG.info("====================================");
    }

}
