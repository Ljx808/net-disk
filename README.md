# 个人云盘（net-disk）

一个用于校招简历的个人云盘系统：支持注册登录、文件上传与秒传、目录管理、下载、回收站和对账巡检。后端采用 Spring Boot 4.1.1 + MyBatis-Plus + MySQL 5.7 + JWT，前端采用 Vue 3 + Vite + Element Plus + Pinia + Axios。物理文件按 md5 全站去重，存储层抽象为 `StorageService`，便于以后切换 OSS。

## 端点总表

所有业务响应统一为 `Result<T>`：`{ code, message, data }`。HTTP 状态码按业务码区间映射：`400xx → 400`、`404xx → 404`、`409xx → 409`；401 为真 401；未预期异常为真 500 + `50000`。

| 方法 | 路径 | 说明 | 关键错误码 |
|---|---|---|---|
| POST | `/api/user/register` | 注册 | `40000` 参数校验失败 |
| POST | `/api/user/login` | 登录，`data` 即 JWT token | `40000`；密码错误为 HTTP 200 + 业务码 `500` |
| GET | `/api/user/info` | 当前登录用户信息 | `401` |
| POST | `/api/file/upload` | 上传单文件（multipart：`file`、可选 `parentId`），md5 命中时秒传 | `40000`、`40401` 父目录无效、`40901` 上传与彻底删除竞态 |
| POST | `/api/file/folder` | 新建文件夹（JSON：`parentId`、`foldername`） | `40000`、`40401`、`40901` 同目录重名 |
| GET | `/api/file/list?pageNum=&pageSize=&parentId=` | 当前目录分页列表，文件夹在前 | `401`、`40401` |
| GET | `/api/file/recycle?pageNum=&pageSize=` | 回收站分页列表 | `401` |
| GET | `/api/file/download/{userFileId}` | 流式下载（响应是文件二进制，不是 Result） | `40401`、`40003` 文件夹不可下载 |
| DELETE | `/api/file/{userFileId}` | 软删除（移入回收站），幂等 | `40401`、`40902` 文件夹非空 |
| PUT | `/api/file/{userFileId}/restore` | 从回收站恢复，幂等 | `40401`、`40901` 同目录同名、`40903` 父目录已删 |
| DELETE | `/api/file/recycle/{userFileId}` | 彻底删除单条（只接受回收站记录） | `40401` |
| DELETE | `/api/file/recycle` | 清空本人回收站，逐条返回结果 | `401`；部分失败见 `items` |
| GET | `/api/file/config` | 上传大小限制 | `401` |
| GET | `/api/admin/reconcile` | 对账巡检（只读，三段报告） | `401` |
| POST | `/api/admin/reconcile/cleanup` | 手动清理孤儿（白名单 + 无引用 + ≥24h 三道闸） | `401` |

**关键错误码**

| code | 含义 |
|---|---|
| `401` | 未登录 / token 无效或过期 |
| `40000` | 参数校验失败、JSON 语法错误、缺少 multipart 部件、类型转换失败 |
| `40401` | 文件/文件夹不存在、不属于当前用户、父目录不存在或已删除（统一不区分，防 id 枚举） |
| `40901` | 同目录重名（建夹/上传/恢复）、上传与彻底删除竞态 |
| `40902` | 文件夹非空（回收站里的子项也算非空） |
| `40903` | 恢复时父目录已删除或不存在 |
| `40003` | 文件夹不支持下载 |
| `50000` | 未预期的服务端异常 |

## 架构

### 三张表

| 表 | 职责 |
|---|---|
| `user` | 账号、BCrypt 密码、容量上限 `storage_limit` |
| `real_file` | 磁盘上真实存在的物理文件，按 `md5` 唯一，全站一份；记录相对路径、大小、mime、`ref_count` |
| `user_file` | 用户看到的文件记录：文件行指向 `real_file_id`，文件夹行 `is_folder=1` 且 `real_file_id=NULL`；`parent_id=0` 表示根目录；`is_delete=1` 表示在回收站 |

### ref_count 语义（定案）

`real_file.ref_count` = 指向该 `real_file` 的 `user_file` 行数，**包含回收站行（`is_delete=1`）**。

