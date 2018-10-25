package test;

import javax.jms.ConnectionFactory;
import org.junit.Test;
import test.AbstractFailoverTester;

public class CoreOnlyFailoverTester extends AbstractFailoverTester {

    @Test
    public void testCore() throws Exception {
        final ConnectionFactory producerConnectionFactory = createCoreConnectionFactory(USERNAME, PASSWORD);
        testMe(producerConnectionFactory);
    }

    @Test
    public void testCoreFailover() throws Exception {
        final ConnectionFactory producerConnectionFactory = createCoreConnectionFactory(USERNAME, PASSWORD);
        testMeFailover(producerConnectionFactory);
    }

}
