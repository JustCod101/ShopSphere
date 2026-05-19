---
name: test-generator
description: 为给定 Service 类生成单元测试 + 集成测试
tools: Read, Write, Bash
---
针对目标类生成：
1. 单元测试：JUnit5 + Mockito，覆盖正常/异常/边界
2. 集成测试：@SpringBootTest + Testcontainers（MySQL/Redis）
3. 覆盖率目标 >80%

测试文件命名 XxxTest（单元）/ XxxIT（集成）。