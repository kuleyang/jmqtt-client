package net.ewant.jmqttclient;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.security.cert.CertificateException;

/**
 * Created by admin on 2018/12/7.
 */
public class JmqttClient {

    Logger logger = LoggerFactory.getLogger(JmqttClient.class);

    static final String TOPIC_START_CHART = "/";

    MqttClient client;
    ConfigOptions config;

    MqttConnectOptions options = new MqttConnectOptions();

    BackgroundConnect backgroundConnect = new BackgroundConnect("JmqttClient-BackgroundConnect");

    public JmqttClient(ConfigOptions config, MqttCallback callback){
        this(null, config, callback);
    }

    public JmqttClient(String clientId, ConfigOptions config, MqttCallback callback){
        this(clientId, config, callback, null);
    }

    /**
     * @param clientId
     * @param config
     * @param callback
     * @param persistence
     */
    public JmqttClient(String clientId, ConfigOptions config, MqttCallback callback, MqttClientPersistence persistence){
        try {
            if(config == null){
                throw new IllegalArgumentException("config must be not NULL");
            }
            if(callback == null){
                throw new IllegalArgumentException("callback must be not NULL");
            }
            String connectUrl = getConnectUrl(config);
            this.config = config;
            if(persistence == null){
                persistence = new MemoryPersistence();
            }
            if(clientId == null){
                clientId = config.getClientIdPrefix() + MqttClient.generateClientId();
            }
            this.client = new MqttClient(connectUrl, clientId, persistence);
            if(config.isUseSSL()){
                options.setSocketFactory(createIgnoreVerifySSL().getSocketFactory());
                options.setSSLHostnameVerifier(new IgnoreHostnameVerifier());
            }
            options.setAutomaticReconnect(config.isAutomaticReconnect());
            options.setMaxInflight(config.getMaxInflight());
            this.client.setCallback(callback);
            backgroundConnect.start();
        } catch (MqttException e) {
            logger.error(JmqttClient.class.getSimpleName() + " start error: " + e.getMessage(), e);
        }
    }

    private String getConnectUrl(ConfigOptions config){
        if(config.getHost() == null || config.getPort() < 1){
            throw new IllegalArgumentException("connect host and port invalid");
        }
        StringBuilder sb = new StringBuilder(config.isUseSSL() ? "ssl://" : "tcp://");
        sb.append(config.getHost());
        sb.append(":");
        sb.append(config.getPort());
        return sb.toString();
    }

    public void publish(String topic, String message){
        this.publish(topic, message, config.getDefaultQos(), false);
    }

    public void publish(String topic, String message, int qos, boolean retained){
        this.publish(topic, message.getBytes(), qos, retained);
    }

    public void publish(String topic, byte[] message, int qos, boolean retained){
        try {
            if(!topic.startsWith(TOPIC_START_CHART)){
                topic = TOPIC_START_CHART + topic;
            }
            this.client.publish(topic, message, qos, retained);
        } catch (MqttException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void subscribe(String topicFilter, int qos){
        this.subscribe(new String[] {topicFilter}, new int[] {qos});
    }

    public void subscribe(String[] topicFilters, int[] qos){
        try {
            client.subscribe(topicFilters, qos);
        } catch (MqttException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void unsubscribe(String topicFilter) throws MqttException {
        this.unsubscribe(new String[] {topicFilter});
    }

    public void unsubscribe(String[] topicFilters) throws MqttException {
        client.unsubscribe(topicFilters);
    }

    public void close(){
        try {
            client.disconnect();
        } catch (MqttException e) {
            logger.error(e.getMessage(), e);
        }
    }

    class BackgroundConnect extends Thread{

        boolean checkMode;

        public BackgroundConnect(String name){
            super(name);
        }

        @Override
        public void run() {
            while (true){
                JmqttClient.this.doConnect(checkMode);
            }
        }

    }

    /*递归调用，注意栈溢出*/
    private void doConnect(final boolean reconnect){
        if(reconnect){
            try {
                Thread.sleep(10000);
                if(!backgroundConnect.checkMode)logger.info(JmqttClient.class.getSimpleName() + " client reconnect with id: " + client.getClientId());
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }
        try {
            if(this.client.isConnected()){
                logger.debug(JmqttClient.class.getSimpleName() + " check connect with id: " + client.getClientId());
                return;
            }
            this.client.connectWithResult(options, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    backgroundConnect.checkMode = true;
                    logger.info(JmqttClient.class.getSimpleName() + " started and connected with id: " + client.getClientId());
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable e) {
                }
            });
        } catch (MqttException e) {
            // do something in onFailure
            logger.error(JmqttClient.class.getSimpleName() + " start failed with id: " + client.getClientId() + "error["+e.getMessage()+"]", e);
            JmqttClient.this.doConnect(true);
        }
    }

    private SSLContext createIgnoreVerifySSL() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            // 实现一个X509TrustManager接口，用于绕过验证，不用修改里面的方法
            X509TrustManager trustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(
                        java.security.cert.X509Certificate[] paramArrayOfX509Certificate,
                        String paramString) throws CertificateException {
                }
                @Override
                public void checkServerTrusted(
                        java.security.cert.X509Certificate[] paramArrayOfX509Certificate,
                        String paramString) throws CertificateException {
                }
                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            };
            sc.init(null, new TrustManager[] { trustManager }, null);
            return sc;
        } catch (Exception e) {
        }
        return null;
    }

    private class IgnoreHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    }
}
