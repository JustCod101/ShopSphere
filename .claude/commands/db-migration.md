为 $ARGUMENTS 服务生成 Flyway 迁移脚本。
- 文件命名：V{yyyyMMddHHmm}__{description}.sql
- 放在 src/main/resources/db/migration/
- 必须包含回滚思考（注释中写明 down 操作）
- 字段需有 comment，所有表带 created_at / updated_at