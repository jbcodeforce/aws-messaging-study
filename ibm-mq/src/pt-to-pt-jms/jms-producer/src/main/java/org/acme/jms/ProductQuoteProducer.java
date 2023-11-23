package org.acme.jms;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.JMSProducer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * A bean producing random prices every n seconds and sending them to the prices JMS queue.
 */
@ApplicationScoped
public class ProductQuoteProducer implements Runnable, ExceptionListener {
    Logger logger = Logger.getLogger(ProductQuoteProducer.class.getName());
    
    public static String[] skus = {"sku1", "sku2", "sku3", "sku4", "sku5", "sku6", "sku7", "sku8", "sku9", "sku10"};
 
    @Inject
    @ConfigProperty(name="reconnect.delay.ins")
    public int reconnectDelay;
    @Inject
    @ConfigProperty(name = "mq.host", defaultValue = "localhost" )
    public String mqHostname;

    @Inject
    @ConfigProperty(name = "mq.port", defaultValue = "1414")
    public int mqHostport;

    @Inject
    @ConfigProperty(name = "mq.qmgr", defaultValue = "QM1")
    public String mqQmgr;

    @Inject
    @ConfigProperty(name = "mq.channel", defaultValue = "DEV.APP.SVRCONN")
    public String mqChannel;

    @Inject
    @ConfigProperty(name = "mq.app_user", defaultValue = "app")
    public String mqAppUser;

    @Inject
    @ConfigProperty(name = "mq.app_password", defaultValue = "passw0rd")
    public String mqPassword;

    @Inject
    @ConfigProperty(name = "mq.queue_name", defaultValue = "DEV.QUEUE.1")
    public String queueName;

    @Inject
    @ConfigProperty(name = "app.name", defaultValue = "TestApp")
    public String appName;

    @Inject
    @ConfigProperty(name = "mq.cipher_suite")
    public Optional<String> mqCipherSuite;

    @Inject
    @ConfigProperty(name = "mq.ccdt_url")
    public Optional<String> mqCcdtUrl;

    JmsConnectionFactory connectionFactory;
    private Connection connection = null;
    private MessageProducer producer = null;
    private Session producerSession;
    private Queue outQueue;

    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledExecutorService reconnectScheduler = null;


    void onStart(@Observes StartupEvent ev) {
        try {
            restablishConnection();
        } catch (JMSException e) {
          e.printStackTrace();

        }
        logger.info("JMS Producer Started");
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (simulatorScheduler != null) 
            simulatorScheduler.shutdownNow();
        disconnect();
    }

    private synchronized void disconnect() {
        closeUtil(producerSession);
        closeUtil(connection);
        producerSession = null;
        producer = null;
        connection = null;
    }

    private synchronized void restablishConnection() throws javax.jms.JMSException {
        if (connection == null) {
            JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
            connectionFactory = ff.createConnectionFactory();
            connection = connectionFactory.createConnection(mqAppUser, mqPassword);
            connection.setClientID("p-" + System.currentTimeMillis());
            connection.setExceptionListener(this);
        }
        if (producer == null || producerSession == null)
            initProducer();

        connection.start();
        logger.info("Connect to broker succeed");
    }

    public boolean isConnected() {
        return connection != null 
            && producerSession != null;
    }

    void start(long delay) {
        scheduler.scheduleWithFixedDelay(this, 0L, delay, TimeUnit.SECONDS);
    }

    void stop() {
        scheduler.shutdown();
    }

    
    private Quote createRandomQuote(){
        return new Quote(skus[random.nextInt(skus.length)], random.nextInt(100));
    
    }

    private void initProducer() throws JMSException{
        producerSession = connection.createSession();
        outQueue = producerSession.createQueue(queueName);
        producer = producerSession.createProducer(outQueue);
        producer.setTimeToLive(60000); // one minute
    }
    
    @Override
    public void run() {
        try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
           
            Quote q = createRandomQuote();
            TextMessage msg =  context.createTextMessage(q.toString());
            msg.setJMSMessageID(UUID.randomUUID().toString());
            JMSProducer producer = context.createProducer();
            producer.send(context.createQueue(queueName),msg);
            logger.info("Sent: " + q.toString());
        } catch (JMSException e) {
            e.printStackTrace();
        }
    }

    public void sendNmessages(int totalMessageToSend) throws InterruptedException {
        logger.info("Sending " + totalMessageToSend + " messages");
        try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            JMSProducer producer = context.createProducer();
            for (int i = 0; i < totalMessageToSend; i++) {
                Quote q = createRandomQuote();
                TextMessage msg =  context.createTextMessage(q.toString());
                msg.setJMSMessageID(UUID.randomUUID().toString());
                producer.send(context.createQueue(queueName), msg);
                logger.info("Sent: " + q.toString());
                Thread.sleep(500);
            }
        } catch (JMSException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onException(JMSException arg0) {
        logger.error("JMS Exception occured: " + arg0.getMessage());
        disconnect();
        reconnect(reconnectDelay);
    }

    private void reconnect(int delay) {
        if (reconnectScheduler == null) 
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
        reconnectScheduler.schedule( () -> {
            try {
                restablishConnection();
            } catch (JMSException e) {  
                logger.info("Reconnect to broker fails, retrying in " + delay + " s.");
                disconnect();
                reconnect(delay + 5);
            }
        } , delay, TimeUnit.SECONDS);
        
    }

    private void closeUtil(AutoCloseable ac) {
        try {
            if (ac != null) ac.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
