"""消费者纯函数单元测试 —— 不连真实 broker / DB。"""
from datetime import datetime, timezone

from app.consumer.behavior_consumer import (
    SOURCE_BEHAVIOR,
    SOURCE_ORDER,
    normalize_order_created,
    normalize_user_behavior,
)


def test_user_behavior_to_one_row():
    payload = {
        "eventId": "evt-1234",
        "userId": 7,
        "itemId": 2001,
        "actionType": "view",
        "ts": "2026-05-23T10:30:00Z",
        "extra": None,
    }
    rows = normalize_user_behavior(payload)
    assert len(rows) == 1
    r = rows[0]
    assert r.event_id == "evt-1234"
    assert r.user_id == 7
    assert r.item_id == 2001
    assert r.action_type == "view"
    assert r.source == SOURCE_BEHAVIOR
    assert r.ts == datetime(2026, 5, 23, 10, 30, 0, tzinfo=timezone.utc)


def test_user_behavior_naive_ts_treated_as_utc():
    payload = {
        "eventId": "evt-naive",
        "userId": 1,
        "itemId": 1,
        "actionType": "cart",
        "ts": "2026-05-23T10:30:00",  # no tz
    }
    rows = normalize_user_behavior(payload)
    assert rows[0].ts.tzinfo is not None
    assert rows[0].ts.utcoffset().total_seconds() == 0


def test_order_created_expands_per_item():
    payload = {
        "orderId": 9001,
        "orderNo": "SO9001",
        "userId": 7,
        "totalAmount": "99.00",
        "ts": "2026-05-23T10:30:00Z",
        "items": [
            {"productId": 2001, "productName": "A", "price": "10.00", "quantity": 2},
            {"productId": 2002, "productName": "B", "price": "20.00", "quantity": 1},
        ],
    }
    rows = normalize_order_created(payload)
    assert len(rows) == 2
    assert {r.event_id for r in rows} == {"order-9001-2001", "order-9001-2002"}
    for r in rows:
        assert r.action_type == "order"
        assert r.source == SOURCE_ORDER
        assert r.user_id == 7


def test_order_created_event_id_deterministic_for_replay():
    """重投同一 order.created → 同样的 eventId → DB ON DUPLICATE KEY 天然幂等。"""
    payload = {
        "orderId": 42,
        "orderNo": "SO42",
        "userId": 1,
        "totalAmount": "0.00",
        "ts": "2026-05-23T10:30:00Z",
        "items": [{"productId": 100, "productName": "X", "price": "0", "quantity": 1}],
    }
    a = normalize_order_created(payload)
    b = normalize_order_created(payload)
    assert [r.event_id for r in a] == [r.event_id for r in b]
    assert a[0].event_id == "order-42-100"
