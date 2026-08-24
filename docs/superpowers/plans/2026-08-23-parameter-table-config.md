# Parameter Table Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a typed exact-table-name configuration list for parameter tables and excluded tables, and apply it to comparisons.

**Architecture:** Add a JPA entity/repository/controller backed by the existing SQL initialization script. Resolve enabled configuration rows in `CompareController` and merge them with the existing request filters; add a static UI view for CRUD maintenance using the existing navigation and API helper patterns.

**Tech Stack:** Spring Boot 3, Spring Data JPA, PostgreSQL, static HTML/CSS/JavaScript, JUnit.

---

### Task 1: Persist table configuration

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/web/entity/CompareTableConfig.java`
- Create: `src/main/java/com/lotus/gausscmp/web/repository/CompareTableConfigRepository.java`
- Modify: `src/main/resources/schema.sql`

- [ ] Add entity fields, enum-like string type validation, timestamps, and unique table/type constraint.
- [ ] Add SQL table, comments, and index for type/enabled filtering.
- [ ] Add repository methods for ordered listing and enabled type lookup.

### Task 2: Add CRUD API

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/web/TableConfigController.java`
- Create: `src/test/java/com/lotus/gausscmp/web/TableConfigControllerTest.java`

- [ ] Implement list, create, update, toggle, and delete endpoints under `/api/table-configs`.
- [ ] Validate nonblank exact table names and `PARAMETER`/`EXCLUDE` types; translate duplicate names into a clear 400 error.
- [ ] Test validation and list/toggle behavior with mocked repository.

### Task 3: Apply config to comparison

**Files:**
- Modify: `src/main/java/com/lotus/gausscmp/web/CompareController.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/CompareService.java`
- Modify: `src/test/java/com/lotus/gausscmp/web/CompareControllerTest.java`

- [ ] Inject the config repository and resolve enabled parameter/exclude names for every compare request.
- [ ] Use parameter names as the data whitelist when configured and merge configured excludes with request excludes, with excludes winning.
- [ ] Preserve current behavior when no parameter rows are configured.
- [ ] Add focused tests for whitelist and exclude precedence.

### Task 4: Add maintenance UI

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/app.css`

- [ ] Add a “表配置” navigation item and view with type tabs, search, table, modal form, and CRUD actions.
- [ ] Load and mutate `/api/table-configs` using existing `api`, `showMsg`, modal, and escaping helpers.
- [ ] Keep mobile layout responsive and avoid page-level horizontal overflow.

### Task 5: Verify

- [ ] Run `mvn test -q`.
- [ ] Run the application and verify CRUD plus 1280px/375px screenshots.
- [ ] Run `git diff --check` and review only task-related changes.
