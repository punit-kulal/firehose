package io.odpf.firehose.sink.redis.client;

import io.odpf.depot.metrics.StatsDReporter;
import io.odpf.firehose.config.RedisSinkConfig;
import io.odpf.firehose.config.enums.RedisSinkDeploymentType;
import io.odpf.firehose.exception.ConfigurationException;
import io.odpf.firehose.metrics.FirehoseInstrumentation;
import io.odpf.firehose.proto.ProtoToFieldMapper;
import io.odpf.firehose.sink.redis.parsers.RedisParser;
import io.odpf.firehose.sink.redis.parsers.RedisParserFactory;
import io.odpf.firehose.sink.redis.ttl.RedisTtl;
import io.odpf.firehose.sink.redis.ttl.RedisTTLFactory;
import io.odpf.stencil.client.StencilClient;
import io.odpf.stencil.Parser;
import org.apache.commons.lang.StringUtils;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;

import java.util.HashSet;

/**
 * Redis client factory.
 */
public class RedisClientFactory {

    private static final String DELIMITER = ",";
    private StatsDReporter statsDReporter;
    private RedisSinkConfig redisSinkConfig;
    private StencilClient stencilClient;

    /**
     * Instantiates a new Redis client factory.
     *
     * @param statsDReporter  the statsd reporter
     * @param redisSinkConfig the redis sink config
     * @param stencilClient   the stencil client
     */
    public RedisClientFactory(StatsDReporter statsDReporter, RedisSinkConfig redisSinkConfig, StencilClient stencilClient) {
        this.statsDReporter = statsDReporter;
        this.redisSinkConfig = redisSinkConfig;
        this.stencilClient = stencilClient;
    }

    public RedisClient getClient() {
        Parser protoParser =  stencilClient.getParser(redisSinkConfig.getInputSchemaProtoClass());
        ProtoToFieldMapper protoToFieldMapper = new ProtoToFieldMapper(protoParser, redisSinkConfig.getInputSchemaProtoToColumnMapping());
        RedisParser redisParser = RedisParserFactory.getParser(protoToFieldMapper, protoParser, redisSinkConfig, statsDReporter);
        RedisSinkDeploymentType redisSinkDeploymentType = redisSinkConfig.getSinkRedisDeploymentType();
        RedisTtl redisTTL = RedisTTLFactory.getTTl(redisSinkConfig);
        return RedisSinkDeploymentType.CLUSTER.equals(redisSinkDeploymentType)
                ? getRedisClusterClient(redisParser, redisTTL)
                : getRedisStandaloneClient(redisParser, redisTTL);
    }

    private RedisStandaloneClient getRedisStandaloneClient(RedisParser redisParser, RedisTtl redisTTL) {
        Jedis jedis = null;
        try {
            jedis = new Jedis(HostAndPort.parseString(StringUtils.trim(redisSinkConfig.getSinkRedisUrls())));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(String.format("Invalid url for redis standalone: %s", redisSinkConfig.getSinkRedisUrls()));
        }
        return new RedisStandaloneClient(new FirehoseInstrumentation(statsDReporter, RedisStandaloneClient.class), redisParser, redisTTL, jedis);
    }

    private RedisClusterClient getRedisClusterClient(RedisParser redisParser, RedisTtl redisTTL) {
        String[] redisUrls = redisSinkConfig.getSinkRedisUrls().split(DELIMITER);
        HashSet<HostAndPort> nodes = new HashSet<>();
        try {
            for (String redisUrl : redisUrls) {
                nodes.add(HostAndPort.parseString(StringUtils.trim(redisUrl)));
            }
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(String.format("Invalid url(s) for redis cluster: %s", redisSinkConfig.getSinkRedisUrls()));
        }
        JedisCluster jedisCluster = new JedisCluster(nodes);
        return new RedisClusterClient(new FirehoseInstrumentation(statsDReporter, RedisClusterClient.class), redisParser, redisTTL, jedisCluster);
    }
}
