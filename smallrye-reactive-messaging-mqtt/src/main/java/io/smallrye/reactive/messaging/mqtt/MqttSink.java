package io.smallrye.reactive.messaging.mqtt;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.eclipse.microprofile.reactive.streams.operators.SubscriberBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.mqtt.MqttClient;

public class MqttSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqttSink.class);

    private final String host;
    private final int port;
    private final MqttClient client;
    private final String server;
    private final String topic;
    private final int qos;

    private final SubscriberBuilder<? extends Message<?>, Void> sink;
    private final AtomicBoolean connected = new AtomicBoolean();

    public MqttSink(Vertx vertx, Config config) {
        MqttClientOptions options = new MqttClientOptions();
        options.setClientId(config.getOptionalValue("client-id", String.class).orElse(null));
        options.setAutoGeneratedClientId(config.getOptionalValue("auto-generated-client-id", Boolean.class)
                .orElse(false));
        options.setAutoKeepAlive(config.getOptionalValue("auto-keep-alive", Boolean.class).orElse(true));
        options.setSsl(config.getOptionalValue("ssl", Boolean.class).orElse(false));
        options.setWillQoS(config.getOptionalValue("will-qos", Integer.class).orElse(0));
        options.setKeepAliveTimeSeconds(
                config.getOptionalValue("keep-alive-seconds", Integer.class).orElse(30));
        options.setMaxInflightQueue(config.getOptionalValue("max-inflight-queue", Integer.class).orElse(10));
        options.setCleanSession(config.getOptionalValue("auto-clean-session", Boolean.class).orElse(true));
        options.setWillFlag(config.getOptionalValue("will-flag", Boolean.class).orElse(false));
        options.setWillRetain(config.getOptionalValue("will-retain", Boolean.class).orElse(false));
        options.setMaxMessageSize(config.getOptionalValue("max-message-size", Integer.class).orElse(-1));
        options.setReconnectAttempts(config.getOptionalValue("reconnect-attempts", Integer.class).orElse(5));
        options.setReconnectInterval(TimeUnit.SECONDS.toMillis(
                config.getOptionalValue("reconnect-interval-seconds", Integer.class).orElse(1)));
        options.setUsername(config.getOptionalValue("username", String.class).orElse(null));
        options.setPassword(config.getOptionalValue("password", String.class).orElse(null));
        options.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(
                config.getOptionalValue("connect-timeout-seconds", Integer.class).orElse(60)));
        options.setTrustAll(config.getOptionalValue("trust-all", Boolean.class).orElse(false));

        host = config.getValue("host", String.class);
        int def = options.isSsl() ? 8883 : 1883;
        port = config.getOptionalValue("port", Integer.class).orElse(def);
        server = config.getOptionalValue("server-name", String.class).orElse(null);
        topic = getTopicOrNull(config);
        client = MqttClient.create(vertx, options);
        qos = config.getOptionalValue("qos", Integer.class).orElse(0);

        sink = ReactiveStreams.<Message<?>> builder()
                .flatMapCompletionStage(msg -> {
                    // If not connected, connect
                    if (connected.get()) {
                        //forwarding
                        return CompletableFuture.completedFuture(msg);
                    } else {
                        return client.connect(port, host, server).subscribeAsCompletionStage()
                                .thenApply(x -> {
                                    connected.set(true);
                                    return msg;
                                });
                    }
                })
                .flatMapCompletionStage(msg -> {
                    String actualTopicToBeUsed = this.topic;
                    MqttQoS actualQoS = MqttQoS.valueOf(this.qos);
                    boolean isRetain = false;

                    if (msg instanceof SendingMqttMessage) {
                        MqttMessage mm = ((SendingMqttMessage) msg);

                        actualTopicToBeUsed = mm.getTopic() == null ? topic : mm.getTopic();
                        actualQoS = mm.getQosLevel() == null ? actualQoS : mm.getQosLevel();
                        isRetain = mm.isRetain();
                    }

                    if (actualTopicToBeUsed == null) {
                        LOGGER.error("Ignoring message - no topic set");
                        return CompletableFuture.completedFuture(msg);
                    }

                    return client.publish(actualTopicToBeUsed, convert(msg.getPayload()), actualQoS, false, isRetain)
                            .subscribeAsCompletionStage();
                })
                .onComplete(client::disconnect)
                .onError(t -> LOGGER.error("An error has been caught while sending a MQTT message to the broker", t))
                .ignore();
    }

    private Buffer convert(Object payload) {
        if (payload instanceof JsonObject) {
            return new Buffer(((JsonObject) payload).toBuffer());
        }
        if (payload instanceof JsonArray) {
            return new Buffer(((JsonArray) payload).toBuffer());
        }
        if (payload instanceof String || payload.getClass().isPrimitive()) {
            return new Buffer(io.vertx.core.buffer.Buffer.buffer(payload.toString()));
        }
        if (payload instanceof byte[]) {
            return new Buffer(io.vertx.core.buffer.Buffer.buffer((byte[]) payload));
        }
        if (payload instanceof Buffer) {
            return (Buffer) payload;
        }
        if (payload instanceof io.vertx.core.buffer.Buffer) {
            return new Buffer((io.vertx.core.buffer.Buffer) payload);
        }
        // Convert to Json
        return new Buffer(Json.encodeToBuffer(payload));
    }

    public SubscriberBuilder<? extends Message<?>, Void> getSink() {
        return sink;
    }

    private String getTopicOrNull(Config config) {
        return config.getOptionalValue("topic", String.class)
                .orElseGet(
                        () -> config.getOptionalValue("channel-name", String.class).orElse(null));
    }

    public boolean isReady() {
        return connected.get();
    }
}
