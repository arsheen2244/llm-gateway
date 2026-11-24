package dev.llmgateway.gateway.usage.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads usage totals from PostgreSQL through a small pool of its own (design note 40).
 *
 * <p>The pool connects on first use, so the gateway starts without the database. It is private
 * to this class: nothing on the chat path can come to depend on it. Every wait is bounded.
 */
public class JdbcUsageReader implements UsageReader, AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    public JdbcUsageReader(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("usage-report");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setReadOnly(true);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);                // no connections until someone asks
        config.setInitializationFailTimeout(-1); // start even if the database is down
        config.setConnectionTimeout(2000);       // ms to wait for a free connection
        config.addDataSourceProperty("connectTimeout", "2"); // seconds
        config.addDataSourceProperty("socketTimeout", "5");  // seconds: a hung database is an error
        this.dataSource = new HikariDataSource(config);
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public List<DailyUsage> daily(String clientId, LocalDate from, LocalDate to) {
        try {
            return jdbc.sql("""
                            SELECT day, requests, successful_requests, rate_limited_requests, failed_requests,
                                   cache_hits, prompt_tokens, completion_tokens, cached_prompt_tokens,
                                   cached_completion_tokens, cost_usd, unpriced_requests
                              FROM usage_daily
                             WHERE client_id = :clientId AND day BETWEEN :from AND :to
                             ORDER BY day""")
                    .param("clientId", clientId)
                    .param("from", from)
                    .param("to", to)
                    .query(JdbcUsageReader::day)
                    .list();
        } catch (DataAccessException e) {
            throw new UsageUnavailableException("Could not read usage", e);
        }
    }

    private static DailyUsage day(ResultSet row, int n) throws SQLException {
        return new DailyUsage(row.getObject("day", LocalDate.class), new UsageTotals(
                row.getLong("requests"),
                row.getLong("successful_requests"),
                row.getLong("rate_limited_requests"),
                row.getLong("failed_requests"),
                row.getLong("cache_hits"),
                row.getLong("prompt_tokens"),
                row.getLong("completion_tokens"),
                row.getLong("cached_prompt_tokens"),
                row.getLong("cached_completion_tokens"),
                new PlainDecimal(row.getBigDecimal("cost_usd")),
                row.getLong("unpriced_requests")));
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
