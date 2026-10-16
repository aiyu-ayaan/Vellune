//! Page <-> Yjs document mapping for native clients; a port of `packages/ui/src/cloud/ydoc.ts`.
//!
//! The web and Android clients must write the same Y.Doc shape so their updates merge:
//! `meta` (Y.Map of page fields), `blocks` (Y.Array of Y.Map, each with a Y.Text `text`),
//! `canvasItems` and `strokes` (Y.Map of plain JSON objects keyed by id). Text offsets are UTF-16
//! code units, as in JavaScript.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use serde_json::{Map, Number, Value};
use yrs::types::ToJson;
use yrs::updates::decoder::Decode;
use yrs::{
    Any, Array, ArrayRef, Doc, GetString, Map as YMap, MapPrelim, MapRef, OffsetKind, Options, Out, ReadTxn, StateVector,
    Text, TextPrelim, TextRef, Transact, TransactionMut, Update,
};

mod jni_api;

// `favorite` is deliberately absent: favorites are per person, not part of the shared page.
// `mode` ("edgeless") remembers which face a page opens on.
const META_KEYS: [&str; 13] = [
    "title", "icon", "tags", "aliases", "journal", "extraFrontmatter", "createdAt", "updatedAt", "deletedAt", "parentId", "folder", "order", "mode",
];
const BLOCK_KEYS: [&str; 7] = ["kind", "level", "checked", "language", "color", "src", "indent"];

pub fn new_doc() -> Doc {
    Doc::with_options(Options { offset_kind: OffsetKind::Utf16, ..Options::default() })
}

pub fn apply_update(doc: &Doc, update: &[u8]) -> Result<(), String> {
    let u = Update::decode_v1(update).map_err(|e| e.to_string())?;
    doc.transact_mut().apply_update(u).map_err(|e| e.to_string())
}

pub fn encode_state(doc: &Doc) -> Vec<u8> {
    doc.transact().encode_state_as_update_v1(&StateVector::default())
}

pub fn merge_updates(updates: &[&[u8]]) -> Result<Vec<u8>, String> {
    yrs::merge_updates_v1(updates).map_err(|e| e.to_string())
}

// ---------- JSON <-> Any ----------

fn to_any(v: &Value) -> Any {
    match v {
        Value::Null => Any::Null,
        Value::Bool(b) => Any::Bool(*b),
        // Whole numbers go out as Yjs varints, like the web client writes them.
        Value::Number(n) => Any::Number(match n.as_i64() {
            Some(i) => yrs::any::Number::Int(i),
            None => yrs::any::Number::Float(n.as_f64().unwrap_or(0.0)),
        }),
        Value::String(s) => Any::String(s.as_str().into()),
        Value::Array(a) => Any::Array(a.iter().map(to_any).collect::<Vec<_>>().into()),
        Value::Object(o) => Any::Map(Arc::new(o.iter().map(|(k, v)| (k.clone(), to_any(v))).collect::<HashMap<_, _>>())),
    }
}

fn from_any(a: &Any) -> Value {
    match a {
        Any::Null | Any::Undefined => Value::Null,
        Any::Bool(b) => Value::Bool(*b),
        Any::Number(yrs::any::Number::Int(n)) => Value::Number((*n).into()),
        Any::Number(yrs::any::Number::Float(n)) => num(*n),
        Any::String(s) => Value::String(s.to_string()),
        Any::Buffer(_) => Value::Null,
        Any::Array(a) => Value::Array(a.iter().map(from_any).collect()),
        Any::Map(m) => Value::Object(m.iter().map(|(k, v)| (k.clone(), from_any(v))).collect()),
    }
}

/// Whole numbers come back as integers so `createdAt` round-trips as `1712345678901`, not `1.712e12`.
fn num(n: f64) -> Value {
    if n.fract() == 0.0 && n.abs() < 9.0e15 {
        Value::Number((n as i64).into())
    } else {
        Number::from_f64(n).map(Value::Number).unwrap_or(Value::Null)
    }
}

