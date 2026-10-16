//! WebSocket sync gateway (`/api/v1/sync`). Relays and persists encrypted Yjs updates per page.
//!
//! Durability: a push is acknowledged only after its row is committed. Ordering: pushes for one
//! page are serialized in-process, so `seq` order equals fan-out order and a client cursor never
//! skips an update. shortcut: single-instance fan-out; add Redis pub/sub (plus a per-page DB lock)
//! before running more than one API instance.

use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicU64, AtomicUsize, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};

use axum::{
    extract::{
        ws::{Message, WebSocket},
        State, WebSocketUpgrade,
    },
    http::{header, HeaderMap},
    response::Response,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::sync::{broadcast, mpsc};
use uuid::Uuid;

use crate::{auth::user_from_headers, pages::role_of, settings, Shared};

const CATCHUP_BATCH: i64 = 500;
const HEARTBEAT: Duration = Duration::from_secs(30);
const IDLE_TIMEOUT: Duration = Duration::from_secs(90);
const RATE_WINDOW: Duration = Duration::from_secs(10);
const RATE_MAX: u32 = 300;
const MAX_SUBSCRIPTIONS: usize = 2000;

#[derive(Clone)]
enum RoomEvent {
    Update { seq: i64, payload: Arc<str> },
    Presence(Arc<str>),
    CloseAnonymous,
}

struct Room {
    tx: broadcast::Sender<RoomEvent>,
    present: HashMap<u64, (Uuid, String)>,
    push_lock: Arc<tokio::sync::Mutex<()>>,
}

pub struct Hub {
    rooms: Mutex<HashMap<Uuid, Room>>,
    kick: broadcast::Sender<Uuid>,
    next_conn: AtomicU64,
    pub online: AtomicUsize,
}

impl Default for Hub {
    fn default() -> Self {
        Self {
            rooms: Mutex::default(),
            kick: broadcast::channel(256).0,
            next_conn: AtomicU64::new(1),
            online: AtomicUsize::new(0),
        }
    }
}

impl Hub {
    /// Close every socket of `user` (revoked session, disabled account, changed permission).
    pub fn kick(&self, user: Uuid) {
        let _ = self.kick.send(user);
    }

    pub fn close_anonymous(&self, page: Uuid) {
        if let Some(r) = self.rooms.lock().unwrap().get(&page) {
            let _ = r.tx.send(RoomEvent::CloseAnonymous);
        }
    }

    fn presence_json(page: Uuid, room: &Room) -> Arc<str> {
        let mut seen = std::collections::HashSet::new();
        let users: Vec<Value> = room
            .present
            .values()
            .filter(|(id, _)| seen.insert(*id))
            .map(|(id, name)| json!({ "id": id, "name": name }))
            .collect();
        json!({ "type": "presence", "page_id": page, "users": users }).to_string().into()
    }

    fn join(&self, page: Uuid, conn: u64, who: Option<(Uuid, String)>) -> broadcast::Receiver<RoomEvent> {
        let mut rooms = self.rooms.lock().unwrap();
        let room = rooms.entry(page).or_insert_with(|| Room {
            tx: broadcast::channel(1024).0,
            present: HashMap::new(),
            push_lock: Arc::default(),
        });
        let rx = room.tx.subscribe();
        if let Some(w) = who {
            room.present.insert(conn, w);
            let _ = room.tx.send(RoomEvent::Presence(Self::presence_json(page, room)));
        }
        rx
    }

    fn leave(&self, page: Uuid, conn: u64) {
        let mut rooms = self.rooms.lock().unwrap();
        let Some(room) = rooms.get_mut(&page) else { return };
        if room.present.remove(&conn).is_some() {
            let _ = room.tx.send(RoomEvent::Presence(Self::presence_json(page, room)));
        }
        if room.present.is_empty() && room.tx.receiver_count() == 0 {
            rooms.remove(&page);
        }
    }

    /// Creates the room if needed so concurrent pushes always share one lock.
    fn push_lock(&self, page: Uuid) -> Arc<tokio::sync::Mutex<()>> {
        let mut rooms = self.rooms.lock().unwrap();
        let room = rooms.entry(page).or_insert_with(|| Room {
            tx: broadcast::channel(1024).0,
            present: HashMap::new(),
            push_lock: Arc::default(),
        });
        room.push_lock.clone()
    }

    fn publish(&self, page: Uuid, seq: i64, payload: Arc<str>) {
        if let Some(r) = self.rooms.lock().unwrap().get(&page) {
            let _ = r.tx.send(RoomEvent::Update { seq, payload });
        }
    }
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum ClientMsg {
    Subscribe { page_id: Uuid, after: i64 },
    Unsubscribe { page_id: Uuid },
    Push { page_id: Uuid, op_id: Uuid, payload: String },
    Ping,
}

fn origin_allowed(headers: &HeaderMap, app_url: &str) -> bool {
    // Browsers always send Origin on WebSocket upgrades; reject cross-site pages riding the cookie.
    match headers.get(header::ORIGIN).and_then(|o| o.to_str().ok()) {
        None => true,
        Some(o) => o.trim_end_matches('/') == app_url,
    }
}

pub async fn ws_handler(State(st): State<Shared>, headers: HeaderMap, ws: WebSocketUpgrade) -> Response {
    let s = settings::load(&st.db).await.unwrap_or_default();
    let user = if origin_allowed(&headers, s.app_url()) {
        user_from_headers(&st.db, &headers).await.ok().flatten().map(|u| (u.id, u.name))
    } else {
        None
    };
    let max = s.max_update_bytes as usize;
    ws.max_message_size(max * 4 / 3 + 4096)
        .on_upgrade(move |socket| connection(st, socket, user, max))
}

type Out = mpsc::Sender<Message>;

async fn send(out: &Out, v: Value) -> bool {
    out.send(Message::Text(v.to_string())).await.is_ok()
}

async fn send_err(out: &Out, code: &str, message: &str, page: Option<Uuid>, op: Option<Uuid>) -> bool {
    send(out, json!({ "type": "error", "code": code, "message": message, "page_id": page, "op_id": op })).await
}

async fn connection(st: Shared, socket: WebSocket, user: Option<(Uuid, String)>, max_bytes: usize) {
    st.hub.online.fetch_add(1, Ordering::Relaxed);
    let conn = st.hub.next_conn.fetch_add(1, Ordering::Relaxed);
    let (mut sink, mut stream) = socket.split();
    let (out, mut out_rx) = mpsc::channel::<Message>(256);
    let writer = tokio::spawn(async move {
        while let Some(m) = out_rx.recv().await {
            if sink.send(m).await.is_err() {
                break;
            }
        }
        let _ = sink.close().await;
    });

    let mut subs: HashMap<Uuid, tokio::task::JoinHandle<()>> = HashMap::new();
    let mut kicks = st.hub.kick.subscribe();
    let mut beat = tokio::time::interval(HEARTBEAT);
    let mut last_seen = Instant::now();
    let mut window = (Instant::now(), 0u32);

    loop {
        tokio::select! {
            msg = stream.next() => {
                let text = match msg {
                    Some(Ok(Message::Text(t))) => t,
                    Some(Ok(Message::Ping(_) | Message::Pong(_))) => { last_seen = Instant::now(); continue; }
                    Some(Ok(Message::Binary(_))) => { send_err(&out, "invalid", "Text frames only", None, None).await; continue; }
                    _ => break,
                };
                last_seen = Instant::now();
                if window.0.elapsed() > RATE_WINDOW { window = (Instant::now(), 0); }
                window.1 += 1;
                if window.1 > RATE_MAX {
                    send_err(&out, "rate_limited", "Too many messages", None, None).await;
                    break;
                }
                let Ok(m) = serde_json::from_str::<ClientMsg>(&text) else {
                    send_err(&out, "invalid", "Malformed message", None, None).await;
                    continue;
                };
                match m {
                    ClientMsg::Ping => { send(&out, json!({ "type": "pong" })).await; }
                    ClientMsg::Unsubscribe { page_id } => {
                        if let Some(h) = subs.remove(&page_id) { h.abort(); }
                        st.hub.leave(page_id, conn);
                    }
                    ClientMsg::Subscribe { page_id, after } => {
                        if subs.len() >= MAX_SUBSCRIPTIONS && !subs.contains_key(&page_id) {
                            send_err(&out, "invalid", "Too many subscriptions", Some(page_id), None).await;
                            continue;
                        }
                        if !can_read(&st, page_id, user.as_ref().map(|u| u.0)).await {
                            send_err(&out, "forbidden", "No access to this page", Some(page_id), None).await;
                            continue;
                        }
                        if let Some(h) = subs.remove(&page_id) { h.abort(); st.hub.leave(page_id, conn); }
                        let member = match &user {
                            Some((uid, _)) => matches!(role_of(&st.db, page_id, *uid).await, Ok(Some(_))),
                            None => false,
                        };
                        // Public readers are not announced and do not see who is editing.
                        let rx = st.hub.join(page_id, conn, if member { user.clone() } else { None });
                        let task = forward(st.clone(), out.clone(), page_id, after.max(0), rx, !member);
                        subs.insert(page_id, tokio::spawn(task));
                    }
                    ClientMsg::Push { page_id, op_id, payload } => {
                        handle_push(&st, &out, user.as_ref().map(|u| u.0), page_id, op_id, payload, max_bytes).await;
                    }
                }
            }
            k = kicks.recv() => {
                if let Ok(id) = k {
                    if user.as_ref().is_some_and(|u| u.0 == id) { break; }
                }
            }
            _ = beat.tick() => {
                if last_seen.elapsed() > IDLE_TIMEOUT { break; }
                if out.send(Message::Ping(Vec::new())).await.is_err() { break; }
            }
        }
    }

    for (page, h) in subs {
        h.abort();
        st.hub.leave(page, conn);
    }
    drop(out);
    let _ = tokio::time::timeout(Duration::from_secs(2), writer).await;
    st.hub.online.fetch_sub(1, Ordering::Relaxed);
}

async fn can_read(st: &Shared, page: Uuid, user: Option<Uuid>) -> bool {
    if let Some(u) = user {
        if matches!(role_of(&st.db, page, u).await, Ok(Some(_))) {
            return true;
        }
    }
    sqlx::query_scalar::<_, bool>("SELECT public FROM pages WHERE id = $1")
        .bind(page)
        .fetch_optional(&st.db)
        .await
        .ok()
        .flatten()
        .unwrap_or(false)
}

async fn fetch_after(st: &Shared, page: Uuid, after: i64) -> Option<Vec<(i64, Vec<u8>)>> {
    sqlx::query_as("SELECT seq, payload FROM document_updates WHERE page_id = $1 AND seq > $2 ORDER BY seq LIMIT $3")
        .bind(page)
        .bind(after)
        .bind(CATCHUP_BATCH)
        .fetch_all(&st.db)
        .await
        .map_err(|e| tracing::error!(error = %e, "catch-up query failed"))
        .ok()
}

/// Sends everything after `cursor` from the DB. Returns the new cursor, or None if the socket closed.
async fn catch_up(st: &Shared, out: &Out, page: Uuid, mut cursor: i64) -> Option<i64> {
    loop {
        let rows = fetch_after(st, page, cursor).await?;
        let n = rows.len() as i64;
        if let Some((last, _)) = rows.last() {
            cursor = *last;
        }
        let updates: Vec<Value> = rows.into_iter().map(|(seq, p)| json!({ "seq": seq, "payload": STANDARD.encode(p) })).collect();
        if !send(out, json!({ "type": "updates", "page_id": page, "updates": updates, "cursor": cursor })).await {
            return None;
        }
        if n < CATCHUP_BATCH {
            return Some(cursor);
        }
    }
}

/// Subscription task: replay history after `after`, then stream live events from the room.
/// `public_reader`: subscribed via the page's public flag rather than membership.
async fn forward(st: Shared, out: Out, page: Uuid, after: i64, mut rx: broadcast::Receiver<RoomEvent>, public_reader: bool) {
    // `rx` was subscribed before this query, so nothing committed in between is lost.
    let Some(mut cursor) = catch_up(&st, &out, page, after).await else { return };
    loop {
        match rx.recv().await {
            Ok(RoomEvent::Update { seq, payload }) => {
                if seq <= cursor {
                    continue;
                }
                cursor = seq;
                let m = json!({ "type": "updates", "page_id": page, "updates": [{ "seq": seq, "payload": &*payload }], "cursor": seq });
                if !send(&out, m).await {
                    return;
                }
            }
            Ok(RoomEvent::Presence(_)) if public_reader => {}
            Ok(RoomEvent::Presence(p)) => {
                if out.send(Message::Text(p.to_string())).await.is_err() {
                    return;
                }
            }
            Ok(RoomEvent::CloseAnonymous) => {
                if public_reader {
                    send_err(&out, "forbidden", "This page is no longer public", Some(page), None).await;
                    return;
                }
            }
            // Slow consumer fell behind the ring buffer: recover from the durable log.
            Err(broadcast::error::RecvError::Lagged(_)) => match catch_up(&st, &out, page, cursor).await {
                Some(c) => cursor = c,
                None => return,
            },
            Err(broadcast::error::RecvError::Closed) => return,
        }
    }
}

async fn handle_push(st: &Shared, out: &Out, user: Option<Uuid>, page: Uuid, op: Uuid, payload: String, max_bytes: usize) {
    let role = match user {
        Some(u) => role_of(&st.db, page, u).await.ok().flatten(),
        None => None,
    };
    if !matches!(role.as_deref(), Some("owner" | "editor")) {
        send_err(out, "forbidden", "You cannot edit this page", Some(page), Some(op)).await;
        return;
    }
    let bytes = match STANDARD.decode(&payload) {
        // 12-byte IV + 16-byte GCM tag is the smallest valid ciphertext.
        Ok(b) if b.len() >= 28 && b.len() <= max_bytes => b,
        Ok(_) => {
            send_err(out, "invalid", "Update size out of range", Some(page), Some(op)).await;
            return;
        }
        Err(_) => {
            send_err(out, "invalid", "Payload must be base64", Some(page), Some(op)).await;
            return;
        }
    };
    let lock = st.hub.push_lock(page);
    let _guard = lock.lock().await;
    let inserted: Result<Option<i64>, _> = sqlx::query_scalar(
        "INSERT INTO document_updates (page_id, op_id, user_id, payload) VALUES ($1, $2, $3, $4)
         ON CONFLICT (page_id, op_id) DO NOTHING RETURNING seq",
    )
    .bind(page)
    .bind(op)
    .bind(user)
    .bind(&bytes)
    .fetch_optional(&st.db)
    .await;
    let seq = match inserted {
        Ok(Some(seq)) => {
            st.hub.publish(page, seq, payload.into());
            seq
        }
        // Duplicate delivery after a reconnect: ack the original row, do not fan out again.
        Ok(None) => match sqlx::query_scalar("SELECT seq FROM document_updates WHERE page_id = $1 AND op_id = $2")
            .bind(page)
            .bind(op)
            .fetch_one(&st.db)
            .await
        {
            Ok(seq) => seq,
            Err(_) => return,
        },
        Err(e) => {
            tracing::error!(error = %e, "persisting update failed");
            send_err(out, "internal", "Could not save update", Some(page), Some(op)).await;
            return;
        }
    };
    drop(_guard);
    send(out, json!({ "type": "ack", "page_id": page, "op_id": op, "seq": seq })).await;
}
