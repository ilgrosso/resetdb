/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package net.tirasa.ilgrosso.resetdb;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.datasource.DataSourceUtils;

public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private static ClassPathXmlApplicationContext ctx;

    private static void resetOracle(final Connection conn)
            throws Exception {

        final Statement statement = conn.createStatement();

        ResultSet resultSet = statement.executeQuery(
                "SELECT 'DROP VIEW ' || object_name || ';'"
                + " FROM user_objects WHERE object_type='VIEW'");
        final List<String> drops = new ArrayList<String>();
        while (resultSet.next()) {
            drops.add(resultSet.getString(1));
        }
        resultSet.close();

        for (String drop : drops) {
            statement.executeUpdate(drop.substring(0, drop.length() - 1));
        }
        drops.clear();

        resultSet = statement.executeQuery(
                "SELECT 'DROP INDEX ' || object_name || ';'"
                + " FROM user_objects WHERE object_type='INDEX'"
                + " AND object_name NOT LIKE 'SYS_%'");
        while (resultSet.next()) {
            drops.add(resultSet.getString(1));
        }
        resultSet.close();

        for (String drop : drops) {
            try {
                statement.executeUpdate(drop.substring(0, drop.length() - 1));
            } catch (SQLException e) {
                LOG.error("Could not perform: {}", drop);
            }
        }
        drops.clear();

        resultSet = statement.executeQuery(
                "SELECT 'DROP TABLE ' || table_name || ' CASCADE CONSTRAINTS;'"
                + " FROM all_TABLES WHERE owner='"
                + ((String) ctx.getBean("username")).toUpperCase() + "'");
        while (resultSet.next()) {
            drops.add(resultSet.getString(1));
        }
        resultSet.close();

        resultSet = statement.executeQuery(
                "SELECT 'DROP SEQUENCE ' || sequence_name || ';'"
                + " FROM user_SEQUENCES");
        while (resultSet.next()) {
            drops.add(resultSet.getString(1));
        }
        resultSet.close();

        for (String drop : drops) {
            statement.executeUpdate(drop.substring(0, drop.length() - 1));
        }

        statement.close();
        conn.close();
    }

    private static void resetPostgreSQL(final Connection conn)
            throws Exception {

        final Statement statement = conn.createStatement();

        final ResultSet resultSet = statement.executeQuery(
                "SELECT 'DROP TABLE ' || c.relname || ' CASCADE;' FROM pg_catalog.pg_class c "
                + "LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relkind IN ('r','') AND n.nspname NOT IN ('pg_catalog', 'pg_toast') "
                + "AND pg_catalog.pg_table_is_visible(c.oid)");
        final List<String> drops = new ArrayList<String>();
        while (resultSet.next()) {
            drops.add(resultSet.getString(1));
        }
        resultSet.close();

        for (String drop : drops) {
            statement.executeUpdate(drop.substring(0, drop.length() - 1));
        }

        statement.close();
    }

    private static void resetSQLServer(final Connection conn)
            throws SQLException {

        Statement statement = conn.createStatement();
        final ResultSet resultSet = statement.executeQuery(
                "SELECT sysobjects.name "
                + "FROM sysobjects "
                + "JOIN sysusers "
                + "ON sysobjects.uid = sysusers.uid "
                + "WHERE OBJECTPROPERTY(sysobjects.id, N'IsView') = 1");
        final List<String> drops = new ArrayList<String>();
        while (resultSet.next()) {
            drops.add("DROP VIEW " + resultSet.getString(1));
        }
        resultSet.close();
        statement.close();

        statement = conn.createStatement();
        for (String drop : drops) {
            statement.executeUpdate(drop);
        }
        statement.close();

        statement = conn.createStatement();
        statement.executeUpdate("EXEC sp_MSforeachtable \"DROP TABLE ?\"");
        statement.close();

        statement = conn.createStatement();
        statement.executeUpdate("EXEC sp_MSforeachtable \"ALTER TABLE ? NOCHECK CONSTRAINT all\"");
        statement.close();
    }

    public static void main(final String[] args) {
        ctx = new ClassPathXmlApplicationContext("applicationContext.xml");

        final DBMS dbms = ctx.getBean(DBMS.class);
        if (dbms == null) {
            throw new IllegalArgumentException("Could not find a valid DBMS bean");
        }

        final DataSource dataSource = ctx.getBean(DataSource.class);
        final Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            switch (dbms) {
                case ORACLE:
                    resetOracle(conn);
                    break;

                case POSTGRESQL:
                    resetPostgreSQL(conn);
                    break;

                case SQLSERVER:
                    resetSQLServer(conn);
                    break;

                default:
                    LOG.warn("Unsupported DBMS: {}", dbms);
            }
        } catch (Throwable t) {
            LOG.error("During execution", t);
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }

        LOG.info("Reset successfully done.");
    }
}