fn out_json<T: ReadTxn>(txn: &T, o: &Out) -> Value {
    match o {
        Out::Any(a) => from_any(a),
        other => from_any(&other.to_json(txn)),
    }
}

fn same(a: Option<Value>, b: &Value) -> bool {
    a.as_ref() == Some(b)
}

// ---------- page -> doc ----------

/// Minimal splice turning `ytext` into `next` (common prefix/suffix), so concurrent typing merges.
fn set_text(txn: &mut TransactionMut, ytext: &TextRef, next: &str) {
    let prev = ytext.get_string(txn);
    if prev == next {
        return;
    }
    let p: Vec<u16> = prev.encode_utf16().collect();
    let n: Vec<u16> = next.encode_utf16().collect();
    let mut start = 0;
    while start < p.len() && start < n.len() && p[start] == n[start] {
        start += 1;
    }
    // Never split a surrogate pair.
    if start > 0 && (0xD800..0xDC00).contains(&n.get(start - 1).copied().unwrap_or(0)) {
        start -= 1;
    }
    let (mut end_p, mut end_n) = (p.len(), n.len());
    while end_p > start && end_n > start && p[end_p - 1] == n[end_n - 1] {
        end_p -= 1;
        end_n -= 1;
    }
    if end_n < n.len() && (0xDC00..0xE000).contains(&n[end_n]) {
        end_n += 1;
        end_p += 1;
    }
    if end_p > start {
        ytext.remove_range(txn, start as u32, (end_p - start) as u32);
    }
    if end_n > start {
        ytext.insert(txn, start as u32, &String::from_utf16_lossy(&n[start..end_n]));
    }
}

fn block_id<T: ReadTxn>(txn: &T, ym: &MapRef) -> Option<String> {
    match ym.get(txn, "id") {
        Some(Out::Any(Any::String(s))) => Some(s.to_string()),
        _ => None,
    }
}

fn insert_block(txn: &mut TransactionMut, yblocks: &ArrayRef, index: u32, b: &Map<String, Value>) {
    let ym: MapRef = yblocks.insert(txn, index, MapPrelim::default());
    if let Some(id) = b.get("id") {
        ym.insert(txn, "id", to_any(id));
    }
    for k in BLOCK_KEYS {
        if let Some(v) = b.get(k).filter(|v| !v.is_null()) {
            ym.insert(txn, k, to_any(v));
        }
    }
    let text = b.get("text").and_then(Value::as_str).unwrap_or_default();
    ym.insert(txn, "text", TextPrelim::new(text));
}

fn sync_keyed(txn: &mut TransactionMut, ymap: &MapRef, items: &[Value]) {
    let ids: HashSet<&str> = items.iter().filter_map(|i| i.get("id").and_then(Value::as_str)).collect();
    let stale: Vec<String> = ymap.keys(txn).filter(|k| !ids.contains(k)).map(str::to_string).collect();
    for k in stale {
        ymap.remove(txn, &k);
    }
    for i in items {
        let Some(id) = i.get("id").and_then(Value::as_str) else { continue };
        let current = ymap.get(txn, id).map(|o| out_json(txn, &o));
        if !same(current, i) {
            ymap.insert(txn, id, to_any(i));
        }
    }
}

