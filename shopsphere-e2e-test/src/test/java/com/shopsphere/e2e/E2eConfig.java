package com.shopsphere.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * E2E 全局配置。优先级：System.getProperty (`-Dkey=value`) > classpath `e2e.properties`。
 * 单例懒加载；测试基类直接读 {@link #get()}。
 */
public final class E2eConfig {

    private static volatile E2eConfig INSTANCE;

    private final Properties props = new Properties();

    private E2eConfig() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("e2e.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("e2e.properties 加载失败", e);
        }
    }

    public static E2eConfig get() {
        if (INSTANCE == null) {
            synchronized (E2eConfig.class) {
                if (INSTANCE == null) INSTANCE = new E2eConfig();
            }
        }
        return INSTANCE;
    }

    public String str(String key) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) return sys;
        String v = props.getProperty(key);
        if (v == null) throw new IllegalStateException("missing config key: " + key);
        return v;
    }

    public int integer(String key) { return Integer.parseInt(str(key)); }
    public long longVal(String key) { return Long.parseLong(str(key)); }

    public String gatewayBase()  { return str("gateway.base"); }
    public String recoBase()     { return str("reco.base"); }

    public String mysqlHost()    { return str("mysql.host"); }
    public int    mysqlPort()    { return integer("mysql.port"); }
    public String mysqlUser()    { return str("mysql.user"); }
    public String mysqlPass()    { return str("mysql.password"); }

    public String redisHost()    { return str("redis.host"); }
    public int    redisPort()    { return integer("redis.port"); }

    public String rabbitApi()    { return str("rabbit.api"); }
    public String rabbitUser()   { return str("rabbit.user"); }
    public String rabbitPass()   { return str("rabbit.password"); }

    public long seedProductId()  { return longVal("seed.product.id"); }
    public long seedProductId2() { return longVal("seed.product.id2"); }
    public long seedProductId3() { return longVal("seed.product.id3"); }
    public int  seedStock()      { return integer("seed.product.stock"); }
    public long seedAddressId()  { return longVal("seed.address.id"); }

    public int  defaultAwaitSec(){ return integer("await.default.sec"); }
    public int  timeoutAwaitSec(){ return integer("await.timeout.sec"); }
}
