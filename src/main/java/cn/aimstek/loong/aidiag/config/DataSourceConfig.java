package cn.aimstek.loong.aidiag.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 动态数据源 Bean 注册。
 *
 * <p>显式以 {@link Primary} 形式暴露 {@link DynamicDataSource}，
 * 取代 Spring Boot 默认创建的 HikariDataSource，
 * 这样 {@code JdbcTemplate} 自动装配以及其他组件注入到的都是动态代理；
 * 真正的 Hikari 连接池在 {@code DataSourceManager} 中按当前环境延迟构造。</p>
 *
 * <p>{@link DataSourceProperties} 仍由 Spring Boot 自动装配，
 * 用于在环境未配置 JDBC 时回退到 application.yml 中的默认连接信息。</p>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DynamicDataSource dataSource() {
        return new DynamicDataSource();
    }
}
