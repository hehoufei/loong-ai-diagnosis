package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.config.DynamicDataSource;
import cn.aimstek.loong.aidiag.config.EnvConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * 动态数据源生命周期管理：
 *
 * <ul>
 *   <li>启动时根据当前激活环境的 JDBC 配置初始化连接池；
 *       若环境未配置 JDBC，则回退到 application.yml 中的默认 spring.datasource。</li>
 *   <li>对外暴露 {@link #switchTo(EnvConfig.EnvItem)}，
 *       在切换环境时原子替换底层 HikariDataSource 并关闭旧连接池，避免连接泄漏。</li>
 *   <li>切换操作 synchronized，保证多线程并发安全。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceManager {

    private final DynamicDataSource dynamicDataSource;
    private final DataSourceProperties dataSourceProperties;
    private final EnvConfig envConfig;

    @PostConstruct
    public void init() {
        EnvConfig.EnvItem active = envConfig.getActiveEnvItem();
        if (hasJdbc(active)) {
            applyEnv(active, "启动时使用环境数据源");
        } else {
            applyDefault("启动时使用 application.yml 默认数据源");
        }
    }

    /**
     * 根据环境配置切换底层 DataSource。
     * 若环境未配置 JDBC，则回退到 application.yml 默认连接。
     */
    public synchronized void switchTo(EnvConfig.EnvItem env) {
        if (hasJdbc(env)) {
            applyEnv(env, "切换环境数据源");
        } else {
            applyDefault("环境未配置 JDBC，回退到默认数据源");
        }
    }

    private void applyEnv(EnvConfig.EnvItem env, String reason) {
        HikariDataSource ds = buildHikari(env.getJdbcUrl(), env.getDbUsername(), env.getDbPassword());
        replace(ds);
        log.info("{}: env={}, url={}", reason, env.getName(), env.getJdbcUrl());
    }

    private void applyDefault(String reason) {
        HikariDataSource ds = buildHikari(
                dataSourceProperties.getUrl(),
                dataSourceProperties.getUsername(),
                dataSourceProperties.getPassword());
        replace(ds);
        log.info("{}: url={}", reason, dataSourceProperties.getUrl());
    }

    private void replace(DataSource newDs) {
        DataSource old = dynamicDataSource.getDelegate();
        dynamicDataSource.setDelegate(newDs);
        closeQuietly(old);
    }

    private HikariDataSource buildHikari(String url, String user, String pwd) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        if (user != null) ds.setUsername(user);
        if (pwd != null) ds.setPassword(pwd);
        if (dataSourceProperties.getDriverClassName() != null) {
            ds.setDriverClassName(dataSourceProperties.getDriverClassName());
        }
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setPoolName("loong-ai-dyn-" + System.currentTimeMillis());
        // 启动期不要 fail-fast：DB 不可达时也允许应用启动，
        // 由各 JDBC 调用在运行期自行处理失败，避免整个应用首屏卡住。
        ds.setInitializationFailTimeout(-1);
        // 启动期不要主动验证连接，避免占用 30s connectionTimeout。
        ds.setConnectionTimeout(5_000);
        ds.setValidationTimeout(3_000);
        return ds;
    }

    private boolean hasJdbc(EnvConfig.EnvItem env) {
        return env != null && env.getJdbcUrl() != null && !env.getJdbcUrl().isBlank();
    }

    private void closeQuietly(DataSource ds) {
        if (ds instanceof HikariDataSource hikari) {
            try {
                hikari.close();
            } catch (Exception e) {
                log.warn("关闭旧 HikariDataSource 失败: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        closeQuietly(dynamicDataSource.getDelegate());
    }
}
