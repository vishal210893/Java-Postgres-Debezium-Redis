-- Ensure PostgreSQL has logical replication support for Debezium
-- Tables are created by Hibernate ddl-auto, this handles any extra setup
ALTER SYSTEM SET wal_level = 'logical';
