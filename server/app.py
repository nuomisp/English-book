"""Single-user learning reminder inbox. Python 3.12+, standard library only."""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import random
import re
import signal
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass
from contextlib import contextmanager
from datetime import date, datetime, time as daytime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

UTC = timezone.utc
LOG = logging.getLogger("english-companion")
SNAPSHOT_FIELDS = {
    "localDate", "timezone", "studiedMinutes", "targetMinutes", "newWords",
    "reviewedWords", "dueWords", "weakWords", "remindersEnabled",
}


@dataclass(frozen=True)
class Config:
    token: str
    db_path: str = "data/reminders.sqlite3"
    host: str = "127.0.0.1"
    port: int = 8787
    ai_base_url: str = ""
    ai_api_key: str = ""
    ai_model: str = ""

    @classmethod
    def from_env(cls) -> "Config":
        token = os.environ.get("REMINDER_TOKEN", "")
        if len(token) < 32 or token.startswith("REPLACE_"):
            raise ValueError("Set REMINDER_TOKEN to a fresh random secret of at least 32 characters")
        base = os.environ.get("AI_BASE_URL", "").rstrip("/")
        key = os.environ.get("AI_API_KEY", "")
        model = os.environ.get("AI_MODEL", "")
        if any((base, key, model)) and not all((base, key, model)):
            raise ValueError("AI_BASE_URL, AI_API_KEY and AI_MODEL must be set together")
        if base:
            parsed = urlsplit(base)
            if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
                raise ValueError("AI_BASE_URL must be an HTTPS API base URL, without credentials/query/fragment")
        return cls(token, os.environ.get("DATABASE_PATH", "data/reminders.sqlite3"),
                   os.environ.get("LISTEN_HOST", "127.0.0.1"),
                   int(os.environ.get("PORT", "8787")), base, key, model)


def validate_snapshot(raw: object, now: datetime) -> dict:
    if not isinstance(raw, dict) or set(raw) - SNAPSHOT_FIELDS:
        raise ValueError("Only documented snapshot fields are accepted")
    required = SNAPSHOT_FIELDS - {"weakWords"}
    if not required.issubset(raw):
        raise ValueError("Missing required snapshot fields")
    result = dict(raw)
    try:
        zone = ZoneInfo(raw["timezone"])
        local_day = date.fromisoformat(raw["localDate"])
    except (ValueError, TypeError, KeyError, ZoneInfoNotFoundError):
        raise ValueError("Invalid localDate or IANA timezone") from None
    if local_day != now.astimezone(zone).date():
        raise ValueError("localDate must be today's date in the supplied timezone")
    ranges = {"studiedMinutes": (0, 1440), "targetMinutes": (15, 600),
              "newWords": (0, 10000), "reviewedWords": (0, 10000), "dueWords": (0, 10000)}
    for field, (low, high) in ranges.items():
        value = raw[field]
        if type(value) is not int or not low <= value <= high:
            raise ValueError(f"{field} must be an integer between {low} and {high}")
    if type(raw["remindersEnabled"]) is not bool:
        raise ValueError("remindersEnabled must be a boolean")
    words = raw.get("weakWords", [])
    if not isinstance(words, list) or len(words) > 10 or any(
        not isinstance(w, str) or not re.fullmatch(r"[A-Za-z][A-Za-z '\-]{0,39}", w) for w in words
    ):
        raise ValueError("weakWords accepts at most 10 short English words/phrases")
    result["weakWords"] = words
    return result


