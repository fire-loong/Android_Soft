package com.actionloop.api.test;

/**
 * 集成测试使用的基础设施连接参数，全部可由环境变量覆盖（CI 注入 service 容器地址）。
 * 与 application-test.yml / {@link AbstractIntegrationTest} 的 @DynamicPropertySource 保持单一事实来源。
 */
public final class TestInfraSettings {

    private TestInfraSettings() {}

    public static String pgHost() { return env("ACTIONLOOP_TEST_PG_HOST", "127.0.0.1"); }
    public static int pgPort() { return Integer.parseInt(env("ACTIONLOOP_TEST_PG_PORT", "5433")); }
    public static String pgDb() { return env("ACTIONLOOP_TEST_PG_DB", "actionloop_test"); }
    public static String pgUser() { return env("ACTIONLOOP_TEST_PG_USER", "postgres"); }
    public static String pgPassword() { return env("ACTIONLOOP_TEST_PG_PASSWORD", "actionloop_dev"); }

    public static String redisHost() { return env("ACTIONLOOP_TEST_REDIS_HOST", "127.0.0.1"); }
    public static int redisPort() { return Integer.parseInt(env("ACTIONLOOP_TEST_REDIS_PORT", "6379")); }
    public static String redisPassword() { return env("ACTIONLOOP_TEST_REDIS_PASSWORD", ""); }

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + pgHost() + ":" + pgPort() + "/" + pgDb();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
