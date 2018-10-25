package test;

import javax.jms.ConnectionFactory;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.Test;

public class FailoverTester extends CoreOnlyFailoverTester {

    @Test
    public void testAMQPFailover() throws Exception {
        final ConnectionFactory producerConnectionFactory = createAMQPConnectionFactory(USERNAME, PASSWORD);
        final ConnectionFactory consumerConnectionFactory = createAMQPConnectionFactory(USERNAME, PASSWORD);

        testMeFailover(producerConnectionFactory, consumerConnectionFactory);
    }

}
