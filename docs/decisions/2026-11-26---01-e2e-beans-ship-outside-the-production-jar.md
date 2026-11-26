# The e2e stack's beans ship in an add-on jar, never in the production jar

**Status:** current

## Context

The e2e stack needs beans production must never run: a seeder that writes mock node definitions,
templates and repositories at boot, a resolver that points server-started runs at mock copies of
the Story Design and Task Delivery graphs, and an endpoint that expires one run's artifacts on
demand. They lived in `src/main` behind `@Profile("e2e")`, so every production jar carried them, and
one environment variable was all that separated a production server from seeding test data into its
database. The profile also leaked into production code that had nothing to do with e2e:
`DefaultTemplateGraphIdResolver` was `@Profile("!e2e")`, and `RoadmapOnlyTemplates` named the mock
copies' graph ids. Because `src/main` was where such beans went, new e2e-only code kept being added
there.

## Decision

1. **E2e-only code builds from its own source set.** `api-server/src/e2e/java` compiles against
   `main` into a separate jar (`e2eJar`). It is a Gradle feature variant, the mechanism
   `testFixtures` uses, so a downstream build can depend on it by capability
   (`com.choruskube:api-server-e2e`) and package it beside its own e2e code. The source set declares
   no dependency of its own: its beans run on exactly the classpath the bootJar ships.
2. **The e2e image runs the production bootJar with the add-on beside it.** `Dockerfile.e2e` copies
   the unchanged bootJar and the `e2eAddOn` directory, and starts Spring Boot's `PropertiesLauncher`
   with `loader.path` pointing at that directory. The launcher adds those jars to the application
   class loader, so component scanning finds the e2e beans as it did when they sat in `src/main`. The
   published `Dockerfile` and its `java -jar` entrypoint are unchanged.
3. **`@Profile("e2e")` stays on every e2e bean** as a second lock: an add-on jar loaded without the
   profile still seeds nothing.
4. **Production code no longer names the e2e profile.** `DefaultTemplateGraphIdResolver` is
   registered unconditionally and `E2eTemplateGraphIdResolver` is `@Primary` over it.
   `RoadmapOnlyTemplates` refuses a standalone start of whatever template the resolver maps a
   roadmap-only graph to, instead of listing the mock copies by name.
5. **A test fails the build on e2e code in production.** `E2eCodeGuard` (test fixtures) scans the
   classes of the code source a production class was loaded from and fails on a class in an `e2e`
   package or a class or member whose `@Profile` names `e2e`, negated or not.
   `E2eCodeStaysOutOfProductionTest` runs it over this repo's main classes; a downstream build runs
   it over its own.

## Rejected alternatives

- **Keep the beans in `src/main` behind the profile.** No build change, but every production
  artifact keeps shipping code that writes test data, guarded by one environment variable.
- **A separate process that runs only the seeders.** It cannot host the resolver or the endpoint,
  which must live in the API server. It would also seed from outside the server's boot sequence, so
  rows would be written before or after the server's own boot-time steps (migrations, ownership
  reconciliation) instead of in their fixed order.
- **A second, e2e-only fat jar that bundles the e2e beans.** The e2e stack would then test a jar no
  deployment runs.
- **Make `PropertiesLauncher` the bootJar's launcher, so production and e2e run the same command.**
  That changes how every production server starts, and lets a `LOADER_PATH` variable or a
  `loader.properties` file add classes to it, to save the e2e image one entrypoint line.
