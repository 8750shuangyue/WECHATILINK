package com.example.demo.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableTransactionManagement
public class SQLiteConfig {

    private static final Logger logger = LoggerFactory.getLogger(SQLiteConfig.class);

    @Configuration
    @EnableJpaRepositories(
            basePackages = {
                "com.example.demo.chat.repository.mysql", "com.example.demo.care.repository",
                "com.example.demo.push.repository", "com.example.demo.inventory.repository",
                "com.example.demo.timeline.repository", "com.example.demo.community.repository"
            },
            entityManagerFactoryRef = "entityManagerFactory",
            transactionManagerRef = "transactionManager"
    )
    public static class MySQLConfig {

        @Value("${spring.datasource.url:jdbc:mysql://localhost:3306/ilink_chat?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}")
        private String mysqlUrl;

        @Value("${spring.datasource.username:root}")
        private String mysqlUsername;

        @Value("${spring.datasource.password:}")
        private String mysqlPassword;

        @Bean(name = "mysqlDataSource")
        @Primary
        public DataSource mysqlDataSource() {
            logger.info("========== Initializing MySQL DataSource ==========");
            return DataSourceBuilder.create()
                    .driverClassName("com.mysql.cj.jdbc.Driver")
                    .url(mysqlUrl)
                    .username(mysqlUsername)
                    .password(mysqlPassword)
                    .build();
        }

        @Bean(name = "entityManagerFactory")
        @Primary
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(
                @Qualifier("mysqlDataSource") DataSource dataSource) {
            
            logger.info("========== Creating MySQL EntityManagerFactory ==========");
            
            LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
            em.setDataSource(dataSource);
            em.setPackagesToScan(
                "com.example.demo.chat.entity", "com.example.demo.care.model",
                "com.example.demo.push.entity", "com.example.demo.inventory.entity",
                "com.example.demo.timeline.entity", "com.example.demo.community.entity"
            );
            
            HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
            em.setJpaVendorAdapter(vendorAdapter);
            
            Map<String, Object> properties = new HashMap<>();
            properties.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
            properties.put("hibernate.hbm2ddl.auto", "update");
            properties.put("hibernate.show_sql", "false");
            properties.put("hibernate.format_sql", "true");
            em.setJpaPropertyMap(properties);
            
            return em;
        }

        @Bean(name = "mysqlJdbcTemplate")
        @Primary
        public JdbcTemplate mysqlJdbcTemplate(@Qualifier("mysqlDataSource") DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean(name = "transactionManager")
        @Primary
        public PlatformTransactionManager transactionManager(
                @Qualifier("entityManagerFactory") LocalContainerEntityManagerFactoryBean entityManagerFactory) {
            
            logger.info("========== Creating MySQL TransactionManager ==========");
            JpaTransactionManager transactionManager = new JpaTransactionManager();
            transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
            return transactionManager;
        }
    }

    @Configuration
    @EnableJpaRepositories(
            basePackages = {"com.example.demo.chat.repository.sqlite"},
            entityManagerFactoryRef = "sqliteEntityManagerFactory",
            transactionManagerRef = "sqliteTransactionManager"
    )
    public static class SQLiteDbConfig {

        @Value("${spring.datasource.secondary.url:jdbc:sqlite:rag_knowledge.sqlite}")
        private String sqliteUrl;

        @Bean(name = "sqliteDataSource")
        public DataSource sqliteDataSource() {
            logger.info("========== Initializing SQLite DataSource ==========");
            logger.info("SQLite URL: {}", sqliteUrl);
            
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(sqliteUrl);
            dataSource.setDriverClassName("org.sqlite.JDBC");
            dataSource.setMaximumPoolSize(1);
            dataSource.setMinimumIdle(1);
            dataSource.setIdleTimeout(60000);
            dataSource.setConnectionTimeout(3000);
            dataSource.setMaxLifetime(1800000);
            dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;");
            
            return dataSource;
        }

        @Bean(name = "sqliteEntityManagerFactory")
        public LocalContainerEntityManagerFactoryBean sqliteEntityManagerFactory(
                @Qualifier("sqliteDataSource") DataSource dataSource) {
            
            logger.info("========== Creating SQLite EntityManagerFactory ==========");
            
            LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
            em.setDataSource(dataSource);
            em.setPackagesToScan("com.example.demo.chat.entity.sqlite");
            
            HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
            em.setJpaVendorAdapter(vendorAdapter);
            
            Map<String, Object> properties = new HashMap<>();
            properties.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
            properties.put("hibernate.hbm2ddl.auto", "update");
            properties.put("hibernate.show_sql", "false");
            em.setJpaPropertyMap(properties);
            
            return em;
        }

        @Bean(name = "sqliteTransactionManager")
        public PlatformTransactionManager sqliteTransactionManager(
                @Qualifier("sqliteEntityManagerFactory") LocalContainerEntityManagerFactoryBean entityManagerFactory) {
            
            logger.info("========== Creating SQLite TransactionManager ==========");
            JpaTransactionManager transactionManager = new JpaTransactionManager();
            transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
            return transactionManager;
        }

        @Bean(name = "sqliteJdbcTemplate")
        public JdbcTemplate sqliteJdbcTemplate(@Qualifier("sqliteDataSource") DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
