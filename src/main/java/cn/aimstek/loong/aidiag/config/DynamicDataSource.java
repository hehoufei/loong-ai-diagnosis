package cn.aimstek.loong.aidiag.config;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 委托式动态数据源：所有 JDBC 调用都转发给 {@link #delegate}，
 * 通过 {@link #setDelegate(DataSource)} 在运行期原子地替换底层连接池，
 * 即可让 {@code JdbcTemplate} 等下游组件随环境切换而连接到不同数据库，
 * 无需重启应用。
 *
 * <p>注意：本类只是一个轻量代理，连接池的真正生命周期由
 * {@code DataSourceManager} 负责维护（创建/关闭）。
 */
public class DynamicDataSource extends AbstractDataSource {

    /** volatile 保证多线程下的可见性；切换是原子引用替换。*/
    private volatile DataSource delegate;

    public void setDelegate(DataSource newDelegate) {
        this.delegate = newDelegate;
    }

    public DataSource getDelegate() {
        return this.delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        DataSource ds = this.delegate;
        if (ds == null) {
            throw new SQLException("DynamicDataSource 尚未初始化，无可用的底层 DataSource");
        }
        return ds.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        DataSource ds = this.delegate;
        if (ds == null) {
            throw new SQLException("DynamicDataSource 尚未初始化，无可用的底层 DataSource");
        }
        return ds.getConnection(username, password);
    }
}
