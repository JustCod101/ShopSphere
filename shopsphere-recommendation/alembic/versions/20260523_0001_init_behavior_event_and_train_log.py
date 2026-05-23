"""init behavior_event and t_train_log

Revision ID: 0001
Revises:
Create Date: 2026-05-23

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "behavior_event",
        sa.Column("id", sa.BigInteger, primary_key=True, autoincrement=True),
        sa.Column("event_id", sa.String(64), nullable=False),
        sa.Column("user_id", sa.BigInteger, nullable=False),
        sa.Column("item_id", sa.BigInteger, nullable=False),
        sa.Column("action_type", sa.String(16), nullable=False, comment="view/cart/order"),
        sa.Column("source", sa.String(16), nullable=False, comment="behavior/order"),
        sa.Column("ts", sa.DateTime(timezone=True), nullable=False, comment="UTC,DATETIME(3)"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP(3)"),
            nullable=False,
        ),
        sa.UniqueConstraint("event_id", name="uk_event_id"),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_unicode_ci",
    )
    op.create_index("idx_user_ts", "behavior_event", ["user_id", "ts"])
    op.create_index("idx_item_ts", "behavior_event", ["item_id", "ts"])

    op.create_table(
        "t_train_log",
        sa.Column("id", sa.BigInteger, primary_key=True, autoincrement=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("item_count", sa.Integer, nullable=True),
        sa.Column("user_count", sa.Integer, nullable=True),
        sa.Column("behavior_count", sa.BigInteger, nullable=True),
        sa.Column("status", sa.String(16), nullable=False, comment="RUNNING/SUCCESS/FAILED"),
        sa.Column("error", sa.String(500), nullable=True),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_unicode_ci",
    )
    op.create_index("idx_started_at", "t_train_log", ["started_at"])


def downgrade() -> None:
    op.drop_index("idx_started_at", table_name="t_train_log")
    op.drop_table("t_train_log")
    op.drop_index("idx_item_ts", table_name="behavior_event")
    op.drop_index("idx_user_ts", table_name="behavior_event")
    op.drop_table("behavior_event")
