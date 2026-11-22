package com.actionloop.api.test;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * 探测测试用 PostgreSQL（必要时自动创建库）与 Redis 是否可达。
 * 任一不可达即禁用测试，保证无基础设施时构建不红。
 */
public class TestInfraCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("PostgreSQL + Redis test infra reachable");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            ensureDatabase();
            pingRedis();
            return ENABLED;
        } catch (Exception e) {
            return ConditionEvaluationResult.disabled(
                    "test infra unavailable (skipped): " + e.getMessage());
        }
    }

    private void ensureDatabase() throws Exception {
        String base = "jdbc:postgresql://" + TestInfraSettings.pgHost() + ":" + TestInfraSettings.pgPort() + "/";
        Properties p = new Properties();
        p.setProperty("user", TestInfraSettings.pgUser());
        p.setProperty("password", TestInfraSettings.pgPassword());
        p.setProperty("loginTimeout", "3");
        p.setProperty("connectTimeout", "3");

        // 1) 直连目标库
        try (Connection c = DriverManager.getConnection(base + TestInfraSettings.pgDb(), p)) {
            return;
        } catch (SQLException ignore) {
            // 目标库可能不存在，走维护库创建
        }
        // 2) 通过 postgres 维护库创建目标库
        try (Connection c = DriverManager.getConnection(base + "postgres", p);
             Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + TestInfraSettings.pgDb() + "'");
            if (!rs.next()) {
                st.execute("CREATE DATABASE \"" + TestInfraSettings.pgDb() + "\"");
            }
        }
        // 3) 再次校验目标库可连
        try (Connection c = DriverManager.getConnection(base + TestInfraSettings.pgDb(), p)) {
            return;
        }
    }

    private void pingRedis() throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(TestInfraSettings.redisHost(), TestInfraSettings.redisPort()), 1500);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            String pw = TestInfraSettings.redisPassword();
            if (pw != null && !pw.isBlank()) {
                writeResp(out, "AUTH", pw);
                expectSimple(in);
            }
            writeResp(out, "PING");
            String line = readLine(in);
            if (!"+PONG".equals(line)) {
                throw new IllegalStateException("redis unexpected response: " + line);
            }
        }
    }

    private static void writeResp(OutputStream out, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            sb.setLength(0);
            out.write(b);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    private static void expectSimple(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || !line.startsWith("+")) {
            throw new IllegalStateException("redis error: " + line);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        boolean cr = false;
        while ((c = in.read()) != -1) {
            if (c == '\n' && cr) {
                break;
            }
            if (c == '\r') {
                cr = true;
                continue;
            }
            cr = false;
            buf.write(c);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