- 软删除 / 恢复：只改 `user_file.is_delete`，**绝不动 `ref_count`**，也不动磁盘文件；
- 只有**彻底删除**（物理 `DELETE user_file` 行）才 `ref_count - 1`；
- 递减到 0：删 `real_file` 行 + 删物理文件；递减后仍 > 0：物理文件**必须保留**（秒传共享，不能因为一个用户删了就把别人的文件删掉）；
- 巡检口径与之一致：`countByRealFileId()` 不过滤 `is_delete`，实际引用数与 `ref_count` 直接可比；
- 为什么回收站行也算引用：恢复只改一个标志位，物理文件必须还在场，否则恢复出来的是一条下载 404 的死记录。

### 存储布局

- 根目录由 `net-disk.storage.root-path` 配置（默认 `D:/net-disk-storage`），代码中不硬编码；
- 落盘规则：`yyyy/MM/dd/<32 位 UUID>`，例如 `2026/10/18/1c77ae9c6ff74d33b59c661d8289f2ed`；
- 数据库只存相对路径，物理文件通过 `StorageService` 接口读写，将来可替换为 OSS；
- 彻底删除的执行序固定为 **先提交数据库事务，再删物理文件**：最坏只留下可被巡检发现的孤儿，绝不产生"库有盘无"的失踪文件。

### 对账巡检三段口径

| 段 | 口径 |
|---|---|
| 孤儿（盘有库无） | 扫描存储根目录，相对路径不在 `real_file.file_path` 中；清理必须额外满足"无任何 `user_file` 引用（含回收站）"且"落盘 ≥24h" |
| 失踪（库有盘无） | `real_file` 有记录但磁盘文件不存在；只报告，不修复 |
| ref_count 审计 | `real_file.ref_count` 与 `countByRealFileId()`（含回收站行）逐条比对 |

巡检是纯只读接口；清理接口必须显式传入路径数组，服务端实时重算白名单，不会自行扩大删除范围。

### 存储指纹（版本化）

真实档只读验收前后各取一次指纹，命令原文：

```bash
find /d/net-disk-storage -type f -printf '%P %s %T@\n' | sort | md5sum
```

基线值：`b802032138ef8ac255ae416f369b2d23`。**换用其它 `find -printf` 格式得到的 md5 不可与基线比较**；口径变更必须声明并同时给出旧口径重算值（双算）。

## 设计取舍

项目明确接受的技术债、每一条的结论/理由/日期，见 [CLAUDE.md](CLAUDE.md) 的「技术债终审」。面试被问"项目有什么不足"时可直接从那里取答案，例如：

- 同目录同名的 TOCTOU 没有唯一索引兜底——为了保留回收站允许多条同名历史的语义，没有加条件唯一索引；
- 历史业务码仍保存在 HTTP 200 响应体里（状态码统一未完成）；
- `/api/admin/**` 暂无角色控制，只有登录校验；
- 孤儿清理没有定时任务，只有手动接口；
- 回收站列表排序仍需 filesort、前端主包超过 500 kB 未分割等低优项。

## 本地运行

前置：JDK 17、Maven、MySQL 5.7（8.0 客户端可用）、Node.js 18+。Redis 已在依赖中预留，当前功能未使用，不启动不影响运行。

```bash
# 1. 建库并导入表结构（空库可自举，重复执行不报错）
mysql -uroot -p -e "CREATE DATABASE net_disk DEFAULT CHARACTER SET utf8mb4;"
mysql -uroot -p net_disk < docs/schema.sql

# 2. 按需修改 backend/src/main/resources/application.yml
#    - spring.datasource.username / password
#    - net-disk.storage.root-path（目录需可写，不存在会自动创建）

# 3. 启动后端
cd backend
mvn spring-boot:run

# 4. 启动前端
cd frontend
npm install
npm run dev
```

浏览器打开 <http://localhost:5173>，注册账号后即可登录使用。开发环境下 Vite 把 `/api` 代理到 `http://localhost:8080`，无需额外配置跨域。

## 验证方式

项目采用"临时档 + 真实档"双档验收：临时档使用独立临时库与临时存储目录，跑写操作与异常回归；真实档只覆盖端口、只发 GET，并在跑前跑后比对存储指纹，确保零污染。临时库必须且只能由 `docs/schema.sql` 自举，用于校验 schema 文件本身。
