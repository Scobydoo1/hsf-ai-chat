# Spring Boot & JPA – Frequently Asked Questions

> Real technical notes for students, used as sample knowledge for the AI Chat app.

## What is Spring Boot?
Spring Boot is a framework on top of the Spring Framework that makes it fast to create stand-alone Java applications.
It provides "starters" (dependency bundles such as spring-boot-starter-web), auto-configuration that sets up beans based on
the libraries on the classpath, and an embedded web server (Tomcat by default), so the application runs with `java -jar`.

## What is the difference between @Controller and @RestController?
`@Controller` returns the name of a view (for example a Thymeleaf template) that is rendered to HTML.
`@RestController` is `@Controller` plus `@ResponseBody`: the return value is written directly to the HTTP response,
usually as JSON. Use @Controller for server-rendered pages and @RestController for APIs used by a separate front-end.

## What is JPA, and what is Hibernate?
JPA (Jakarta Persistence API) is the standard Java API for mapping objects to relational database tables.
Hibernate is the most widely used JPA implementation, and it is the default one in Spring Boot.
Spring Data JPA adds repositories: you write an interface that extends `JpaRepository<Entity, Id>`,
and Spring generates the implementation, including queries derived from method names such as `findByNameContaining`.

## What does spring.jpa.hibernate.ddl-auto do?
It controls what Hibernate does to the database schema at start-up:
- `none`: do nothing (recommended in production with a migration tool such as Flyway or Liquibase).
- `validate`: check that tables match the entities, fail if not.
- `update`: create missing tables and columns, never drop anything. Convenient for student projects.
- `create`: drop and recreate the schema at start-up.
- `create-drop`: like create, and drop the schema at shutdown. Useful for tests.

## Why do I get LazyInitializationException?
A lazy relationship (for example `@OneToMany`, which is lazy by default) was accessed after the database session closed.
Fixes: load the relationship in the query with `JOIN FETCH` or `@EntityGraph`, map the data to a DTO inside a
transactional service method, or (not recommended) keep `spring.jpa.open-in-view=true`.

## What is the N+1 query problem?
Loading a list of N entities and then one extra query per entity for a relationship gives N+1 queries.
It makes pages slow. Fix it with `JOIN FETCH`, `@EntityGraph(attributePaths = "...")` or batch fetching.

## javax or jakarta?
Spring Boot 3 and later use Jakarta EE 9+, so imports start with `jakarta.` (for example `jakarta.persistence.Entity`,
`jakarta.validation.constraints.NotBlank`). Code written for Spring Boot 2 uses `javax.` and must be changed.

## What is a Spring profile?
A profile is a named set of configuration. `application-prod.properties` is used only when the `prod` profile is active,
for example with the environment variable `SPRING_PROFILES_ACTIVE=prod`. A common pattern is a local database in the
default profile and a cloud database in the prod profile.

## What is CORS and why does my front-end get blocked?
Browsers block JavaScript from calling an API on a different origin (scheme + host + port) unless the API answers with
CORS headers such as `Access-Control-Allow-Origin`. In Spring Boot you allow an origin with `@CrossOrigin`, a
`WebMvcConfigurer.addCorsMappings` method, or a `CorsFilter` bean.

## What is RAG?
Retrieval-Augmented Generation. Before asking a language model, the application searches a knowledge base for the passages
most related to the question and puts them into the prompt. The model then answers from those passages and can cite them.
Typical steps: split documents into chunks, turn each chunk into an embedding vector, store the vectors, embed the question,
find the closest chunks with cosine similarity, and send them to the model with the question.

## What is an embedding?
An embedding is a list of numbers (a vector) that represents the meaning of a text. Texts with similar meaning have vectors
that point in similar directions, so their cosine similarity is close to 1. Embedding models are different from chat models:
for example `text-embedding-3-small` (OpenAI) or `gemini-embedding-001` (Google). Vectors from different embedding models
cannot be compared with each other.
