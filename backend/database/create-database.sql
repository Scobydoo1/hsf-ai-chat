-- Run once in SQL Server Management Studio (SSMS) or Azure Data Studio.
-- Tables are created automatically by JPA (spring.jpa.hibernate.ddl-auto=update).
IF DB_ID('AiChatDB') IS NULL
    CREATE DATABASE AiChatDB;
GO
