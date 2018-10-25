package test.common;

import java.util.ArrayList;
import java.util.List;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;
import javax.jms.JMSException;

public class TrackerConnectionFactory implements ConnectionFactory, AutoCloseable {

    private List<Connection> connections = new ArrayList<>();
    private List<JMSContext> jmsContexts = new ArrayList<>();

    private final ConnectionFactory connectionFactory;

    public TrackerConnectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Connection createConnection() throws JMSException {
        Connection connection = connectionFactory.createConnection();
        connections.add(connection);
        return connection;
    }

    @Override
    public Connection createConnection(String userName, String password) throws JMSException {
        Connection connection =  connectionFactory.createConnection(userName, password);
        connections.add(connection);
        return connection;
    }

    @Override
    public JMSContext createContext() {
        JMSContext jmsContext = connectionFactory.createContext();
        jmsContexts.add(jmsContext);
        return jmsContext;
    }

    @Override
    public JMSContext createContext(int sessionMode) {
        JMSContext jmsContext =  connectionFactory.createContext(sessionMode);
        jmsContexts.add(jmsContext);
        return jmsContext;
    }

    @Override
    public JMSContext createContext(String userName, String password) {
        JMSContext jmsContext =  connectionFactory.createContext(userName, password);
        jmsContexts.add(jmsContext);
        return jmsContext;
    }

    @Override
    public JMSContext createContext(String userName, String password, int sessionMode) {
        JMSContext jmsContext =  connectionFactory.createContext(userName, password, sessionMode);
        jmsContexts.add(jmsContext);
        return jmsContext;
    }

    @Override
    public void close() throws Exception {
        Exception exception = null;
        for (Connection connection : connections) {
            try {
                connection.close();
            } catch (Exception e) {
                exception = e;
            }
        }
        for (JMSContext jmsContexts : jmsContexts) {
            try {
                jmsContexts.close();
            } catch (Exception e) {
                exception = e;
            }
        }
        if (exception != null) {
            throw exception;
        }
    }
}
