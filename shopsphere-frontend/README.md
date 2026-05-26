# shopsphere-frontend

ShopSphere 电商前端（React 18 + TypeScript + Vite + AntD 5）。

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 构建 | Vite 5 + @vitejs/plugin-react-swc |
| 框架 | React 18 + React Router 6 |
| 数据 | TanStack Query 5 + Zustand 4 + Immer |
| UI | AntD 5 + @ant-design/icons |
| HTTP | Axios 1.7 |
| 时间 | dayjs（utc / timezone 插件） |
| 测试 | Vitest + Testing Library + Playwright（E2E） |
| 规范 | TypeScript strict + ESLint 8 + Prettier 3 |

## Scripts

```bash
npm run dev        # 启动开发服务器 http://localhost:5173
npm run build      # tsc -b && vite build
npm run preview    # 本地预览构建产物
npm run typecheck  # tsc --noEmit
npm run lint       # ESLint（零警告门槛）
npm run format     # Prettier 写回
npm run test       # Vitest（单元/组件测试）
```

## 关键约定

- **API 路径前缀**：前端业务代码**永远写 `/api/xxx`**。dev 环境由 Vite proxy 自动重写为 `/api/v1/xxx` 后转发到 Gateway `:8080`；切版本时只需改 `vite.config.ts` 的 `rewrite` 一处（生产由 Nginx/Gateway 同义处理）。
- **禁止主动设置请求头** `X-User-Id` / `X-User-Name` / `X-Trace-Id`。这三个头由 Gateway 解析 JWT 后注入，前端若发送会被网关剥离。详见 `docs/api-contracts.md` §3。
- **时间统一 UTC**：后端 `OffsetDateTime` ISO-8601（带 `Z` 偏移），前端用 `dayjs.utc().local()` 渲染。
- **状态管理用 Zustand**，禁止引入 redux / mobx；时间库用 dayjs，禁止 moment（见根 `CLAUDE.md`）。

## 目录结构

详见 `.claude/CLAUDE.md`「工程结构」。F0.1 仅建骨架与 `.gitkeep`，业务文件随后续任务填充。

## 环境变量

复制 `.env.example` 为 `.env.local`：

```
VITE_API_BASE=/api
VITE_APP_NAME=ShopSphere
```
