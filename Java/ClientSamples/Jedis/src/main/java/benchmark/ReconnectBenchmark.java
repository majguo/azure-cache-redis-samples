package benchmark;

import com.beust.jcommander.JCommander;
import cluster.JedisClusterHelper;
import pool.JedisHelper;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.log4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class ReconnectBenchmark {
    private final static Logger logger = Logger.getLogger(ReconnectBenchmark.class);
    private Random random = new Random();
    private volatile Interval interval = new Interval();
    private volatile boolean isConnected = true;
    private final Queue<Interval> intervals = new ConcurrentLinkedQueue<>();
    private static final String DEFAULT_STRING = "foo";
    private final BenchmarkArgs args;
    private static JedisPool jedisPool;
    private static JedisCluster jedisCluster;

    public ReconnectBenchmark(BenchmarkArgs args) {
        this.args = args;
    }

    public static void main(String[] args){
        BenchmarkArgs benchmarkArgs = new BenchmarkArgs();
        JCommander.newBuilder()
                .addObject(benchmarkArgs)
                .build()
                .parse(args);
        new ReconnectBenchmark(benchmarkArgs).runTest();
    }

    public void runTest(){
        logger.info("Start to test...");
        if(args.isCluster){
            logger.info("Cluster mode");
            jedisCluster = JedisClusterHelper.getCluster(args.configFilePath);
            logger.info(JedisClusterHelper.getClusterConfig(jedisCluster));
        } else {
            logger.info("Pool mode");
            jedisPool = JedisHelper.getPool(args.configFilePath);
            logger.info(JedisHelper.getPoolConfig(jedisPool));
        }
        while(intervals.size() < args.numberOfTests){
            if(args.isCluster){
                simulateClusterWorkload();
            } else {
                simulatePoolWorkload();
            }
        }

        logResult();
    }

    // 70% read, 30% write
    private void simulateWorkload(JedisCluster jedis){
        if(random.nextInt(10) <= 3){
            jedis.set(getString(), getString());
        } else {
            jedis.get(getString());
        }
    }

    private void simulateWorkload(Jedis jedis){
        if(random.nextInt(10) <= 3){
            jedis.set(getString(), getString());
        } else {
            jedis.get(getString());
        }
    }

    private void simulateClusterWorkload(){
        long startTime = System.currentTimeMillis();
        try{
            simulateWorkload(jedisCluster);

            checkUnconnected();
            sleepIfNecessary();
        } catch (JedisConnectionException e){
            long duration = (System.currentTimeMillis() - startTime);
            if(duration > 1000){
                logger.info("GET or SET spent " + duration);
            }
            checkConnected();

            logger.info(JedisClusterHelper.getClusterUsage(jedisCluster));
        }
    }

    private void simulatePoolWorkload(){
        try(Jedis jedis = jedisPool.getResource()){
            simulateWorkload(jedis);

            checkUnconnected();
            sleepIfNecessary();
        } catch (JedisConnectionException e){
            checkConnected();
            logger.info(JedisHelper.getPoolUsage(jedisPool));
        }
    }

    private void sleepIfNecessary(){
        if(args.maxOperationPerSecond >= 0){
            try {
                Thread.sleep(1000 / args.maxOperationPerSecond, 1000 * 1000 * 1000 / args.maxOperationPerSecond);
            } catch (InterruptedException e) {
                logger.error("Thread interrupted", e);
            }
        }
    }

    private synchronized void checkUnconnected(){
        if(!isConnected){
            interval.end();
            intervals.add(interval);
            isConnected = true;
            if(args.verbose) {
                logger.info("Connected");
                logResult();
            }
        }
    }

    private synchronized void checkConnected(){
        if(isConnected){
            interval = new Interval();
            interval.start();
            isConnected = false;
            if(args.verbose) {
                logger.info("Disconnected.");
            }
        }
    }

    private void logResult(){
        List<Interval> intervalsSortedByDuration = new ArrayList<>(intervals);
        intervalsSortedByDuration.sort(Comparator.comparing(Interval::getDuration));
        LongSummaryStatistics summaryStatistics = intervalsSortedByDuration.parallelStream().collect(Collectors.summarizingLong(i -> i.getDuration().toMillis()));

        logger.info("Intervals are " + intervals.stream().map(Object::toString).collect(Collectors.joining(",")));
        logger.info("Total tests: " + summaryStatistics.getCount());
        logger.info("Average (Seconds): " + summaryStatistics.getAverage() / 1000.0);
        logger.info("Min (Seconds): " + summaryStatistics.getMin() / 1000.0);
        logger.info("Max (Seconds): " + summaryStatistics.getMax() / 1000.0);

        int[] percentiles = {50, 90, 95, 99};

        Arrays.stream(percentiles).forEach(i -> logger.info(String.format("%s %% <= %s", i, intervalsSortedByDuration.get(((intervalsSortedByDuration.size() + 1) * i / 100) - 1).getDuration().toMillis() / 1000.0)));

    }

    private String getString(){
        if(!args.random){
            return DEFAULT_STRING;
        } else {
            return RandomStringUtils.random(args.dataSizeInBytes);
        }
    }
}