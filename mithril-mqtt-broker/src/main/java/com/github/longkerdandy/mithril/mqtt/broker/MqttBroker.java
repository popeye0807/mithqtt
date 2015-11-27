package com.github.longkerdandy.mithril.mqtt.broker;

import com.github.longkerdandy.mithril.mqtt.api.auth.Authenticator;
import com.github.longkerdandy.mithril.mqtt.api.comm.BrokerCommunicator;
import com.github.longkerdandy.mithril.mqtt.api.comm.BrokerListenerFactory;
import com.github.longkerdandy.mithril.mqtt.broker.comm.BrokerListenerFactoryImpl;
import com.github.longkerdandy.mithril.mqtt.broker.handler.MessageMetricsHandler;
import com.github.longkerdandy.mithril.mqtt.broker.handler.SyncRedisHandler;
import com.github.longkerdandy.mithril.mqtt.broker.session.SessionRegistry;
import com.github.longkerdandy.mithril.mqtt.broker.util.Validator;
import com.github.longkerdandy.mithril.mqtt.storage.redis.sync.RedisSyncStorage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * MQTT Bridge
 */
public class MqttBroker {

    private static final Logger logger = LoggerFactory.getLogger(MqttBroker.class);

    public static void main(String[] args) throws Exception {

        logger.debug("Starting MQTT broker ...");

        // load config
        logger.debug("Loading MQTT broker config files ...");
        PropertiesConfiguration brokerConfig;
        PropertiesConfiguration redisConfig;
        PropertiesConfiguration communicatorConfig;
        PropertiesConfiguration authenticatorConfig;
        if (args.length >= 4) {
            brokerConfig = new PropertiesConfiguration(args[0]);
            redisConfig = new PropertiesConfiguration(args[1]);
            communicatorConfig = new PropertiesConfiguration(args[2]);
            authenticatorConfig = new PropertiesConfiguration(args[3]);
        } else {
            brokerConfig = new PropertiesConfiguration("config/broker.properties");
            redisConfig = new PropertiesConfiguration("config/redis.properties");
            communicatorConfig = new PropertiesConfiguration("config/communicator.properties");
            authenticatorConfig = new PropertiesConfiguration("config/authenticator.properties");
        }

        // validator
        logger.debug("Initializing validator ...");
        Validator validator = new Validator(brokerConfig);

        // session registry
        logger.debug("Initializing session registry ...");
        SessionRegistry registry = new SessionRegistry();

        // storage
        logger.debug("Initializing redis storage ...");
        // RedisAsyncStorage redis = (RedisAsyncStorage) Class.forName(redisConfig.getString("storage.async.class")).newInstance();
        // redis.init(RedisURI.create(redisConfig.getString("redis.uri")));
        RedisSyncStorage redis = (RedisSyncStorage) Class.forName(redisConfig.getString("storage.sync.class")).newInstance();
        redis.init(redisConfig);

        // authenticator
        logger.debug("Initializing authenticator...");
        Authenticator authenticator = (Authenticator) Class.forName(authenticatorConfig.getString("authenticator.class")).newInstance();
        authenticator.init(authenticatorConfig);

        // communicator
        logger.debug("Initializing communicator ...");
        BrokerCommunicator communicator = (BrokerCommunicator) Class.forName(communicatorConfig.getString("communicator.class")).newInstance();
        BrokerListenerFactory listenerFactory = new BrokerListenerFactoryImpl(registry);
        communicator.init(communicatorConfig, brokerConfig.getString("broker.id"), listenerFactory);

        // metrcis
        final boolean metrics = brokerConfig.getBoolean("mqtt.metrics.enabled");
        final String dbName = metrics ? brokerConfig.getString("influxdb.dbname") : null;
        final InfluxDB influxDB = metrics ? InfluxDBFactory.connect(brokerConfig.getString("influxdb.url"), brokerConfig.getString("influxdb.username"), brokerConfig.getString("influxdb.password")) : null;
        if (metrics) {
            logger.debug("Initializing metrics ...");
            influxDB.createDatabase(dbName);
            influxDB.enableBatch(brokerConfig.getInt("influxdb.actions"), brokerConfig.getInt("influxdb.durations"), TimeUnit.MILLISECONDS);
        }

        // broker
        final String brokerId = brokerConfig.getString("broker.id");
        final int keepAlive = brokerConfig.getInt("mqtt.keepalive.default");
        final int keepAliveMax = brokerConfig.getInt("mqtt.keepalive.max");
        final boolean ssl = brokerConfig.getBoolean("mqtt.ssl.enabled");
        final SslContext sslContext = ssl ? SslContextBuilder.forServer(new File(brokerConfig.getString("mqtt.ssl.certPath")), new File(brokerConfig.getString("mqtt.ssl.keyPath")), brokerConfig.getString("mqtt.ssl.keyPassword")).build() : null;
        final String host = brokerConfig.getString("mqtt.host");
        final int port = ssl ? brokerConfig.getInt("mqtt.ssl.port") : brokerConfig.getInt("mqtt.port");

        // tcp server
        logger.debug("Initializing tcp server ...");
        InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        EventLoopGroup handlerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            // ssl
                            if (ssl) {
                                p.addLast("ssl", sslContext.newHandler(ch.alloc()));
                            }
                            // idle
                            p.addFirst("idleHandler", new IdleStateHandler(0, 0, keepAlive));
                            // mqtt encoder & decoder
                            p.addLast("encoder", new MqttEncoder());
                            p.addLast("decoder", new MqttDecoder());
                            // metrics
                            if (metrics) {
                                p.addLast("msg-metrics", new MessageMetricsHandler(brokerId, influxDB, dbName));
                            }
                            // logic handler
                            p.addLast(handlerGroup, "logicHandler", new SyncRedisHandler(authenticator, communicator, redis, registry, validator, brokerId, keepAlive, keepAliveMax));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, brokerConfig.getInt("netty.soBacklog"))
                    .childOption(ChannelOption.SO_KEEPALIVE, brokerConfig.getBoolean("netty.soKeepAlive"));

            // Bind and start to accept incoming connections.
            ChannelFuture f = b.bind(host, port).sync();

            logger.info("MQTT broker is up and running.");

            // Wait until the server socket is closed.
            // Do this to gracefully shut down the server.
            f.channel().closeFuture().sync();
        } finally {
            communicator.destroy();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