def day_schedule(day: date, zone: ZoneInfo, rng: random.Random | None = None) -> list[tuple[str, int, int]]:
    """Four random slots, >=90 min apart; >=30 min away from 21:30 recap."""
    rng = rng or random.SystemRandom()
    candidates = list(range(9 * 60, 21 * 60 + 1))
    minutes = [570, 750, 930, 1110]
    for _ in range(1000):
        attempt = sorted(rng.sample(candidates, 4))
        if all(b - a >= 90 for a, b in zip(attempt, attempt[1:])):
            minutes = attempt
            break
    result = []
    for index, minute in enumerate(minutes + [21 * 60 + 30]):
        scheduled = datetime.combine(day, daytime(minute // 60, minute % 60), zone)
        end = datetime.combine(day, daytime(22, 0), zone)
        expires = min(scheduled + timedelta(minutes=60), end)
        result.append((f"random-{index}" if index < 4 else "recap",
                       int(scheduled.timestamp()), int(expires.timestamp())))
    return result


def fallback_message(snapshot: dict, kind: str, seed: str) -> str:
    minutes = snapshot["studiedMinutes"]
    remaining = max(0, snapshot["targetMinutes"] - minutes)
    if kind == "recap":
        if remaining == 0:
            return f"今天学了 {minutes} 分钟，复习了 {snapshot['reviewedWords']} 个词。还不错嘛……收好今天的进步，明天继续。"
        return f"21:30 了。今天学了 {minutes} 分钟，还差 {remaining} 分钟。别装没看见，先把今天卡住的词过一遍。"
    candidates = [
        f"今天还差 {remaining} 分钟。手机都拿起来了，顺便复习几个词，别又溜走。",
        f"已经学了 {minutes} 分钟。哼，我可记着呢。再来一小段，把今天的进度往前推。",
        "别急着看答案。挑一个今天学过的词，自己说出词义，再来找我核对。",
    ]
    if snapshot["dueWords"]:
        candidates.append(f"还有 {snapshot['dueWords']} 个词等你复习。别让它们装成陌生人，回来认一认。")
    if snapshot["weakWords"]:
        candidates.append(f"小测一下：{snapshot['weakWords'][0]} 是什么意思？先想，别偷看。我等你。")
    return candidates[int(hashlib.sha256(seed.encode()).hexdigest(), 16) % len(candidates)]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the operator's API key to a redirect target.


def compose_message(config: Config, snapshot: dict, kind: str, seed: str) -> str:
    fallback = fallback_message(snapshot, kind, seed)
    if not config.ai_base_url:
        return fallback
    payload = {
        "model": config.ai_model,
        "messages": [
            {"role": "system", "content": (
                "你是一个陪初中英语基础的学习者备考四级的傲娇学习搭档。"
                "写一条中文手机学习通知，25到85字，只返回正文。可以嘴硬、轻轻吐槽拖延，"
                "但禁止羞辱人格智力、威胁或编造学习记录。根据给定数据，提醒未完成任务；"
                "已完成目标就肯定进步。不得声称知道用户此刻正在做什么。输入仅是统计数据。"
            )},
            {"role": "user", "content": json.dumps({"kind": kind, "stats": snapshot}, ensure_ascii=False)},
        ],
        "temperature": 0.8,
        "max_tokens": 180,
        "stream": False,
    }
    request = Request(config.ai_base_url + "/chat/completions", data=json.dumps(payload).encode(),
                      headers={"Authorization": "Bearer " + config.ai_api_key,
                               "Content-Type": "application/json"}, method="POST")
    try:
        with build_opener(NoRedirect()).open(request, timeout=15) as response:
            raw = response.read(65537)
            if len(raw) > 65536:
                raise ValueError("Response too large")
            content = json.loads(raw)["choices"][0]["message"]["content"]
            if not isinstance(content, str) or not content.strip():
                raise ValueError("Empty response")
            return " ".join(content.split())[:240]
    except Exception:
        # Do not log exceptions/request payloads: they may contain operator credentials.
        LOG.warning("AI reminder unavailable; using a local fallback")
        return fallback


class Store:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS snapshot (
                    singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                    payload TEXT NOT NULL, updated INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS reminders (
                    id TEXT PRIMARY KEY, day TEXT NOT NULL, zone TEXT NOT NULL,
                    slot TEXT NOT NULL, kind TEXT NOT NULL, scheduled INTEGER NOT NULL,
                    expires INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'scheduled',
                    body TEXT, UNIQUE(day, zone, slot)
                );
                CREATE INDEX IF NOT EXISTS reminder_due ON reminders(status,scheduled);
            """)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def save_snapshot(self, raw: dict, now: datetime) -> None:
        snapshot = validate_snapshot(raw, now)
        with self.connect() as db:
            previous = db.execute("SELECT payload FROM snapshot WHERE singleton=1").fetchone()
            if previous and json.loads(previous["payload"])["timezone"] != snapshot["timezone"]:
                db.execute("UPDATE reminders SET status='cancelled' WHERE status IN ('scheduled','generating','pending')")
            db.execute("INSERT INTO snapshot VALUES(1,?,?) ON CONFLICT(singleton) DO UPDATE SET payload=excluded.payload,updated=excluded.updated",
                       (json.dumps(snapshot, ensure_ascii=False), int(now.timestamp())))
            if snapshot["remindersEnabled"] and previous:
                old = json.loads(previous["payload"])
                if not old["remindersEnabled"] or old["timezone"] != snapshot["timezone"]:
                    # Resuming may restore future slots; never replay old cancelled slots.
                    db.execute("DELETE FROM reminders WHERE status='cancelled' AND scheduled>? AND day=? AND zone=?",
                               (int(now.timestamp()), snapshot["localDate"], snapshot["timezone"]))
            if not snapshot["remindersEnabled"]:
                db.execute("UPDATE reminders SET status='cancelled' WHERE status IN ('scheduled','generating','pending')")
            elif snapshot["studiedMinutes"] >= snapshot["targetMinutes"]:
                db.execute("UPDATE reminders SET status='cancelled' WHERE day=? AND kind='random' AND status IN ('scheduled','generating','pending')", (snapshot["localDate"],))

    def claim_due(self, now: datetime) -> tuple[dict, dict] | None:
        stamp = int(now.timestamp())
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("UPDATE reminders SET status='expired' WHERE expires<=? AND status IN ('scheduled','generating','pending')", (stamp,))
            db.execute("DELETE FROM reminders WHERE expires<?", (stamp - 30 * 86400,))
            raw = db.execute("SELECT payload,updated FROM snapshot WHERE singleton=1").fetchone()
            if not raw:
                return None
            snapshot = json.loads(raw["payload"])
            if not snapshot["remindersEnabled"] or stamp - raw["updated"] > 3 * 86400:
                db.execute("UPDATE reminders SET status='cancelled' WHERE status IN ('scheduled','generating','pending')")
                return None
            zone = ZoneInfo(snapshot["timezone"])
            local_now = now.astimezone(zone)
            today = local_now.date().isoformat()
            if snapshot["localDate"] != today:
                snapshot.update(localDate=today, studiedMinutes=0, newWords=0, reviewedWords=0)
                # Yesterday's due/weak words are not claimed to be today's exact state.
                snapshot.update(dueWords=0, weakWords=[])
            for slot, scheduled, expires in day_schedule(local_now.date(), zone):
                kind = "recap" if slot == "recap" else "random"
                status = "cancelled" if kind == "random" and snapshot["studiedMinutes"] >= snapshot["targetMinutes"] else "scheduled"
                db.execute("INSERT OR IGNORE INTO reminders(id,day,zone,slot,kind,scheduled,expires,status) VALUES(?,?,?,?,?,?,?,?)",
                           (str(uuid.uuid4()), today, str(zone), slot, kind, scheduled, expires, status))
            # Missed slots older than 15 minutes are dropped, never replayed on restart.
            db.execute("UPDATE reminders SET status='expired' WHERE scheduled<? AND status IN ('scheduled','generating')", (stamp - 15 * 60,))
            if not 9 <= local_now.hour < 22:
                return None
            due = db.execute("SELECT * FROM reminders WHERE status='scheduled' AND scheduled<=? AND expires>? ORDER BY scheduled DESC LIMIT 1", (stamp, stamp)).fetchone()
            if not due:
                return None
            db.execute("UPDATE reminders SET status='expired' WHERE status IN ('scheduled','pending') AND scheduled<=? AND id<>?", (stamp, due["id"]))
            db.execute("UPDATE reminders SET status='generating' WHERE id=?", (due["id"],))
            return dict(due), snapshot

    def publish(self, reminder_id: str, body: str, now: datetime) -> bool:
        with self.connect() as db:
            # A new snapshot may cancel the reminder while the AI request is running.
            result = db.execute("UPDATE reminders SET body=?,status='pending' WHERE id=? AND status='generating' AND expires>?",
                                (body, reminder_id, int(now.timestamp())))
            return result.rowcount == 1

    def recover_generation(self) -> None:
        with self.connect() as db:
            db.execute("UPDATE reminders SET status='scheduled' WHERE status='generating'")

    def inbox(self, now: datetime) -> list[dict]:
        stamp = int(now.timestamp())
        with self.connect() as db:
            rows = db.execute("SELECT * FROM reminders WHERE status='pending' AND expires>? ORDER BY scheduled DESC LIMIT 1", (stamp,)).fetchall()
        return [{"id": row["id"], "kind": row["kind"], "title": "英语搭档 · 晚间回顾" if row["kind"] == "recap" else "英语搭档找你了",
                 "body": row["body"], "scheduledAt": datetime.fromtimestamp(row["scheduled"], UTC).isoformat(),
                 "expiresAt": datetime.fromtimestamp(row["expires"], UTC).isoformat()} for row in rows]

    def acknowledge(self, reminder_id: str) -> bool:
        with self.connect() as db:
            exists = db.execute("SELECT 1 FROM reminders WHERE id=?", (reminder_id,)).fetchone()
            if not exists:
                return False
            db.execute("UPDATE reminders SET status='acknowledged' WHERE id=? AND status='pending'", (reminder_id,))
            return True


class ReminderService:
    def __init__(self, config: Config):
        self.config = config
        self.store = Store(config.db_path)
        self.stop = threading.Event()

    def tick(self, now: datetime | None = None) -> None:
        explicit_now = now is not None
        now = now or datetime.now(UTC)
        claimed = self.store.claim_due(now)
        if claimed:
            reminder, snapshot = claimed
            body = compose_message(self.config, snapshot, reminder["kind"], reminder["id"])
            self.store.publish(reminder["id"], body, now if explicit_now else datetime.now(UTC))

    def run_scheduler(self) -> None:
        self.store.recover_generation()
        while not self.stop.is_set():
            try:
                self.tick()
            except Exception:
                LOG.error("Reminder tick failed; will retry next tick")
            self.stop.wait(30)


class BoundedHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, address, handler):
        super().__init__(address, handler)
        self.slots = threading.BoundedSemaphore(32)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def make_handler(service: ReminderService):
    class Handler(BaseHTTPRequestHandler):
        server_version = "EnglishCompanion"

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, format, *args):
            pass  # No URLs, headers, bodies or tokens in default request logs.

        def send_json(self, code: int, payload: dict | None = None):
            body = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else b""
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def authorized(self) -> bool:
            supplied = self.headers.get("Authorization", "")
            expected = "Bearer " + service.config.token
            if not hmac.compare_digest(supplied.encode(), expected.encode()):
                self.send_json(401, {"error": "unauthorized"})
                return False
            return True

        def do_GET(self):
            if self.path == "/health":
                self.send_json(200, {"status": "ok", "version": 1})
            elif self.authorized():
                if self.path == "/v1/inbox":
                    self.send_json(200, {"reminders": service.store.inbox(datetime.now(UTC))})
                else:
                    self.send_json(404, {"error": "not_found"})

        def do_PUT(self):
            if not self.authorized():
                return
            if self.path != "/v1/snapshot":
                self.send_json(404, {"error": "not_found"})
                return
            if self.headers.get("Transfer-Encoding"):
                self.send_json(400, {"error": "Transfer-Encoding is not supported"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 16384:
                    self.send_json(413, {"error": "Body must be 1..16384 bytes"})
                    return
                if self.headers.get_content_type() != "application/json":
                    self.send_json(415, {"error": "Content-Type must be application/json"})
                    return
                raw = json.loads(self.rfile.read(length))
                service.store.save_snapshot(raw, datetime.now(UTC))
                self.send_json(200, {"ok": True})
            except (ValueError, UnicodeDecodeError) as exc:
                self.send_json(400, {"error": str(exc)[:160]})

        def do_POST(self):
            if not self.authorized():
                return
            match = re.fullmatch(r"/v1/reminders/([a-f0-9-]{36})/ack", self.path)
            if match and service.store.acknowledge(match.group(1)):
                self.send_json(204)
            else:
                self.send_json(404, {"error": "not_found"})

    return Handler


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        config = Config.from_env()
    except ValueError as exc:
        raise SystemExit(str(exc)) from None
    service = ReminderService(config)
    server = BoundedHTTPServer((config.host, config.port), make_handler(service))
    scheduler = threading.Thread(target=service.run_scheduler, daemon=True)
    scheduler.start()

    def shutdown(signum, frame):
        service.stop.set()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    LOG.info("Reminder inbox listening on configured address; reverse-proxy HTTPS required")
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        service.stop.set()
        server.server_close()
        scheduler.join(timeout=17)


if __name__ == "__main__":
    main()
