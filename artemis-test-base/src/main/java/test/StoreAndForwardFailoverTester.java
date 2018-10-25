package test;

import java.io.File;
import static java.util.Arrays.asList;
import javax.jms.ConnectionFactory;
import org.apache.activemq.artemis.utils.FileUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import store.forward.StoreAndForwardConnectionFactory;
import test.common.TrackerConnectionFactory;

public class StoreAndForwardFailoverTester extends FailoverTester {

    @Before
    public void setUp() throws Exception {
        //Ensure SandF is clear
        FileUtil.deleteDirectory(new File(STORE_LOCATION_PATH));
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        FileUtil.deleteDirectory(new File(STORE_LOCATION_PATH));
    }

    @Test
    public void testStoreAndForward() throws Exception {
        final ConnectionFactory producerConnectionFactory = createConnectionFactoryStoreAndForward(USERNAME, PASSWORD, asList(DESTINATION_ADDRESS));
        testMe(producerConnectionFactory);
    }

    @Test
    public void testStoreAndForwardFailover() throws Exception {
        final ConnectionFactory producerConnectionFactory = createConnectionFactoryStoreAndForward(USERNAME, PASSWORD, asList(DESTINATION_ADDRESS));
        testMeFailover(producerConnectionFactory);
    }

}