/// Applies a full page snapshot (the web `Page` JSON) as the smallest set of Yjs operations and
/// returns the resulting update (empty when nothing changed).
pub fn apply_page(doc: &Doc, page: &Value) -> Vec<u8> {
    let meta = doc.get_or_insert_map("meta");
    let yblocks = doc.get_or_insert_array("blocks");
    let items = doc.get_or_insert_map("canvasItems");
    let strokes = doc.get_or_insert_map("strokes");
    let props = doc.get_or_insert_map("props");
    let mut txn = doc.transact_mut();

    if let Some(id) = page.get("id") {
        if !same(meta.get(&txn, "id").map(|o| out_json(&txn, &o)), id) {
            meta.insert(&mut txn, "id", to_any(id));
        }
    }
    for k in META_KEYS {
        match page.get(k).filter(|v| !v.is_null()) {
            None => {
                if meta.get(&txn, k).is_some() {
                    meta.remove(&mut txn, k);
                }
            }
            Some(v) => {
                if !same(meta.get(&txn, k).map(|o| out_json(&txn, &o)), v) {
                    meta.insert(&mut txn, k, to_any(v));
                }
            }
        }
    }

    let empty = Vec::new();
    let blocks: Vec<&Map<String, Value>> =
        page.get("blocks").and_then(Value::as_array).unwrap_or(&empty).iter().filter_map(Value::as_object).collect();
    let want: HashSet<&str> = blocks.iter().filter_map(|b| b.get("id").and_then(Value::as_str)).collect();
    let mut i = yblocks.len(&txn);
    while i > 0 {
        i -= 1;
        let keep = match yblocks.get(&txn, i) {
            Some(Out::YMap(m)) => block_id(&txn, &m).is_some_and(|id| want.contains(id.as_str())),
            _ => false,
        };
        if !keep {
            yblocks.remove_range(&mut txn, i, 1);
        }
    }
    for (i, b) in blocks.iter().enumerate() {
        let i = i as u32;
        let id = b.get("id").and_then(Value::as_str).unwrap_or_default();
        let ym = match yblocks.get(&txn, i) {
            Some(Out::YMap(m)) if block_id(&txn, &m).as_deref() == Some(id) => m,
            _ => {
                let len = yblocks.len(&txn);
                let found = (i + 1..len).find(|&j| matches!(yblocks.get(&txn, j), Some(Out::YMap(m)) if block_id(&txn, &m).as_deref() == Some(id)));
                // shortcut: a moved block is re-inserted, so a concurrent edit to it during the move is lost.
                if let Some(j) = found {
                    yblocks.remove_range(&mut txn, j, 1);
                }
                insert_block(&mut txn, &yblocks, i, b);
                continue;
            }
        };
        for k in BLOCK_KEYS {
            match b.get(k).filter(|v| !v.is_null()) {
                None => {
                    if ym.get(&txn, k).is_some() {
                        ym.remove(&mut txn, k);
                    }
                }
                Some(v) => {
                    if !same(ym.get(&txn, k).map(|o| out_json(&txn, &o)), v) {
                        ym.insert(&mut txn, k, to_any(v));
                    }
                }
            }
        }
        let text = b.get("text").and_then(Value::as_str).unwrap_or_default();
        match ym.get(&txn, "text") {
            Some(Out::YText(t)) => set_text(&mut txn, &t, text),
            _ => {
                ym.insert(&mut txn, "text", TextPrelim::new(text));
            }
        }
    }
    let n = blocks.len() as u32;
    let len = yblocks.len(&txn);
    if len > n {
        yblocks.remove_range(&mut txn, n, len - n);
    }

    // Typed properties merge per key (see packages/ui/src/cloud/ydoc.ts).
    let empty_props = Map::new();
    let want = page.get("props").and_then(Value::as_object).unwrap_or(&empty_props);
    let stale: Vec<String> = props.keys(&txn).filter(|k| !want.contains_key(*k)).map(str::to_string).collect();
    for k in stale {
        props.remove(&mut txn, &k);
    }
    for (k, v) in want {
        if !same(props.get(&txn, k).map(|o| out_json(&txn, &o)), v) {
            props.insert(&mut txn, k.as_str(), to_any(v));
        }
    }

    let canvas = page.get("canvas");
    let list = |k: &str| canvas.and_then(|c| c.get(k)).and_then(Value::as_array).cloned().unwrap_or_default();
    sync_keyed(&mut txn, &items, &list("items"));
    sync_keyed(&mut txn, &strokes, &list("strokes"));

    let u = txn.encode_update_v1();
    // An update with no structs and no deletions encodes as two zero varints.
    if u == [0, 0] { Vec::new() } else { u }
}

// ---------- doc -> page ----------

