"""MQ payload schemas（与 Java 生产方约定，契约 §8 / docs/mq-topology.md）。

- UserBehaviorEvent：User 服务 BehaviorEventPublisher 发出，payload 自带 eventId（32hex UUID）
- OrderCreatedEvent：Order 服务 outbox 发出，无 per-item eventId —— 消费端按 (orderId,productId) 派生
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field

ActionType = Literal["view", "cart", "order"]


class UserBehaviorEvent(BaseModel):
    """`shopsphere.behavior` rk `user.behavior`。"""

    model_config = ConfigDict(extra="ignore")

    eventId: str
    userId: int
    itemId: int
    actionType: ActionType
    ts: datetime
    extra: Optional[dict] = None


class OrderItemPayload(BaseModel):
    model_config = ConfigDict(extra="ignore")

    productId: int
    productName: str
    price: Decimal
    quantity: int


class OrderCreatedEvent(BaseModel):
    """`shopsphere.order` rk `order.created`。"""

    model_config = ConfigDict(extra="ignore")

    orderId: int
    orderNo: str
    userId: int
    totalAmount: Decimal
    ts: datetime
    items: list[OrderItemPayload] = Field(default_factory=list)
