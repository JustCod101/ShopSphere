"""Result 包装结构 + UTC ISO-8601 timestamp 断言。"""
import re

from app.core.error_code import ErrorCode
from app.core.result import Result


ISO_UTC_OFFSET_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?\+00:00$")


def test_ok_with_data():
    r = Result.ok({"items": []})
    d = r.to_dict()
    assert d["code"] == 0
    assert d["message"] == "ok"
    assert d["data"] == {"items": []}
    assert d["traceId"] is None  # 测试外没有中间件设置 contextvar
    assert ISO_UTC_OFFSET_RE.match(d["timestamp"]), f"timestamp not UTC ISO-8601: {d['timestamp']}"


def test_ok_default_data_none():
    d = Result.ok().to_dict()
    assert d["code"] == 0
    assert d["data"] is None


def test_fail_uses_default_message_when_omitted():
    d = Result.fail(ErrorCode.UNAUTHORIZED).to_dict()
    assert d["code"] == 1001
    assert d["message"]  # 非空
    assert d["data"] is None


def test_fail_custom_message():
    d = Result.fail(ErrorCode.PARAM_INVALID, "topk must be 1..100").to_dict()
    assert d["code"] == 1000
    assert d["message"] == "topk must be 1..100"
