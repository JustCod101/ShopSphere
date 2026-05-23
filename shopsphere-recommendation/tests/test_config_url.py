"""Settings URL 拼装 —— 口令含 URL 保留字符时必须 percent-encode。"""
from app.core.config import MysqlSettings, RabbitMQSettings


def test_mysql_jdbc_url_encodes_special_chars_in_password():
    s = MysqlSettings(host="db", port=3306, user="user@x", password="p@ss:w/rd#1", db="shopsphere_reco")
    url = s.jdbc_url()
    # @ in password 不应破坏 host 解析
    assert "@db:3306/" in url, url
    # 必须 percent-encode（@ → %40 / : → %3A / / → %2F / # → %23）
    assert "p%40ss%3Aw%2Frd%231" in url
    assert "user%40x" in url
    assert url.endswith("?charset=utf8mb4")


def test_rabbitmq_amqp_url_encodes_special_chars():
    s = RabbitMQSettings(host="rabbit", port=5672, user="adm", password="p@ss", vhost="shopsphere")
    url = s.amqp_url()
    assert "p%40ss" in url
    assert "@rabbit:5672/" in url
