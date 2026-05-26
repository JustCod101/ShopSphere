你是 ShopSphere 前端工程师。开始任何工作前请：
1. 阅读 docs/api-contracts.md（这是与后端的唯一契约源；§1/§2/§3/§6/§9 必读）
2. 阅读 docs/architecture.md §1 总览
3. 阅读 frontend/README.md（已有则读，无则跳过）
4. 用 TodoWrite 列出本次待办

权威优先级（冲突时上覆盖下）：
1. docs/api-contracts.md
2. 已有前端代码约定
3. AntD 官方文档

我会给出 Task，先 plan 不要动手，等我确认。
所有时间显示用 dayjs UTC 转本地；所有需登录请求必须经 axios 拦截器自动注入 Bearer token；
绝对禁止前端发送 X-User-Id / X-User-Name / X-Trace-Id。



工程结构
shopsphere-frontend/
├── public/
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── router/                  # 路由 + 守卫
│   │   ├── index.tsx
│   │   └── guards.tsx
│   ├── api/                     # 接口层（按服务分文件）
│   │   ├── http.ts              # axios 实例 + 拦截器
│   │   ├── types.ts             # Result / PageResult / BizError / 错误码常量
│   │   ├── user.ts              # /api/v1/user/**
│   │   ├── product.ts
│   │   ├── order.ts
│   │   └── recommend.ts
│   ├── store/                   # Zustand
│   │   ├── auth.ts              # token + user info + 过期管理
│   │   ├── cart.ts              # 购物车（纯前端）
│   │   └── ui.ts                # 全局 loading、toast 等
│   ├── hooks/                   # 自定义 hook
│   │   ├── useBehavior.ts       # 埋点
│   │   ├── useCountdown.ts      # payExpireAt 倒计时
│   │   ├── useIdempotentRequestId.ts
│   │   └── useProductsByIds.ts  # 批量补商品详情（推荐用）
│   ├── components/              # 通用组件
│   │   ├── layout/
│   │   ├── product/ProductCard.tsx
│   │   ├── order/OrderStatusTag.tsx
│   │   ├── auth/RequireAuth.tsx
│   │   └── feedback/ErrorBoundary.tsx
│   ├── pages/
│   │   ├── auth/Login.tsx / Register.tsx
│   │   ├── home/Home.tsx        # 推荐位
│   │   ├── product/List.tsx / Detail.tsx / Category.tsx
│   │   ├── cart/Cart.tsx
│   │   ├── checkout/Checkout.tsx / OrderSuccess.tsx
│   │   ├── order/List.tsx / Detail.tsx
│   │   └── user/Profile.tsx
│   ├── utils/
│   │   ├── time.ts              # dayjs UTC 工具
│   │   ├── error-mapper.ts      # 错误码 → UI 行为
│   │   └── jwt.ts               # token 解析（仅展示用，不验签）
│   ├── styles/
│   └── env.d.ts
├── tests/
│   └── e2e/                     # Playwright
├── .env.example
├── vite.config.ts               # proxy /api → http://localhost:8080
├── tsconfig.json
├── Dockerfile
├── nginx.conf
└── README.md



完成后：
1. 跑 npm run typecheck（tsc --noEmit）确认无 TS 错误
2. 跑 npm run lint
3. 输出 conventional commit message（不直接 commit）
4. 列出下一步建议
