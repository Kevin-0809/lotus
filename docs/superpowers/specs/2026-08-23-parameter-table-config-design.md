# 参数表与排除表配置设计

## 目标

使用一张可维护的配置表，按类型保存精确表名：参数表参与结构和数据比对，排除表优先级更高并从最终比对列表移除。

## 数据模型

新增 `compare_table_config` 表：

- `id`：主键
- `table_name`：精确表名
- `table_type`：`PARAMETER` 或 `EXCLUDE`
- `enabled`：是否启用
- `remark`：备注
- `created_at`、`updated_at`
- `(table_name, table_type)` 唯一约束

## 执行规则

后端读取启用的 `PARAMETER` 表名作为数据比对白名单；启用的 `EXCLUDE` 表名从结构和数据比对候选集中移除。现有请求中的 include/exclude 仍作为额外筛选条件，最终候选集合为：

`参数表白名单 ∩ include 条件 - exclude 条件 - 配置排除表`

没有配置参数表时保持当前兼容行为；配置参数表后，`BOTH` 和 `DATA` 均自动使用白名单。定时任务复用同一配置表。

## 界面

新增“表配置”导航页，提供类型切换、表名搜索、新增、编辑、启用/停用和删除。新增/编辑表单使用精确表名和类型下拉框，不再要求正则表达式。

## 验证

- repository/controller/service 单元测试覆盖类型筛选、排除优先和 CRUD。
- 前端检查两个类型页签和 CRUD 请求。
- `mvn test -q` 通过。
