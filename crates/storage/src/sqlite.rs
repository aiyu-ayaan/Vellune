//! SQLite-backed local-first store for pages, blocks, and page links.

use std::path::Path;
use std::sync::{Mutex, MutexGuard};

use chrono::{DateTime, SecondsFormat, Utc};
use rusqlite::{params, Connection, OptionalExtension, Row};
use uuid::Uuid;
use vellune_document::{Block, Document};
use vellune_domain::{Page, PageId, WorkspaceId};

use crate::{DocumentRepository, StorageError};

/// Ordered schema migrations; index + 1 is the `PRAGMA user_version` after applying it.
const MIGRATIONS: &[&str] = &["
    CREATE TABLE local_pages (
        id           TEXT PRIMARY KEY NOT NULL,
        workspace_id TEXT NOT NULL,
        title        TEXT NOT NULL,
        slug         TEXT NOT NULL,
        created_at   TEXT NOT NULL,
        updated_at   TEXT NOT NULL
    );
    CREATE INDEX local_pages_workspace ON local_pages (workspace_id);

    CREATE TABLE local_blocks (
        id          TEXT PRIMARY KEY NOT NULL,
        page_id     TEXT NOT NULL REFERENCES local_pages (id) ON DELETE CASCADE,
        parent_id   TEXT,
        kind        TEXT NOT NULL,
        content     TEXT NOT NULL,
        order_index REAL NOT NULL
    );
    CREATE INDEX local_blocks_page ON local_blocks (page_id, order_index);

    -- target_id has no foreign key: links to deleted pages are kept as broken links.
    CREATE TABLE local_page_links (
        source_id TEXT NOT NULL REFERENCES local_pages (id) ON DELETE CASCADE,
        target_id TEXT NOT NULL,
        PRIMARY KEY (source_id, target_id)
    );
    CREATE INDEX local_page_links_target ON local_page_links (target_id);
"];

/// Local SQLite database. One connection behind a mutex; SQLite serializes writes anyway.
pub struct SqliteStore {
    conn: Mutex<Connection>,
}

impl SqliteStore {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StorageError> {
        let conn =
            Connection::open(path).map_err(|e| StorageError::ConnectionFailed(e.to_string()))?;
        Self::init(conn)
    }

    pub fn open_in_memory() -> Result<Self, StorageError> {
        let conn = Connection::open_in_memory()
            .map_err(|e| StorageError::ConnectionFailed(e.to_string()))?;
        Self::init(conn)
    }

    fn init(mut conn: Connection) -> Result<Self, StorageError> {
        conn.pragma_update(None, "foreign_keys", true)
            .map_err(internal)?;
        migrate(&mut conn)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    fn conn(&self) -> Result<MutexGuard<'_, Connection>, StorageError> {
        self.conn
            .lock()
            .map_err(|_| StorageError::Internal("connection mutex poisoned".into()))
    }

    pub fn list_pages(&self, workspace_id: &WorkspaceId) -> Result<Vec<Page>, StorageError> {
        let conn = self.conn()?;
        let mut stmt = conn
            .prepare(
                "SELECT id, workspace_id, title, slug, created_at, updated_at
                 FROM local_pages WHERE workspace_id = ?1 ORDER BY title, id",
            )
            .map_err(internal)?;
        let rows = stmt
            .query_map([workspace_id.to_string()], page_from_row)
            .map_err(internal)?;
        rows.map(|r| r.map_err(internal)?).collect()
    }

    /// Delete a page with its blocks and outgoing links.
    pub fn delete_page(&self, page_id: &PageId) -> Result<(), StorageError> {
        self.conn()?
            .execute(
                "DELETE FROM local_pages WHERE id = ?1",
                [page_id.to_string()],
            )
            .map_err(internal)?;
        Ok(())
    }

    /// Replace all outgoing links of `source` in one transaction.
    pub fn set_page_links(&self, source: &PageId, targets: &[PageId]) -> Result<(), StorageError> {
        let mut conn = self.conn()?;
        let tx = conn.transaction().map_err(internal)?;
        tx.execute(
            "DELETE FROM local_page_links WHERE source_id = ?1",
            [source.to_string()],
        )
        .map_err(internal)?;
        {
            let mut insert = tx
                .prepare(
                    "INSERT OR IGNORE INTO local_page_links (source_id, target_id) VALUES (?1, ?2)",
                )
                .map_err(internal)?;
            for target in targets {
                insert
                    .execute([source.to_string(), target.to_string()])
                    .map_err(internal)?;
            }
        }
        tx.commit().map_err(internal)
    }

    pub fn get_backlinks(&self, target: &PageId) -> Result<Vec<PageId>, StorageError> {
        self.query_ids(
            "SELECT source_id FROM local_page_links WHERE target_id = ?1 ORDER BY source_id",
            target,
        )
    }

    pub fn get_forward_links(&self, source: &PageId) -> Result<Vec<PageId>, StorageError> {
        self.query_ids(
            "SELECT target_id FROM local_page_links WHERE source_id = ?1 ORDER BY target_id",
            source,
        )
    }

    fn query_ids(&self, sql: &str, id: &PageId) -> Result<Vec<PageId>, StorageError> {
        let conn = self.conn()?;
        let mut stmt = conn.prepare(sql).map_err(internal)?;
        let rows = stmt
            .query_map([id.to_string()], |row| row.get::<_, String>(0))
            .map_err(internal)?;
        rows.map(|r| parse_uuid(&r.map_err(internal)?)).collect()
    }
}

impl DocumentRepository for SqliteStore {
    fn get_page(&self, page_id: &PageId) -> Result<Option<Page>, StorageError> {
        self.conn()?
            .query_row(
                "SELECT id, workspace_id, title, slug, created_at, updated_at FROM local_pages WHERE id = ?1",
                [page_id.to_string()],
                page_from_row,
            )
            .optional()
            .map_err(internal)?
            .transpose()
    }

    fn save_page(&self, page: &Page) -> Result<(), StorageError> {
        // Upsert, not INSERT OR REPLACE: a replace deletes the row and cascades to its blocks.
        self.conn()?
            .execute(
                "INSERT INTO local_pages (id, workspace_id, title, slug, created_at, updated_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT (id) DO UPDATE SET
                     workspace_id = excluded.workspace_id, title = excluded.title, slug = excluded.slug,
                     created_at = excluded.created_at, updated_at = excluded.updated_at",
                params![
                    page.id.to_string(),
                    page.workspace_id.to_string(),
                    page.title,
                    page.slug,
                    format_time(&page.created_at),
                    format_time(&page.updated_at),
                ],
            )
            .map_err(internal)?;
        Ok(())
    }

    fn get_document(&self, page_id: &PageId) -> Result<Option<Document>, StorageError> {
        let conn = self.conn()?;
        let exists = conn
            .query_row(
                "SELECT 1 FROM local_pages WHERE id = ?1",
                [page_id.to_string()],
                |_| Ok(()),
            )
            .optional()
            .map_err(internal)?;
        if exists.is_none() {
            return Ok(None);
        }
        let mut stmt = conn
            .prepare(
                "SELECT id, page_id, parent_id, kind, content, order_index
                 FROM local_blocks WHERE page_id = ?1 ORDER BY order_index, rowid",
            )
            .map_err(internal)?;
        let rows = stmt
            .query_map([page_id.to_string()], block_from_row)
            .map_err(internal)?;
        let blocks = rows
            .map(|r| r.map_err(internal)?)
            .collect::<Result<_, _>>()?;
        Ok(Some(Document {
            page_id: *page_id,
            blocks,
        }))
    }

    // shortcut: rewrites every block of the page per save; switch to per-operation writes
    // once editor transactions are persisted through the CRDT/pending-operation queue.
    fn save_document(&self, doc: &Document) -> Result<(), StorageError> {
        if let Some(b) = doc.blocks.iter().find(|b| b.page_id != doc.page_id) {
            return Err(StorageError::Internal(format!(
                "block {} belongs to page {}, not {}",
                b.id, b.page_id, doc.page_id
            )));
        }
        let mut conn = self.conn()?;
        let tx = conn.transaction().map_err(internal)?;
        tx.execute(
            "DELETE FROM local_blocks WHERE page_id = ?1",
            [doc.page_id.to_string()],
        )
        .map_err(internal)?;
        {
            let mut insert = tx
                .prepare(
                    "INSERT INTO local_blocks (id, page_id, parent_id, kind, content, order_index)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                )
                .map_err(internal)?;
            for b in &doc.blocks {
                let kind = serde_json::to_string(&b.kind)
                    .map_err(|e| StorageError::Serialization(e.to_string()))?;
                insert
                    .execute(params![
                        b.id.to_string(),
                        b.page_id.to_string(),
                        b.parent_id.map(|p| p.to_string()),
                        kind,
                        b.content,
                        b.order_index,
                    ])
                    .map_err(internal)?;
            }
        }
        tx.commit().map_err(internal)
    }
}

fn migrate(conn: &mut Connection) -> Result<(), StorageError> {
    let version: i64 = conn
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .map_err(internal)?;
    if version > MIGRATIONS.len() as i64 {
        return Err(StorageError::Internal(format!(
            "database schema v{version} is newer than supported v{}",
            MIGRATIONS.len()
        )));
    }
    for (i, sql) in MIGRATIONS.iter().enumerate().skip(version as usize) {
        let tx = conn.transaction().map_err(internal)?;
        tx.execute_batch(sql).map_err(internal)?;
        tx.pragma_update(None, "user_version", i as i64 + 1)
            .map_err(internal)?;
        tx.commit().map_err(internal)?;
    }
    Ok(())
}

// Row mappers return a nested Result so parse failures surface as StorageError, not rusqlite errors.
fn page_from_row(row: &Row) -> rusqlite::Result<Result<Page, StorageError>> {
    let (id, ws, title, slug, created, updated): (String, String, String, String, String, String) = (
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
    );
    Ok((|| {
        Ok(Page {
            id: parse_uuid(&id)?,
            workspace_id: parse_uuid(&ws)?,
            title,
            slug,
            created_at: parse_time(&created)?,
            updated_at: parse_time(&updated)?,
        })
    })())
}

fn block_from_row(row: &Row) -> rusqlite::Result<Result<Block, StorageError>> {
    let (id, page_id, parent_id, kind, content, order_index): (
        String,
        String,
        Option<String>,
        String,
        String,
        f64,
    ) = (
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
    );
    Ok((|| {
        Ok(Block {
            id: parse_uuid(&id)?,
            page_id: parse_uuid(&page_id)?,
            parent_id: parent_id.as_deref().map(parse_uuid).transpose()?,
            kind: serde_json::from_str(&kind)
                .map_err(|e| StorageError::Serialization(e.to_string()))?,
            content,
            order_index,
        })
    })())
}

fn internal(e: rusqlite::Error) -> StorageError {
    StorageError::Internal(e.to_string())
}

fn parse_uuid(s: &str) -> Result<Uuid, StorageError> {
    Uuid::parse_str(s).map_err(|e| StorageError::Serialization(format!("bad uuid {s:?}: {e}")))
}

fn format_time(t: &DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::AutoSi, true)
}

fn parse_time(s: &str) -> Result<DateTime<Utc>, StorageError> {
    DateTime::parse_from_rfc3339(s)
        .map(|t| t.with_timezone(&Utc))
        .map_err(|e| StorageError::Serialization(format!("bad timestamp {s:?}: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use vellune_document::BlockKind;

    fn page(workspace_id: WorkspaceId, title: &str) -> Page {
        let now = Utc::now();
        Page {
            id: Uuid::new_v4(),
            workspace_id,
            title: title.into(),
            slug: title.to_lowercase(),
            created_at: now,
            updated_at: now,
        }
    }

    fn block(page_id: PageId, kind: BlockKind, order: f64) -> Block {
        Block {
            id: Uuid::new_v4(),
            page_id,
            parent_id: None,
            kind,
            content: format!("c{order}"),
            order_index: order,
        }
    }

    #[test]
    fn page_and_document_round_trip() {
        let store = SqliteStore::open_in_memory().unwrap();
        let ws = Uuid::new_v4();
        let p = page(ws, "Note");
        store.save_page(&p).unwrap();
        assert_eq!(store.get_page(&p.id).unwrap(), Some(p.clone()));

        let mut child = block(p.id, BlockKind::Checklist { checked: true }, 1.0);
        let parent = block(
            p.id,
            BlockKind::EmbeddedPage {
                target_page_id: Uuid::new_v4(),
            },
            0.5,
        );
        child.parent_id = Some(parent.id);
        let doc = Document {
            page_id: p.id,
            blocks: vec![child.clone(), parent.clone()],
        };
        store.save_document(&doc).unwrap();
        assert_eq!(
            store.get_document(&p.id).unwrap().unwrap().blocks,
            vec![parent, child]
        );

        store
            .save_document(&Document {
                page_id: p.id,
                blocks: vec![],
            })
            .unwrap();
        assert!(store
            .get_document(&p.id)
            .unwrap()
            .unwrap()
            .blocks
            .is_empty());
        assert_eq!(store.list_pages(&ws).unwrap(), vec![p]);
    }

    #[test]
    fn missing_page_and_orphan_document() {
        let store = SqliteStore::open_in_memory().unwrap();
        let id = Uuid::new_v4();
        assert_eq!(store.get_page(&id).unwrap(), None);
        assert_eq!(store.get_document(&id).unwrap(), None);
        let orphan = Document {
            page_id: id,
            blocks: vec![block(id, BlockKind::Paragraph, 1.0)],
        };
        assert!(
            store.save_document(&orphan).is_err(),
            "foreign key must reject blocks without a page"
        );

        let p = page(Uuid::new_v4(), "P");
        store.save_page(&p).unwrap();
        let wrong = Document {
            page_id: p.id,
            blocks: vec![block(id, BlockKind::Paragraph, 1.0)],
        };
        assert!(store.save_document(&wrong).is_err());
    }

    #[test]
    fn renaming_page_keeps_blocks() {
        let store = SqliteStore::open_in_memory().unwrap();
        let mut p = page(Uuid::new_v4(), "Old");
        store.save_page(&p).unwrap();
        store
            .save_document(&Document {
                page_id: p.id,
                blocks: vec![block(p.id, BlockKind::Paragraph, 1.0)],
            })
            .unwrap();
        p.title = "New".into();
        store.save_page(&p).unwrap();
        assert_eq!(store.get_page(&p.id).unwrap().unwrap().title, "New");
        assert_eq!(store.get_document(&p.id).unwrap().unwrap().blocks.len(), 1);
    }

    #[test]
    fn links_and_cascading_delete() {
        let store = SqliteStore::open_in_memory().unwrap();
        let ws = Uuid::new_v4();
        let (a, b, c) = (page(ws, "A"), page(ws, "B"), page(ws, "C"));
        for p in [&a, &b, &c] {
            store.save_page(p).unwrap();
        }
        store
            .save_document(&Document {
                page_id: a.id,
                blocks: vec![block(a.id, BlockKind::Paragraph, 1.0)],
            })
            .unwrap();
        store.set_page_links(&a.id, &[b.id, c.id, b.id]).unwrap();
        store.set_page_links(&c.id, &[b.id]).unwrap();
        let mut expected = vec![a.id, c.id];
        expected.sort();
        assert_eq!(store.get_backlinks(&b.id).unwrap(), expected);

        store.set_page_links(&a.id, &[c.id]).unwrap();
        assert_eq!(store.get_forward_links(&a.id).unwrap(), vec![c.id]);

        store.delete_page(&a.id).unwrap();
        assert_eq!(store.get_document(&a.id).unwrap(), None);
        assert!(store.get_backlinks(&c.id).unwrap().is_empty());
        assert_eq!(store.get_backlinks(&b.id).unwrap(), vec![c.id]);
    }

    #[test]
    fn reopening_file_keeps_data_and_rejects_newer_schema() {
        let dir = std::env::temp_dir().join(format!("vellune-storage-{}", Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("vault.sqlite");
        let p = page(Uuid::new_v4(), "Persisted");
        SqliteStore::open(&path).unwrap().save_page(&p).unwrap();
        assert_eq!(
            SqliteStore::open(&path).unwrap().get_page(&p.id).unwrap(),
            Some(p)
        );

        Connection::open(&path)
            .unwrap()
            .pragma_update(None, "user_version", 99)
            .unwrap();
        assert!(SqliteStore::open(&path).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