/// The page a document currently describes, as web `Page` JSON. None until its metadata arrived.
pub fn doc_to_page(doc: &Doc) -> Option<Value> {
    let meta = doc.get_or_insert_map("meta");
    let yblocks = doc.get_or_insert_array("blocks");
    let items = doc.get_or_insert_map("canvasItems");
    let strokes = doc.get_or_insert_map("strokes");
    let props = doc.get_or_insert_map("props");
    let txn = doc.transact();
    let id = meta.get(&txn, "id").map(|o| out_json(&txn, &o)).filter(Value::is_string)?;

    let mut page = Map::new();
    page.insert("id".into(), id);
    for k in META_KEYS {
        if let Some(o) = meta.get(&txn, k) {
            page.insert(k.into(), out_json(&txn, &o));
        }
    }
    page.entry("title").or_insert(Value::String(String::new()));
    page.entry("tags").or_insert(Value::Array(vec![]));
    page.entry("aliases").or_insert(Value::Array(vec![]));
    page.insert("favorite".into(), Value::Bool(false));

    let blocks: Vec<Value> = yblocks
        .iter(&txn)
        .filter_map(|o| match o {
            Out::YMap(m) => Some(m),
            _ => None,
        })
        .map(|ym| {
            let mut b = Map::new();
            b.insert("id".into(), Value::String(block_id(&txn, &ym).unwrap_or_default()));
            b.insert("kind".into(), Value::String("paragraph".into()));
            for k in BLOCK_KEYS {
                if let Some(o) = ym.get(&txn, k) {
                    b.insert(k.into(), out_json(&txn, &o));
                }
            }
            let text = match ym.get(&txn, "text") {
                Some(Out::YText(t)) => t.get_string(&txn),
                _ => String::new(),
            };
            b.insert("text".into(), Value::String(text));
            Value::Object(b)
        })
        .collect();
    page.insert("blocks".into(), Value::Array(blocks));

    if props.len(&txn) > 0 {
        let p: Map<String, Value> = props.iter(&txn).map(|(k, o)| (k.to_string(), out_json(&txn, &o))).collect();
        page.insert("props".into(), Value::Object(p));
    }

    let values = |m: &MapRef| -> Vec<Value> { m.iter(&txn).map(|(_, o)| out_json(&txn, &o)).collect() };
    let mut canvas = Map::new();
    canvas.insert("items".into(), Value::Array(values(&items)));
    canvas.insert("strokes".into(), Value::Array(values(&strokes)));
    page.insert("canvas".into(), Value::Object(canvas));
    Some(Value::Object(page))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn page(blocks: Value) -> Value {
        json!({ "id": "p1", "title": "Hello", "icon": "✈️", "props": { "status": "Doing", "points": 3, "done": true }, "tags": ["a"], "aliases": [], "favorite": true,
                "createdAt": 1712345678901i64, "updatedAt": 1712345678901i64, "blocks": blocks,
                "canvas": { "items": [{ "id": "n", "type": "note", "x": 0, "y": 0, "w": 640, "h": 400 }],
                            "strokes": [{ "id": "s", "points": [1, 2, 0.5], "color": "#000", "width": 2 }] } })
    }

    #[test]
    fn round_trip() {
        let doc = new_doc();
        let p = page(json!([{ "id": "b1", "kind": "heading", "level": 1, "text": "Title 😀" }, { "id": "b2", "kind": "paragraph", "text": "body" }]));
        assert!(!apply_page(&doc, &p).is_empty());
        let back = doc_to_page(&doc).unwrap();
        assert_eq!(back["title"], "Hello");
        assert_eq!(back["icon"], "✈️");
        assert_eq!(back["props"], json!({ "status": "Doing", "points": 3, "done": true }));
        assert_eq!(back["createdAt"], json!(1712345678901i64));
        assert_eq!(back["blocks"][0]["text"], "Title 😀");
        assert_eq!(back["blocks"][0]["level"], json!(1));
        assert_eq!(back["favorite"], json!(false));
        assert_eq!(back["canvas"]["strokes"][0]["points"], json!([1, 2, 0.5]));
        // Unchanged snapshot, also on a replica: no operations at all.
        assert!(apply_page(&doc, &p).is_empty());
        assert!(apply_page(&new_doc_with(&doc), &p).is_empty());
    }

    fn new_doc_with(src: &Doc) -> Doc {
        let d = new_doc();
        apply_update(&d, &encode_state(src)).unwrap();
        d
    }

    #[test]
    fn concurrent_typing_merges() {
        let a = new_doc();
        apply_page(&a, &page(json!([{ "id": "b1", "kind": "paragraph", "text": "hello world" }])));
        let b = new_doc_with(&a);
        let ua = apply_page(&a, &page(json!([{ "id": "b1", "kind": "paragraph", "text": "hello brave world" }])));
        let ub = apply_page(&b, &page(json!([{ "id": "b1", "kind": "paragraph", "text": "hello world!" }])));
        apply_update(&a, &ub).unwrap();
        apply_update(&b, &ua).unwrap();
        for d in [&a, &b] {
            assert_eq!(doc_to_page(d).unwrap()["blocks"][0]["text"], "hello brave world!");
        }
    }

    #[test]
    fn emoji_edits_keep_surrogates_whole() {
        let d = new_doc();
        apply_page(&d, &page(json!([{ "id": "b1", "kind": "paragraph", "text": "a😀b" }])));
        apply_page(&d, &page(json!([{ "id": "b1", "kind": "paragraph", "text": "a😃b" }])));
        assert_eq!(doc_to_page(&d).unwrap()["blocks"][0]["text"], "a😃b");
    }

    #[test]
    fn removes_and_reorders_blocks() {
        let d = new_doc();
        apply_page(&d, &page(json!([{ "id": "1", "text": "one" }, { "id": "2", "text": "two" }, { "id": "3", "text": "three" }])));
        apply_page(&d, &page(json!([{ "id": "3", "text": "three" }, { "id": "1", "text": "one" }])));
        let ids: Vec<Value> = doc_to_page(&d).unwrap()["blocks"].as_array().unwrap().iter().map(|b| b["id"].clone()).collect();
        assert_eq!(ids, vec![json!("3"), json!("1")]);
    }

    #[test]
    fn merges_updates() {
        let d = new_doc();
        let u1 = apply_page(&d, &page(json!([{ "id": "1", "text": "a" }])));
        let u2 = apply_page(&d, &page(json!([{ "id": "1", "text": "ab" }])));
        let merged = merge_updates(&[&u1, &u2]).unwrap();
        let e = new_doc();
        apply_update(&e, &merged).unwrap();
        assert_eq!(doc_to_page(&e).unwrap()["blocks"][0]["text"], "ab");
    }
}

