"""错误码号段断言 —— 防止误改。"""
from app.core.error_code import ErrorCode


def test_common_codes():
    assert int(ErrorCode.OK) == 0
    assert int(ErrorCode.PARAM_INVALID) == 1000
    assert int(ErrorCode.UNAUTHORIZED) == 1001
    assert int(ErrorCode.NOT_FOUND) == 1004
    assert int(ErrorCode.SERVER_ERROR) == 1500


def test_reco_codes_in_5xxx():
    assert int(ErrorCode.COLD_START) == 5001
    assert int(ErrorCode.MODEL_NOT_READY) == 5002
    # 5xxx 不与通用 1xxx 冲突
    assert 5000 <= int(ErrorCode.COLD_START) < 6000
    assert 5000 <= int(ErrorCode.MODEL_NOT_READY) < 6000
