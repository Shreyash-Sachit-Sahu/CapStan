package dev.capstan.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs which database the application actually reached.
 *
 * <p>Worth the twelve lines: three PostgreSQL servers listen on this machine,
 * and connecting to the wrong one does not fail obviously. PostgreSQL returns
 * the same "password authentication failed" for an unknown role as for a bad
 * password, so a wrong-port connection reads as a credentials bug and sends you
 * looking in the wrong place.
 */
@Component
@RequiredArgsConstructor
public class DbIdentityLogger {

    private static final Logger log = LoggerFactory.getLogger(DbIdentityLogger.class);

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void logDatabaseIdentity() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select inet_server_port(), current_database()")) {
            String url = connection.getMetaData().getURL();
            if (rs.next()) {
                log.info("DB identity: url={} inet_server_port={} current_database={}",
                        url, rs.getInt(1), rs.getString(2));
            }
        } catch (SQLException e) {
            log.error("DB identity probe failed", e);
        }
    }
}