/// Cross-client contract: fixtures shared with `packages/ui/src/cloud/native.test.ts`.
#[cfg(test)]
mod contract {
    use super::*;
    use std::path::Path;

    fn fixture(name: &str) -> std::path::PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)
    }

    #[test]
    fn reads_web_document() {
        let doc = new_doc();
        apply_update(&doc, &std::fs::read(fixture("web_page.bin")).unwrap()).unwrap();
        let expected: Value = serde_json::from_slice(&std::fs::read(fixture("web_page.json")).unwrap()).unwrap();
        assert_eq!(doc_to_page(&doc).unwrap(), expected);
        // Re-applying the same snapshot must be a no-op, or every open would push a bogus edit.
        assert!(apply_page(&doc, &expected).is_empty());
    }

    #[test]
    fn writes_document_for_web() {
        let page: Value = serde_json::from_slice(&std::fs::read(fixture("web_page.json")).unwrap()).unwrap();
        let mut page = page.as_object().unwrap().clone();
        page.insert("title".into(), "Written on Android ✍️".into());
        page.insert("id".into(), "7c1e9d20-8b3f-4f6a-a2d5-0e9c8b7a6f5e".into());
        let page = Value::Object(page);
        let doc = new_doc();
        apply_page(&doc, &page);
        if std::env::var("WRITE_FIXTURES").as_deref() == Ok("1") {
            std::fs::write(fixture("rust_page.bin"), encode_state(&doc)).unwrap();
            std::fs::write(fixture("rust_page.json"), serde_json::to_string_pretty(&doc_to_page(&doc).unwrap()).unwrap()).unwrap();
        }
        assert_eq!(doc_to_page(&doc).unwrap()["title"], "Written on Android ✍️");
    }
}
