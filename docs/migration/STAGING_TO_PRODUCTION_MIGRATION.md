# EHRBase Database Schema Migration Report: Staging → Production

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Migration Architecture Overview](#migration-architecture-overview)
3. [MigrationStrategyConfig Analysis](#migrationstrategyconfig-analysis)
4. [Complete Migration Path Analysis](#complete-migration-path-analysis)
5. [Critical Migration: V15 — Data Tables Add Parent Num](#critical-migration-v15)
6. [beforeValidate.sql Callback Analysis](#beforevalidatesql-callback-analysis)
7. [ext Schema Migrations](#ext-schema-migrations)
8. [Staging Migration Plan](#staging-migration-plan)
9. [Production Migration Checklist](#production-migration-checklist)
10. [Verification Queries](#verification-queries)
11. [Rollback Strategy](#rollback-strategy)
12. [Risk Assessment & Recommendations](#risk-assessment--recommendations)

---

## 1. Executive Summary

EHRBase uses **Flyway 11.15.0** to manage database schema migrations across two PostgreSQL schemas: `ehr` (core clinical data) and `ext` (PostgreSQL extensions and custom functions). Migrations are applied sequentially on application startup via `MigrationStrategyConfig`, which orchestrates Flyway for both schemas independently.

The `ehr` schema has **24 versioned migrations** (V1–V24) plus a `beforeValidate.sql` callback. The `ext` schema has **4 versioned migrations** (V1–V4) plus its own `beforeValidate.sql` callback.

**Key Risk:** Migration **V15** (`data_tables_add_parent_num.sql`) is the heaviest migration — it adds `parent_num` and `num_cap` columns to 6 tables and backfills data in batches. For large databases, this can take significant time and should be monitored closely.

---

## 2. Migration Architecture Overview

### Flyway Configuration

| Property | Default Value | Description |
|---|---|---|
| `spring.flyway.ehr-schema` | `ehr` | Target schema for EHR migrations |
| `spring.flyway.ext-schema` | `ext` | Target schema for extension migrations |
| `spring.flyway.ehr-location` | `classpath:db/migration/ehr` | Migration scripts location (ehr) |
| `spring.flyway.ext-location` | `classpath:db/migration/ext` | Migration scripts location (ext) |
| `spring.flyway.ehr-strategy` | `MIGRATE` | Migration strategy for ehr schema |
| `spring.flyway.ext-strategy` | `MIGRATE` | Migration strategy for ext schema |
| `spring.flyway.user` | `ehrbase` | Flyway DB user (admin privileges) |
| `spring.flyway.password` | `ehrbase` | Flyway DB password |

### Database Roles

- **`ehrbase`** (admin): Used by Flyway for migrations. Has full DDL privileges.
- **`ehrbase_restricted`**: Used by the application at runtime. Has SELECT, INSERT, UPDATE, DELETE on tables in `ehr` schema and SELECT on sequences.

### Schema Layout

```
ehrbase (database)
├── ehr (schema) — Core clinical data tables, managed by Flyway
│   ├── flyway_schema_history — Migration tracking
│   ├── ehr — Electronic Health Records
│   ├── comp_version / comp_data — Composition versioning
│   ├── ehr_status_version / ehr_status_data — EHR status versioning
│   ├── ehr_folder_version / ehr_folder_data — Folder versioning
│   ├── audit_details, contribution, users, etc.
│   └── ehr_item_tag — Experimental tagging
├── ext (schema) — PostgreSQL extensions & custom aggregates
│   ├── flyway_schema_history — Migration tracking
│   ├── uuid-ossp extension
│   └── Custom aggregate functions (jsonb max/min/avg/sum, DV_ORDERED)
└── public (schema) — Restricted (CREATE revoked)
```

---

## 3. MigrationStrategyConfig Analysis

**File:** `configuration/src/main/java/org/ehrbase/configuration/config/flyway/MigrationStrategyConfig.java`

### Strategy Enum (`MigrationStrategy.java`)

Three strategies are available:

| Strategy | Behavior |
|---|---|
| `MIGRATE` | Runs pending migrations (default) |
| `VALIDATE` | Validates migration checksums without running migrations |
| `DISABLED` | Skips Flyway entirely for that schema |

### Execution Order

1. **ext schema first** — Runs with `baselineOnMigrate=true` and `baselineVersion="1"`. This is because the `ext` schema pre-existed before Flyway management was introduced. The baseline ensures Flyway treats V1 as already applied if the schema history table doesn't exist yet.
2. **ehr schema second** — Runs standard Flyway migration without baseline.

### Key Configuration Details

```java
// ext schema configuration
extStrategy.applyStrategy(setSchema(flyway, extSchema)
    .locations(extLocation)
    .baselineOnMigrate(true)      // Auto-baseline for pre-existing ext schema
    .baselineVersion("1")          // Baseline at V1
    .placeholders(Map.of("extSchema", extSchema))
    .load());

// ehr schema configuration
ehrStrategy.applyStrategy(setSchema(flyway, ehrSchema)
    .placeholders(Map.of("ehrSchema", ehrSchema))
    .locations(ehrLocation)
    .load());
```

### Common Settings (via `setSchema()`)

- `validateOnMigrate(true)` — Validates checksums of applied migrations
- `ignoreMigrationPatterns("*:Ignored")` — Ignores migrations marked as "Ignored" but does NOT ignore future migrations

### CLI Runner Alternative

For large databases, the CLI runner (`cli/src/main/java/org/ehrbase/cli/cmd/CliDataBaseCommand.java`) can execute Flyway migrations independently of application startup:

```bash
# Validate migrations without applying
java -jar ehrbase-cli.jar database --migration-validate

# Execute migrations
java -jar ehrbase-cli.jar database --migration-migrate

# Check database connectivity first
java -jar ehrbase-cli.jar database --check-connection --migration-migrate
```

This avoids startup timeouts when heavy migrations (like V15) are pending on large datasets.

---

## 4. Complete Migration Path Analysis

### ehr Schema Migrations (V1–V24)

#### Phase 1: Initial Schema (V1–V4)

| Version | File | Description | Impact |
|---|---|---|---|
| **V1** | `V1__ehr.sql` | Creates the foundational schema: `ehr`, `users`, `audit_details`, `contribution`, `stored_query`, `template_store`, `plugin`, `system`, `tenant` tables. Also creates `ehr_status`, `ehr_status_history`, `ehr_folder`, `ehr_folder_history`, `comp`, `comp_history` tables with Row-Level Security (RLS) and multi-tenancy. | **Heavy** — Full schema creation |
| **V2** | `V2__tenant.sql` | Inserts default tenant record. | Lightweight |
| **V3** | `V3__locatable.sql` | Creates `ehr_status`/`ehr_folder`/`comp` tables and their `_history` variants with full column sets including `entity_path`, `entity_idx`, RLS policies, and indexes. | **Heavy** — Major table creation |
| **V4** | `V4__drop_comp_data_idx.sql` | Drops `comp_data_idx` index. | Lightweight |

#### Phase 2: Multi-Tenancy Removal (V5.1–V5.4)

| Version | File | Description | Impact |
|---|---|---|---|
| **V5.1** | `V5_1__remove_multi_tenancy.sql` | Drops all foreign keys referencing `tenant` table, drops tenant table, drops FK references to `sys_tenant`, removes indexes using `sys_tenant`, disables RLS, drops RLS policies. | **Heavy** — Extensive DDL |
| **V5.2** | `V5_2__remove_multi_tenancy.sql` | Replaces all primary keys to remove `sys_tenant` from composite PKs. | Moderate |
| **V5.3** | `V5_3__remove_multi_tenancy.sql` | Drops `sys_tenant` column from all tables. Recreates foreign keys without tenant. | Moderate |
| **V5.4** | `V5_4__remove_multi_tenancy.sql` | Recreates indexes (`contribution_ehr_idx`, `template_store_id_unq`, `users_username_idx`). | Lightweight |

#### Phase 3: Version Table Refactoring (V6.1–V6.4)

| Version | File | Description | Impact |
|---|---|---|---|
| **V6.1** | `V6_1__version_tables.sql` | Creates new version tables: `comp_version`, `comp_version_history`, `ehr_status_version`, `ehr_status_version_history`, `ehr_folder_version`, `ehr_folder_version_history`. | Moderate |
| **V6.2** | `V6_2__version_tables.sql` | Populates version tables by copying data from original tables (`WHERE num = 0`). | **Heavy on large DBs** — Full table scans |
| **V6.3** | `V6_3__version_tables.sql` | Adds FKs to version tables. Renames original tables to `*_data`/`*_data_history`. Drops redundant columns from data tables. Adds cascading FK from data to version tables. | **Heavy** — Extensive DDL restructuring |
| **V6.4** | `V6_4__version_tables.sql` | Creates performance indexes on `comp_data`, `comp_version`, and `ehr_status_data`. | Moderate |

#### Phase 4: Index Optimization & Schema Refinements (V7–V14)

| Version | File | Description | Impact |
|---|---|---|---|
| **V7** | `V7__comp_rmobject_index.sql` | Creates `comp_data_leaf_idx` index on `comp_data(vo_id, entity_idx)`. | Lightweight |
| **V8** | `V8__vo_data_indexes.sql` | Drops old indexes, creates new `comp_data_idx` and `ehr_status_data_idx` with optimized column ordering. | Moderate |
| **V9.1** | `V9_1__add_root_concept_to_comp_version.sql` | Adds `root_concept` column to `comp_version` and `comp_version_history`. | Lightweight |
| **V9.2** | `V9_2__add_root_concept_to_comp_version.sql` | Backfills `root_concept` from `comp_data`/`comp_data_history` where `num = 0`. | **Heavy on large DBs** — Full table scan |
| **V9.3** | `V9_3__add_root_concept_to_comp_version.sql` | Sets `root_concept` NOT NULL. Creates `comp_version_root_concept_idx`. | Lightweight |
| **V10** | `V10__ehr_status_subject_aql_idx.sql` | Recreates `ehr_status_subject_idx` with JSONB operator-based expressions. | Moderate |
| **V11** | `V11__drop_system.sql` | Drops `system_id` column from `audit_details` and drops `system` table. | Lightweight |
| **V12** | `V12__drop_template_index.sql` | Drops `comp_version_template_idx`. | Lightweight |
| **V13** | `V13__refactor_audit_details.sql` | Drops `contribution.state` column and type. Adds `audit_details.target_type` column. Backfills target_type from version tables. Creates time-ordered indexes. | **Moderate-Heavy** — Backfill updates |
| **V14** | `V14__index_for_ehr_time_created.sql` | Creates `ehr_time_created_idx` index on `ehr(creation_date DESC, id ASC)`. | Lightweight |

#### Phase 5: Critical Data Migration (V15)

| Version | File | Description | Impact |
|---|---|---|---|
| **V15** | `V15__data_tables_add_parent_num.sql` | **HEAVIEST MIGRATION** — Adds `parent_num` and `num_cap` columns to 6 tables, backfills in batches, creates new indexes, drops legacy columns. See [detailed analysis below](#critical-migration-v15). | **CRITICAL — Longest running** |

#### Phase 6: Feature Additions & Fixes (V16–V24)

| Version | File | Description | Impact |
|---|---|---|---|
| **V16** | `V16__ehr_item_tag_table.sql` | Creates `ehr_item_tag` table and index for experimental tagging feature. | Lightweight |
| **V17** | `V17__add_item_uuid_array_column.sql` | Adds `item_uuids` column to `ehr_folder_data`/`ehr_folder_data_history`. Migrates JSONB array data to native UUID arrays. | **Moderate-Heavy** — Data transformation |
| **V18** | `V18__fix_ehr_status_subject_aql_idx.sql` | NOOP — deferred to V20. | None |
| **V19** | `V19__index_for_ehr_folder_version.sql` | Creates unique indexes on `ehr_folder_version(vo_id)` and `ehr_folder_version_history(vo_id)`. | Lightweight |
| **V20** | `V20__fix_ehr_status_subject_aql_idx.sql` | Recreates `ehr_status_subject_idx` with corrected WHERE clause (`num = 0` instead of `rm_entity = 'ES'`). | Moderate |
| **V21** | `V21__add_contribution_indexes.sql` | Adds HASH indexes on `contribution_id` for all `*_version_history` tables. | Lightweight |
| **V22** | `V22__add_missing_contribution_indexes.sql` | Adds HASH indexes on `contribution_id` for all `*_version` tables. | Lightweight |
| **V23** | `V23__add_path_skipping_index.sql` | Drops old indexes, creates optimized `comp_data_path_idx` and `comp_data_path_skip_idx` using `parent_num`/`num_cap`. | Moderate |
| **V24** | `V24__fix_delete_date.sql` | Fixes `sys_period_lower` on deleted version history records to match predecessor's `sys_period_upper`. | Moderate — conditional updates |

---

## 5. Critical Migration: V15 — Data Tables Add Parent Num {#critical-migration-v15}

**File:** `jooq-pg/src/main/resources/db/migration/ehr/V15__data_tables_add_parent_num.sql`
**Lines:** 289

### What It Does

V15 introduces hierarchical navigation columns (`parent_num` and `num_cap`) to optimize AQL path queries. It touches **6 tables**:

| Table | Batch Size | ID Expression |
|---|---|---|
| `ehr_folder_data_history` | 100 | `ehr_id` |
| `ehr_folder_data` | 1,000 | `ehr_id` |
| `ehr_status_data_history` | 10,000 | `ehr_id` |
| `ehr_status_data` | 10,000 | `ehr_id` |
| `comp_data_history` | 1,000 | `vo_id` |
| `comp_data` | 1,000 | `vo_id` |

### Execution Phases

#### Phase A: Schema Preparation
1. Creates temporary type `pg_temp.mig_num_type` and functions for calculating parent/cap numbers
2. **Adds columns** `parent_num` (default 0) and `num_cap` (default -1) to all 6 tables using `ADD COLUMN IF NOT EXISTS`
3. **Creates temporary partial indexes** to efficiently find unmigrated rows (`WHERE num_cap = -1 AND num = 0`)

#### Phase B: Batch Backfill
For each table, the `mig_num_columns` procedure:
1. Reads a batch of rows where `num = 0 AND num_cap = -1 AND sys_version = 1` (unmigrated root rows)
2. Joins to fetch all related rows for those root entities
3. Calculates `parent_num` and `num_cap` using `mig_calc_nums()` — a PL/pgSQL function that walks the entity hierarchy
4. Updates the rows in batch
5. **Commits after each batch** — this means partial progress is preserved if the migration is interrupted
6. Logs progress with `RAISE NOTICE` statements showing row counts and timing

#### Phase C: Post-Migration Cleanup
1. Creates new permanent indexes using the new columns:
   - `ehr_status_data_path_idx` on `ehr_status_data`
   - `comp_data_path_idx` on `comp_data`
2. Drops column defaults
3. **Drops legacy columns**: `entity_idx_cap`, `entity_path`, `entity_path_cap` from all 6 tables
4. Drops temporary migration indexes

### Performance Characteristics

- **Batch processing with COMMIT**: Each batch is committed separately, allowing monitoring of progress and preventing transaction bloat
- **Batch sizes vary by table**: Smaller batches (100) for `ehr_folder_data_history` (likely largest), larger (10,000) for `ehr_status_data` (likely smallest)
- **Temporary indexes**: Created specifically to speed up the migration query (`WHERE num_cap = -1 AND num = 0`)
- **Progress logging**: Each batch logs timing via `RAISE NOTICE`, visible in PostgreSQL logs

### Monitoring During V15

Watch PostgreSQL logs for progress messages like:
```
NOTICE: Starting migration for ehr_folder_data_history
NOTICE: [ehr_folder_data_history] read 100 in 00:00:00:150
NOTICE: [ehr_folder_data_history] updated 100 in 00:00:00:200
...
NOTICE: [ehr_folder_data_history] read 0. Finished pre-migration for ehr_folder_data_history! in 00:00:00:050
```

### Estimated Duration

Duration depends heavily on data volume:
- **Small DB** (< 10K compositions): Minutes
- **Medium DB** (100K–1M compositions): 10–60 minutes
- **Large DB** (> 1M compositions): Hours — **use CLI runner** to avoid startup timeout

---

## 6. beforeValidate.sql Callback Analysis {#beforevalidatesql-callback-analysis}

### ehr/beforeValidate.sql

This callback runs **before every Flyway validation** — including on every application startup. Its purpose is to **patch checksums** in `ehr.flyway_schema_history` for migration scripts that were modified after their initial release.

**Patched versions:** V1, V2, V3, V4, V5.1–V5.4, V6.1–V6.4, V7, V8, V9.1–V9.3, V10, V11, V12, V13, V14, V15, V18, V23

Each entry maps `(version, new_checksum, [old_checksums])`. If a recorded checksum matches any old checksum, it's updated to the current one. This is **expected behavior** — it prevents Flyway validation errors when previously-applied migration scripts have been updated (e.g., for copyright header changes, whitespace fixes, or bug fixes).

**Important:** Do NOT be alarmed by checksum updates in the logs — they are intentional and idempotent.

### ext/beforeValidate.sql

Same pattern for the `ext` schema. Patches checksums for ext V1–V4.

---

## 7. ext Schema Migrations

| Version | File | Description |
|---|---|---|
| **V1** | `V1__baseline.sql` | Empty baseline (schema pre-existed before Flyway). With `baselineOnMigrate=true`, this is automatically marked as applied. |
| **V2** | `V2__aggregate_functions.sql` | Creates custom jsonb aggregate functions: `max(jsonb)`, `min(jsonb)`, `avg(jsonb)`, `sum(jsonb)`. |
| **V3** | `V3__dv_ordered_aggregate_functions.sql` | Creates DV_ORDERED-aware aggregates: `jsonb_dv_ordered_magnitude()`, `max_dv_ordered(jsonb)`, `min_dv_ordered(jsonb)`. |
| **V4** | `V4__create_missing_collation.sql` | Creates `en_US` ICU collation if missing — ensures portability across OS locales. |

All ext migrations are lightweight (function/type definitions only, no data migration).

---

## 8. Staging Migration Plan

### Prerequisites

- [ ] **Ticket 1 complete**: Production database backup verified and accessible for rollback
- [ ] PostgreSQL 15+ on staging environment
- [ ] `uuid-ossp` extension available
- [ ] Database created with `createdb.sql` or `createdb-docker.sql` schema

### Step-by-Step Staging Plan

#### Step 1: Clone Production Database to Staging

```bash
# Option A: pg_dump/pg_restore
pg_dump -Fc -h prod-host -U ehrbase ehrbase > ehrbase_prod_backup.dump
pg_restore -h staging-host -U postgres -d ehrbase_staging ehrbase_prod_backup.dump

# Option B: Use cloud provider snapshot (AWS RDS, Azure, etc.)
# Create a read replica or snapshot restore to staging instance
```

#### Step 2: Verify Staging Database State

```sql
-- Check current migration state
SELECT version, description, type, installed_on, success
FROM ehr.flyway_schema_history
ORDER BY installed_rank;

SELECT version, description, type, installed_on, success
FROM ext.flyway_schema_history
ORDER BY installed_rank;

-- Record current table sizes for comparison
SELECT relname, n_live_tup
FROM pg_stat_user_tables
WHERE schemaname = 'ehr'
ORDER BY n_live_tup DESC;
```

#### Step 3: Deploy EHRBase to Staging

**Option A: Standard Application Startup** (for smaller databases)

Deploy the target EHRBase version with default configuration:
```yaml
spring:
  flyway:
    ehr-strategy: MIGRATE
    ext-strategy: MIGRATE
```

Flyway will auto-run all pending migrations for both schemas on startup.

**Option B: CLI Runner** (recommended for large databases)

```bash
# First validate the migration state
java -jar ehrbase-cli.jar database --check-connection --migration-validate

# Then execute migrations (won't time out like app startup)
java -jar ehrbase-cli.jar database --migration-migrate

# Then start the application with VALIDATE to skip re-running
java -jar ehrbase.jar --spring.flyway.ehr-strategy=VALIDATE --spring.flyway.ext-strategy=VALIDATE
```

#### Step 4: Monitor Migration Progress

```bash
# Watch PostgreSQL logs for V15 progress
tail -f /var/log/postgresql/postgresql-*.log | grep -E "NOTICE|ERROR|WARNING"
```

Expected log output during V15:
```
NOTICE: Starting migration for ehr_folder_data_history
NOTICE: [ehr_folder_data_history] read 100 in 00:00:00:XXX
NOTICE: [ehr_folder_data_history] updated 100 in 00:00:00:XXX
...
NOTICE: [comp_data] read 0. Finished pre-migration for comp_data! in 00:00:00:XXX
```

#### Step 5: Verify Staging Migration

Run the verification queries from [Section 10](#verification-queries).

#### Step 6: Run Application Health Checks

```bash
# Start the application (if using CLI runner approach)
java -jar ehrbase.jar --spring.flyway.ehr-strategy=VALIDATE --spring.flyway.ext-strategy=VALIDATE

# Health check
curl -s http://staging:8080/ehrbase/management/health | jq .

# Basic API smoke test
curl -s http://staging:8080/ehrbase/rest/openehr/v1/definition/template/adl1.4 \
  -u ehrbase-user:SuperSecretPassword
```

---

## 9. Production Migration Checklist

### Pre-Migration

- [ ] Staging migration completed successfully (all `success = true`)
- [ ] Application health checks pass on staging
- [ ] Production backup from Ticket 1 verified and accessible
- [ ] Maintenance window communicated to stakeholders
- [ ] Database monitoring in place (connections, locks, disk I/O, replication lag)
- [ ] Application instances shut down / drained

### Migration Execution

- [ ] **Stop all EHRBase application instances** pointing to production database
- [ ] Record current `flyway_schema_history` state (both schemas)
- [ ] **For large databases**: Use CLI runner to execute migrations
  ```bash
  java -jar ehrbase-cli.jar database --check-connection --migration-migrate
  ```
- [ ] **For smaller databases**: Start single application instance with `MIGRATE` strategy
- [ ] Monitor V15 progress via PostgreSQL logs
- [ ] Wait for all migrations to complete

### Post-Migration

- [ ] Run all [verification queries](#verification-queries)
- [ ] Start application instances with `VALIDATE` strategy for initial restart.
  **Important:** The `post-migrate` profile must be combined with a datasource profile:
  ```bash
  # Option A: Combine post-migrate with your environment profile
  java -jar ehrbase.jar --spring.profiles.active=docker,post-migrate

  # Option B: Set strategy via environment variables
  SPRING_FLYWAY_EHR_STRATEGY=VALIDATE SPRING_FLYWAY_EXT_STRATEGY=VALIDATE java -jar ehrbase.jar
  ```
- [ ] Verify application health: `GET /ehrbase/management/health`
- [ ] Verify API functionality with smoke tests
- [ ] Monitor application logs for errors
- [ ] Consider keeping `VALIDATE` mode permanently (see [recommendations](#risk-assessment--recommendations))

### Sign-Off

- [ ] All verification queries pass
- [ ] Application serves requests normally
- [ ] No errors in application or database logs
- [ ] Stakeholders notified of successful migration

---

## 10. Verification Queries {#verification-queries}

### Flyway Schema History Validation

```sql
-- All ehr migrations should show success = true
SELECT version, description, type, checksum, installed_on, execution_time, success
FROM ehr.flyway_schema_history
ORDER BY installed_rank;

-- Expected: 24 versioned entries (V1 through V24), all success = true
SELECT COUNT(*) AS total_migrations,
       COUNT(*) FILTER (WHERE success = true) AS successful,
       COUNT(*) FILTER (WHERE success = false) AS failed
FROM ehr.flyway_schema_history
WHERE type = 'SQL';

-- All ext migrations should show success = true
SELECT version, description, type, checksum, installed_on, execution_time, success
FROM ext.flyway_schema_history
ORDER BY installed_rank;
```

### V15 Completion Verification

```sql
-- Verify parent_num and num_cap columns exist and are populated
-- No rows should have the default values after migration
SELECT 'comp_data' AS table_name,
       COUNT(*) FILTER (WHERE num_cap = -1) AS unmigrated_rows,
       COUNT(*) AS total_rows
FROM ehr.comp_data
UNION ALL
SELECT 'comp_data_history',
       COUNT(*) FILTER (WHERE num_cap = -1),
       COUNT(*)
FROM ehr.comp_data_history
UNION ALL
SELECT 'ehr_status_data',
       COUNT(*) FILTER (WHERE num_cap = -1),
       COUNT(*)
FROM ehr.ehr_status_data
UNION ALL
SELECT 'ehr_status_data_history',
       COUNT(*) FILTER (WHERE num_cap = -1),
       COUNT(*)
FROM ehr.ehr_status_data_history
UNION ALL
SELECT 'ehr_folder_data',
       COUNT(*) FILTER (WHERE num_cap = -1),
       COUNT(*)
FROM ehr.ehr_folder_data
UNION ALL
SELECT 'ehr_folder_data_history',
       COUNT(*) FILTER (WHERE num_cap = -1),
       COUNT(*)
FROM ehr.ehr_folder_data_history;

-- All unmigrated_rows should be 0
```

### Legacy Column Removal Verification

```sql
-- Verify legacy columns were dropped by V15
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = 'ehr'
  AND column_name IN ('entity_idx_cap', 'entity_path', 'entity_path_cap')
  AND table_name IN ('comp_data', 'comp_data_history',
                     'ehr_status_data', 'ehr_status_data_history',
                     'ehr_folder_data', 'ehr_folder_data_history');
-- Expected: 0 rows (all legacy columns dropped)
```

### Index Verification

```sql
-- Verify key indexes exist after migration
SELECT indexname, tablename
FROM pg_indexes
WHERE schemaname = 'ehr'
  AND indexname IN (
    'ehr_status_data_path_idx',     -- Created by V15
    'comp_data_path_idx',           -- Recreated by V23
    'comp_data_path_skip_idx',      -- Created by V23
    'ehr_time_created_idx',         -- Created by V14
    'comp_version_root_concept_idx', -- Created by V9.3
    'ehr_status_subject_idx',       -- Recreated by V20
    'ehr_folder_version_vo_id_idx', -- Created by V19
    'comp_version_contribution_idx', -- Created by V22
    'ehr_item_tag_ehr_id_target_vo_id_idx' -- Created by V16
  )
ORDER BY tablename, indexname;
```

### Temporary Migration Index Cleanup Verification

```sql
-- These temporary indexes should NOT exist after migration
SELECT indexname
FROM pg_indexes
WHERE schemaname = 'ehr'
  AND indexname LIKE 'mig_%';
-- Expected: 0 rows
```

### Data Consistency Check (from db_scripts)

```sql
-- Check for inconsistent EHR_STATUS (multiple vo_ids for same ehr_id)
SET search_path = ehr;
(
    SELECT 'Inconsistent EHR_STATUS found'
    FROM ehr_status_version_history root
    LEFT JOIN ehr_status_version_history vh
         ON root.ehr_id = vh.ehr_id
        AND root.vo_id <> vh.vo_id
    LEFT JOIN ehr_status_version v
         ON root.ehr_id = v.ehr_id
        AND root.vo_id <> v.vo_id
    WHERE root.sys_version = 1
       AND (v.vo_id IS NOT NULL OR vh.vo_id IS NOT NULL)
    LIMIT 1
)
UNION
(
    SELECT 'Inconsistent FOLDER found'
    FROM ehr_folder_version_history root
    LEFT JOIN ehr_folder_version_history vh
         ON root.ehr_id = vh.ehr_id
        AND root.ehr_folders_idx = vh.ehr_folders_idx
        AND root.vo_id <> vh.vo_id
    LEFT JOIN ehr_folder_version v
         ON root.ehr_id = v.ehr_id
        AND root.ehr_folders_idx = v.ehr_folders_idx
        AND root.vo_id <> v.vo_id
    WHERE root.sys_version = 1
      AND (v.vo_id IS NOT NULL OR vh.vo_id IS NOT NULL)
    LIMIT 1
);
-- Expected: 0 rows
```

---

## 11. Rollback Strategy

### Before V15 Completes

If the migration fails **during** V15:
- V15 uses **batch commits**, so partial progress is saved
- Rows with `num_cap = -1` are still unmigrated
- **Option A**: Fix the issue and restart the application — V15 will pick up where it left off (the migration will re-run from the beginning as Flyway sees it as pending, but the `IF NOT EXISTS` guards on columns and indexes make it safe)
- **Option B**: Restore from backup (Ticket 1)

### After All Migrations Complete

If the application fails after migration:
1. **Restore from backup**: Use the production backup from Ticket 1
   ```bash
   # Stop all application instances
   # Restore database
   pg_restore -h prod-host -U postgres --clean --if-exists -d ehrbase ehrbase_backup.dump
   # Restart with the previous EHRBase version
   ```
2. **Point of no return**: Once the application is running and accepting clinical data post-migration, a rollback requires more careful handling as new data would be lost

### Important Notes on V15 Rollback

V15 **drops columns** (`entity_idx_cap`, `entity_path`, `entity_path_cap`) in its post-migration phase. This means:
- Rolling back V15 requires a full database restore — you cannot simply "undo" the column drops
- This is why the Ticket 1 backup is critical

---

## 12. Risk Assessment & Recommendations

### Risk Matrix

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| V15 timeout on app startup | Medium (large DBs) | High | Use CLI runner for large databases |
| Checksum validation failure | Low | Medium | `beforeValidate.sql` handles this automatically |
| Disk space exhaustion during V15 | Low-Medium | High | Monitor disk I/O; V15 creates temp indexes and does batch updates |
| Lock contention during V15 | Low | Medium | Run during maintenance window with no other connections |
| ext baseline conflict | Low | Low | `baselineOnMigrate=true` handles pre-existing ext schema |

### Recommendations

1. **Use the CLI runner for production** to avoid startup timeouts during V15
2. **Switch to VALIDATE mode after migration** to prevent accidental re-runs:
   ```yaml
   spring:
     flyway:
       ehr-strategy: VALIDATE
       ext-strategy: VALIDATE
   ```
3. **Monitor PostgreSQL logs during V15** — the batch progress messages are your primary visibility tool
4. **Run the data consistency check** (`ehrbase_2.7.0_check_ehr_status_and_folder_void.sql`) before migration to catch any pre-existing data issues
5. **Ensure adequate disk space** — V15 creates temporary indexes and the batch updates generate WAL
6. **Set `statement_timeout` appropriately** — V15's individual statements should complete in reasonable time, but the overall migration can run long
7. **Document the Flyway version used** (11.15.0) — mixing Flyway versions can cause issues
8. **Keep the backup accessible** for at least 1 week post-migration as a safety net

---

## Appendix: File Reference

| File | Path |
|---|---|
| MigrationStrategyConfig | `configuration/src/main/java/org/ehrbase/configuration/config/flyway/MigrationStrategyConfig.java` |
| MigrationStrategy enum | `configuration/src/main/java/org/ehrbase/configuration/config/flyway/MigrationStrategy.java` |
| CLI Runner | `cli/src/main/java/org/ehrbase/cli/cmd/CliDataBaseCommand.java` |
| Application Config | `configuration/src/main/resources/application.yml` |
| ehr migrations | `jooq-pg/src/main/resources/db/migration/ehr/` |
| ext migrations | `jooq-pg/src/main/resources/db/migration/ext/` |
| DB creation script | `createdb.sql` |
| Docker DB creation | `createdb-docker.sql` |
| Data consistency check | `db_scripts/ehrbase_2.7.0_check_ehr_status_and_folder_void.sql` |
