# 云盘系统项目说明

## 项目定位
个人学习项目，用于校招简历，技术栈必须严格遵守以下约定。

## 技术栈
- 后端：Spring Boot 4.1.1（Java 17）+ MyBatis-Plus 3.5.17 + MySQL 5.7.44（实测服务端版本，`SELECT VERSION()` 得到；命令行客户端是 8.0）+ Redis + JWT（jjwt 0.13.0）
  - 注意建表和 SQL 要按 5.7 的语法来（不支持窗口函数、CTE、`DEFAULT (expression)` 等 8.0 特性）
  - 分页插件需要额外引 `mybatis-plus-jsqlparser`：MyBatis-Plus 从 3.5.9 起把依赖 JSqlParser 的内置拦截器（`PaginationInnerInterceptor` 等）从 `mybatis-plus-extension` 拆到了这个独立模块，starter 不再自带。**这个依赖不要当作多余的清理掉。**
- 前端：Vue3 组合式 API + Vite + Element Plus + Pinia + Axios
- 存储：本地磁盘存储，但需抽象 StorageService 接口便于将来切换 OSS
  - 存储根目录 `D:\net-disk-storage`，可在 application.yml 的 `net-disk.storage.root-path` 配置，代码中不得硬编码
  - 落盘规则：按天分目录（`2026/10/17/`）+ 32 位 UUID 文件名；数据库只存相对路径

## 开发约定
- 统一返回结构 Result<T>（code/message/data）
- 全局异常处理 @RestControllerAdvice
- 前端 API 统一封装在 src/api 目录
- 数据库三张表：user、real_file、user_file，**以 docs/schema.sql 为准**（旧的 file_info 表已删除）
  - real_file：物理文件，按 md5 唯一去重，全站共享一份
  - user_file：用户的文件记录，指向 real_file_id
- 密码加密只允许引入 spring-security-crypto，禁止引入 spring-boot-starter-security
- 每完成一个功能模块，为我总结：做了什么、关键设计、可能的面试考点

## 当前进度
- 已完成：
  - Day 1 环境搭建（前后端项目初始化）
  - Day 2 用户注册/登录后端（Result 统一返回、全局异常、BCrypt、JWT 拦截器 + ThreadLocal）
  - Day 3 前端登录/注册页、axios 封装、路由守卫、Vite 代理解决跨域
  - Day 4 单文件上传（real_file + user_file 双表设计、md5 服务端秒传、StorageService 抽象）
  - Day 5 文件列表（`GET /api/file/list` 分页查根目录未删除文件、两次查询组装、分页参数夹紧）
  - Day 6 文件下载（`GET /api/file/download/{userFileId}` 归属校验 + 流式返回、`Content-Disposition` 中文名 RFC 6266 编码、前端 axios+Blob 下载）
    - 联调补丁一：全局异常处理补上 404/405/415/请求体解析四类框架异常，修掉"URL 打错却显示系统繁忙"的假 500（起因见下方"踩坑记录"）
    - 联调补丁二（2026-10-17）：`GlobalExceptionHandler` 改为**继承 `ResponseEntityExceptionHandler` + 覆写 `handleExceptionInternal`**，整族框架异常收编进统一出口，不再逐个枚举（枚举法天然会漏，`MissingServletRequestPartException` 就是漏掉的那个）。随之批准三处状态码变更，见"已知技术债"里的状态码条目
  - Day 7 对账巡检（`GET /api/admin/reconcile`，**纯只读**）：盘有库无（孤儿候选：相对路径/大小/落盘时间/年龄）、库有盘无（失踪：real_file.id + file_path）、ref_count 审计（库值 vs user_file 实际行数，含 `is_delete=1`）三段报告；比对键 = 相对路径（正斜杠，与 `real_file.file_path` 同口径）；只报告不修复
    - Day 7 拉伸目标：手动清理（`POST /api/admin/reconcile/cleanup`）——白名单**实时重算** + 无 `user_file` 引用（含回收站行）+ 落盘 ≥24h 三道闸，逐条返回 deleted/skipped 及原因；2026-10-18 真实档 **4/4** 清理完成
  - Day 8 删除/回收站（正餐，2026-10-18）：
    - 软删除 `DELETE /api/file/{userFileId}`、恢复 `PUT /api/file/{userFileId}/restore`、回收站列表 `GET /api/file/recycle`（分页口径同文件列表，`pageSize` 夹紧 1~100）
    - `ref_count` 语义定案（见下方专节）：**含回收站行**；软删除/恢复只改 `is_delete`，不动 `ref_count`、不动磁盘；只有彻底删除才递减
    - 越权统一 **HTTP 404 + 40401**（不给 403，防 id 枚举）；重复删除/重复恢复 **HTTP 200 幂等**；恢复时同目录同名冲突 **HTTP 409 + 40901**，不自动改名
    - 2026-10-18 验收：临时档 59 项断言通过（16 项功能/冲突/越权/分页 + E1~E13 + 留 1 个回收站文件后巡检三段全零）；真实档只读通过，前后指纹 `2/3/4`、`sum_ref=4`、存储 `b8020321…` 完全一致
    - 拉伸目标（Day 9 已完成）：彻底删除 / 清空回收站
  - Day 9 彻底删除/清空回收站（2026-10-18）：
    - `DELETE /api/file/recycle/{userFileId}`（单条）、`DELETE /api/file/recycle`（清空本人回收站）
    - 强制两步走：只接受 `is_delete=1` 的记录；列表态文件 / 他人记录 / 不存在，统一 **HTTP 404 + 40401**（防 id 枚举）
    - 执行序：**DB 事务先提交，提交成功后再删物理文件**；删盘失败只留下孤儿（巡检可检出），绝不产生"库有盘无"
    - `ref_count` 递减到 0：删 `real_file` 行 + 删物理文件；减完仍 > 0：行与物理文件**必须保留**（秒传共享）
    - 清空 = 服务端按 `user_id + is_delete=1` 圈定清单 → 逐条复用单条逻辑（越权/状态/共享/物理删除全部复核），单条失败不中断整批，响应逐条结果
    - 配套补丁（方案 A）：上传时 `ref_count+1` 影响 0 行 → 删掉刚插入的 `user_file` 行并返回 **409/40901「文件已被清理，请重新上传」**，堵住"上传与彻底删除并发产生悬空引用"
    - 2026-10-18 验收：临时档 **109 项断言全过**（a 单条 / b 共享 / c 清空隔离 / d 越权与强制两步 / e R0~R2 / f 全零快照 / g E1~E12 / h E13）；真实档只读，前后指纹 `2/3/4`、`sum_ref=4`、存储 `b8020321…` 完全一致
  - Day 10 目录结构（2026-10-18，后端）：全程唯一一次 schema 演进，一次 ALTER 完成三件事
    - `user_file` 新增 `is_folder tinyint NOT NULL DEFAULT 0`；`real_file_id` 改为可空（文件夹行=NULL）；索引升级为 `idx_user_parent_time (user_id, parent_id, is_delete, is_folder, create_time)`，同一笔 ALTER 里 DROP 旧的 `idx_user_parent`（它是新索引的严格前缀）
    - 迁移脚本 `docs/migrate_day10.sql`；真实库执行前备份 **`D:\net-disk-backup\net_disk_before_p2_20261018_173844.sql`**（6350 字节，3 表 + 3 条 INSERT）；备份后 ALTER 成功，`SHOW CREATE TABLE net_disk.user_file` 与 `docs/schema.sql` 空库自举结果**逐字一致**（净掉 AUTO_INCREMENT）
    - 接口：`POST /api/file/folder`（parentId + foldername，同目录重名 409/40901，文件与文件夹共用命名空间）；`GET /api/file/list?parentId=`（文件夹在前、同组内 `create_time DESC, id DESC`）；`POST /api/file/upload` 增加 `parentId`（默认 0）；下载/秒传遇 `is_folder=1` → **HTTP 400 + 40003**；列表页支持下载文件夹拦截
    - 名称校验走 DTO `@Valid`：空白/超长(>200)/含 `/` 或 `\`/控制字符 → 400/40000；**只禁整名 `.` 和 `..`**（`my.folder` 放行，DTO 正则用 `^(?!\.{1,2}$)` 锚定整名）
    - 删除语义最小闭环：软删除文件夹仅限空文件夹——任何子行（**不过滤 is_delete**，回收站里的子项也算）→ 409/40902；恢复时父目录不存在或已删除 → 409/40903；彻底删除文件夹只删 `user_file` 行（无 ref_count、无磁盘动作，响应 `refCountAfter=null / physicalDeleted=false`）；递归删除挂账不做
    - 巡检口径：文件夹行不进 `real_file`，不产生孤儿；`loadRealFileMap` 过滤 null 的 real_file_id，取数统一走 `realFileOf`
    - 2026-10-18 验收：临时档 **146 项断言全过**（建夹/校验/排序/导航/秒传跨文件夹/非空夹/40903/跨用户/清空混合/Day 8 的 16 项/R0~R2/巡检三段全零/E1~E13）；真实档只读通过，前后指纹 `2/3/4`、`sum_ref=4`、存储 `b8020321…` 完全一致
    - 修掉一个真实缺陷：回收站页里**只有文件夹**时，`realFileMap.get(null)` 命中 `Map.of()` 抛 NPE → 500（详见踩坑记录）
  - Day 10 前端补齐（2026-10-18，P3）：不引新依赖，纯 Vue3 + Element Plus
    - `Home.vue`：新建文件夹弹窗（前端只挡空名，重名/非法字符以后端为准）、双击文件夹进入、面包屑导航栈 `navStack`（点击任意层级截断到该层）、返回上级、上传进当前目录、文件夹行 `el-tag` + 主题色名称、根目录与子目录空状态文案区分
    - 面包屑导航栈**不做 URL 同步**：刷新页面回根目录是可接受行为。URL 同步要处理"刷新时逐级校验祖先目录是否还存在"，与收益不成比例；需要直达目录/分享链接时另行设计
    - 目录已被删（列表返回 404/40401）：自动重置 `navStack` 回根 + 警告提示 + 递归重拉，用户不会卡在死目录里一直报错
    - `Recycle.vue`：文件夹标识、删除时间、恢复 / 彻底删除 / 清空三种操作全部带确认弹窗，清空弹窗写明总条数；`PurgeBatchVO` 部分失败时按 `failedCount` 渲染"成功 X 条、失败 Y 条"并逐条列出 `filename：reason`，**禁止 HTTP 200 就全部报成功**
    - `request.js`：blob 错误分支同时解析 `message` 与 `code`，挂到 `error.bizCode / error.bizMessage`；新增 `silentError` 配置，让列表/回收站自己弹一次、避免拦截器与页面重复提示；401 仍无条件清 token 并跳登录
    - 下载保留兜底：HTTP 2xx 但响应是 JSON（Content-Type 含 json）时解析 `message` 弹错，**绝不把错误 JSON 存成文件**
    - 秒传 `reused=true` 时提示"秒传成功：xxx（服务端已有该文件，未重复占用磁盘空间）"
    - 2026-10-18 走查：无头 Chrome + CDP（Node 24 原生 WebSocket，不引入依赖）**35 项全过 / 0 失败**，覆盖建夹→进夹→夹内上传→秒传→面包屑→删除/恢复→40902（含"子项只在回收站也算非空"）→空夹删除→回收站文件夹标识→40903→40901→彻底删除→清空条数→40003 blob→**目录被别处删除后自动回根 + toast「目录已不存在，已返回全部文件」**→A/B 隔离→200 条并发清空部分失败渲染；控制台无 JS 报错，仅负向用例产生的 6 条预期 4xx 网络日志（已按预期过滤）
    - 真实档只读：`testuser01` 根目录 4 个历史文件正常显示（`isFolder=false`）、回收站为空、巡检三段全零（磁盘 3 / `real_file` 3）、下载 id=1 的 md5 `3bdd68d9…` 与库/盘一致；前后指纹 `user=2 / real_file=3 / user_file=4`、`sum_ref=4`、存储 md5 `b8020321…`（旧口径 `%P %s %T@` 双算确认；P3 复核时一度换了 printf 格式得到 `24e9e272…`，逐文件 mtime 未变，按仪器漂移结案，见"工作方式"）
    - `npm run build` 通过（1.09s，仅 chunk 体积提示）
- 当前状态：**Day 10 已收工**（后端目录结构 + 前端补齐；后端临时档 146 项 + 前端走查 34 项全过、真实档只读零污染），下一步 P4 竣工
- 已定路线（顺序不要随意调整）：**Day 6 下载（已完成）→ Day 7 对账/巡检任务（已完成）→ Day 8 删除/回收站（已完成）→ Day 9 彻底删除/清空回收站（已完成）→ Day 10 目录结构（已完成）→ 前端补齐（已完成）→ P4 竣工（下一步）**
  - 对账任务排在删除之前的原因：删除功能一上线，回收站、物理删除、`ref_count` 归零这几个动作会立刻让孤儿文件的成因复杂化，先对账更容易定位
  - Day 7 范围确认（2026-10-18）：**只读对账巡检**为今日必做；**手动清理**是拉伸目标，必须在 Day 8 删除功能之前完成；`@Scheduled` 自动清理**不做**；目录结构顺延到 Day 8 之后
  - **孤儿清理 = 拉伸目标，状态：已完成（2026-10-18，真实档 4/4），时限满足（早于 Day 8）**
  - **Day 6 范围**（已完成）：按 `user_file` id 下载；校验该记录归属当前登录用户（越权防线）；按 `file_path` + `mime_type` 流式返回（不能整个读进内存）
  - **Day 6 明确不做**：分享链接、断点续传、在线预览、（实现时另定）批量下载、下载计数
- 后续待办：分享、容量配额、分片上传、在线预览、递归删除（文件夹非空时一键删子树，挂账）

## ref_count 语义（Day 8 定案）

`real_file.ref_count` = 指向该 `real_file` 的 `user_file` 行数，**包含回收站行（`is_delete=1`）**。

- 软删除 / 恢复：只改 `user_file.is_delete`，**绝不动 `ref_count`**，也不动磁盘文件；
- 只有**彻底删除**（物理 `DELETE user_file` 行）才 `ref_count - 1`；
- 递减到 0：删 `real_file` 行 + 删物理文件；递减后仍 > 0：物理文件**必须保留**（秒传共享，不能因为一个用户删了就把别人的文件删掉）；
- 巡检口径与之一致：`UserFileMapper.countByRealFileId()` 不过滤 `is_delete`，实际引用数与 `ref_count` 直接可比；
- 为什么回收站行也算引用：恢复只改一个标志位，物理文件必须还在场，否则恢复出来的是一条下载 404 的死记录。

## 彻底删除语义（Day 9 定案）

- **入口**：`DELETE /api/file/recycle/{userFileId}`（单条）、`DELETE /api/file/recycle`（清空本人回收站）。不使用 `DELETE /api/file/{id}`，同一个 URL 不承载"软删除/彻底删除"两种语义。
- **强制两步**：只接受 `is_delete=1`。列表态文件、他人记录、不存在、已被彻底删除，对外统一 **HTTP 404 + 40401**，不区分（防 id 枚举）。清空空回收站返回 200 + 全零，幂等。
- **执行序（不可颠倒）**：数据库事务先做完全部写操作并**提交**，之后再删物理文件。
  1. 查 `user_file`（`id + user_id`）→ 不在回收站则 404；
  2. `SELECT real_file ... FOR UPDATE` 锁住计数行，串行化同一物理文件的并发彻底删除；
  3. 条件删 `user_file` 行（`id + user_id + is_delete=1`），影响 0 行 → 404；
  4. `ref_count = ref_count - 1`（带 `ref_count > 0` 防下溢）；
  5. 减完为 0 → 条件删 `real_file` 行，把该行记入"待删物理文件"；仍 > 0 → 只返回，不删行不删盘；
  6. 事务提交后，按 `real_file.file_path` 调 `StorageService.delete`，逐条 `log`（路径/大小/md5）。
- **为什么先库后盘**：先删盘再提交，一旦回滚就产生"库有盘无"的失踪文件；按这个顺序最坏只是"库无盘有"的孤儿，巡检 `orphan` 段能检出。**物理删除失败只记日志、仍返回成功**（事务已提交，抛异常也回滚不了）。
- **共享保护**：`ref_count` 减完 > 0 时 `real_file` 行与物理文件必须保留，响应里 `shared=true / physicalDeleted=false` 明确告知。
- **清空**：服务端按登录用户的 `user_id + is_delete=1` 圈定清单，逐条复用与单条完全相同的逻辑（不做批量 SQL，避免绕过锁/计数/共享复核）；单条失败记入 `items` 并继续，响应含 `requestedCount / deletedCount / failedCount / items`。
- **上传竞态补丁**：上传的 `ref_count+1` 若影响 0 行，说明 `real_file` 刚被彻底删除，此时删掉刚插入的 `user_file` 行并返回 **409/40901**，避免留下指向不存在记录的悬空引用。

## 文件夹语义（Day 10 定案）

- **同表存储**：文件夹也是 `user_file` 的一行（`is_folder=1`、`real_file_id=NULL`），不建第二张表。列表、回收站、恢复、彻底删除全部复用现有状态机。
- **父子关系**：`parent_id` 指向父文件夹的 `user_file.id`，`0` 表示根目录。父目录校验条件固定为 `id + user_id + is_delete=0 + is_folder=1`，不满足一律 **404/40401**（不区分"不存在"和"是别人的"，防 id 枚举）。
- **命名空间**：同一目录下文件与文件夹共用一个命名空间（和操作系统一致），重名检查 `user_id + parent_id + filename + is_delete=0` → **409/40901**。回收站里的同名项不算冲突（沿用 Day 8 语义，恢复时才报 409）。
- **列表排序**：`ORDER BY is_folder DESC, create_time DESC, id DESC`，文件夹在前；`idx_user_parent_time` 的列顺序与之对齐，倒序扫描索引即可，不需要 filesort。
- **上传**：`POST /api/file/upload` 带可选 `parentId`（默认 0），落盘前先校验父目录。
- **下载与秒传**：所有"按 userFileId 取内容"的入口统一走 `assertNotFolder`，文件夹 → **HTTP 400 + 40003**。必须是真 400：前端用 blob 下载，HTTP 200 会把错误 JSON 存成文件。秒传只按 md5 命中 `real_file`，文件夹没有 md5，天然不参与。
- **软删除**：文件夹只有在**没有任何子行**时才能删。检查**不过滤 `is_delete`** —— 回收站里的子项仍然占着位置，否则恢复父目录会恢复出一个空壳、子项却指向它。有子项 → **409/40902**，提示先清空。递归删除不做（挂账）。
- **恢复**：父目录非 0 时必须存在且 `is_delete=0`，否则 **409/40903**，不做"自动恢复父目录"这种隐式操作。
- **彻底删除**：文件夹只删 `user_file` 行，不碰 `ref_count`、不碰磁盘；响应 `refCountAfter=null`、`physicalDeleted=false`。清空回收站逐条复用的逻辑对文件夹同样成立。
- **巡检**：文件夹行没有 `real_file_id`，不会进入孤儿/失踪/ref_count 任何一段。

## 已知技术债
- **文件列表分页的排序字段没有索引（Day 5 发现 → Day 10 已偿还）**
  - 现象（修复前）：`user_file` 的 `idx_user_parent (user_id, parent_id, is_delete)` 与列表接口的 WHERE 条件匹配，但 `order by create_time desc, id desc` 不在索引里，数据量大时要做 filesort。
  - **已修（2026-10-18，Day 10，与目录结构同一笔 ALTER）**：索引改为 `idx_user_parent_time (user_id, parent_id, is_delete, is_folder, create_time)`，原来的建议形态 `(user_id, parent_id, is_delete, create_time)` 有一条硬伤——列表排序第一列是 `is_folder DESC`（文件夹在前），不把 `is_folder` 放进索引，优化器仍然要 filesort。列顺序与 `ORDER BY is_folder DESC, create_time DESC, id DESC` 完全对齐（二级索引末尾隐含主键 id），倒序扫描即可。旧索引 `idx_user_parent` 是新索引的严格前缀，同一笔 ALTER 里 DROP，避免写放大。
  - 验证：真实库 4 行数据时优化器选择全表扫描（数据太少，这是正确选择）；`FORCE INDEX(idx_user_parent_time)` 下 `EXPLAIN` 的 `Extra` 只有 `Using where`，**没有 filesort**，证明索引形态可用。
- **回收站列表 `update_time` 排序无索引（Day 8 发现，Day 10 后观察中）**
  - 现象：`GET /api/file/recycle` 的 WHERE 是 `user_id=? AND is_delete=1`，`idx_user_parent_time (user_id, parent_id, is_delete, is_folder, create_time)` 的最长等值前缀仍只有 `user_id`（`is_delete` 在第三列，跳过 `parent_id` 用不上）；`ORDER BY update_time DESC` 也不在索引里，仍需 filesort。
  - 为什么查询不带 `parent_id`：回收站是用户维度的，有目录后也不该只显示根目录的删除项。
  - 建议形态：`(user_id, is_delete, update_time)`；当前数据量无感，等数据量上来再 ALTER。
- **存储目录里有孤儿文件（Day 5 发现 → 2026-10-18 已清理）**
  - 现象：`D:\net-disk-storage` 下的物理文件数多于 `real_file` 的记录数，多出来的文件没有任何数据库记录引用。截至 2026-10-17：磁盘 7 个文件、`real_file` 3 行，**孤儿 4 个**（全部在 `2026/10/16/` 下，都是 18/33 字节的小文件，明显是 curl 测试残留）。
  - **已确认的孤儿清单，作为对账任务的验收标准**（对账任务上线后必须精确检出这 4 个，一个不多一个不少；**这 4 个文件目前不手工删除，留给对账任务清理**）：
    ```
    2026/10/16/16c076956c3b4ae2bb72aea2ab9acfde   18 B    2026-10-16 15:01:00
    2026/10/16/4ce351bdc57b4aa8835c508d1551f267   33 B    2026-10-16 15:01:01
    2026/10/16/e1f6c725d5124e0485336f1f6edd7f24   33 B    2026-10-16 14:55:15
    2026/10/16/e3adf7e21a2344dab443a21b44071537   18 B    2026-10-16 14:55:15
    ```
  - **巡检已上线（2026-10-18）**：`GET /api/admin/reconcile` 在真实档精确检出 **孤儿 4 个，一个不多一个不少**（路径、大小、落盘时间与上表逐条一致），失踪 0（反向基线仍为空），`ref_count` 审计 3 条全一致。巡检只报告不删除。
  - **已清理（2026-10-18）**：`POST /api/admin/reconcile/cleanup` 三道闸逐条复核（① 白名单实时重算、② 无任何 `user_file` 引用含回收站、③ 落盘 ≥24h），上表 4 条**全部删除（4/4）**。复跑巡检：**三段全零**（孤儿 0 / 失踪 0 / ref_count 偏差 0）；磁盘 7 → **3**，与 `real_file` 3 行一一对应；库侧 `user=2 / real_file=3 / user_file=4` 及 `SUM(ref_count)=4` 不变。同一批路径再发一次 → 4 条全部在闸①被拒，验证了幂等。
  - 反向对账截至 2026-10-17 的基线：**为空**（没有"`real_file` 有记录但磁盘无文件"的情况）。这 4 个孤儿是唯一的差集。
  - 成因：**测试残留，不是代码缺陷**。两个可能来源，特征一致、无法再区分，都发生在 Day 4：① 建表 SQL 还没执行时上传，文件已经落盘但插入 `real_file` 报"表不存在"而失败；② 我用临时验证库联调时只覆盖了数据源、没有同时覆盖 `net-disk.storage.root-path`，文件落进了真实存储目录，元数据却留在了事后被 DROP 掉的临时库里。
  - 影响：只占磁盘空间，不影响功能（没有任何接口会读到它们）。
  - 清理方案（挂账到"对账任务"）：扫描存储根目录得到磁盘文件集合，查 `real_file` 得到应存集合，取差集就是孤儿候选；删除前必须满足两个条件 —— ① 该文件确实没有任何记录引用，② 文件创建时间早于 N 天。第 ② 条不能省：`saveNewRealFile` 是"先落盘、再插库"，中间存在短暂的时间窗口，不讲时间条件的对账会误删正在上传中的文件。
  - 反向对账（同样属于这个任务）：`real_file` 有记录但磁盘文件不存在的情况（Day 4 已记录过这个风险），以及 `ref_count` 与实际引用它的 `user_file` 行数是否对得上。
- **HTTP 状态码与业务码不完全一致（Day 6 引入 → 2026-10-17 框架异常部分已偿还 → 2026-10-18 409xx 已映射 → Day 10 400xx 已映射）**
  - **已偿还（2026-10-17，重构 `GlobalExceptionHandler`）**：`GlobalExceptionHandler` 改为**继承 `ResponseEntityExceptionHandler` + 覆写 `handleExceptionInternal`**，由父类一次性接管整族框架异常（不再逐个枚举——枚举法天然会漏，`MissingServletRequestPartException` 就是漏掉的那个），所有分支都从 `handleExceptionInternal` 这一个出口包成 `Result` 信封。框架异常的 code 统一取 **`HTTP 状态码 × 100`**：
    | 场景 | HTTP | code |
    |---|---|---|
    | URL 不存在 / 接口没加载（`NoResourceFoundException`） | 404 | `40400` |
    | 请求方法不支持（`HttpRequestMethodNotSupportedException`） | 405 | `40500` |
    | Content-Type 不支持（`HttpMediaTypeNotSupportedException`） | 415 | `41500` |
    | 参数校验失败（`@Valid`）、JSON 语法错、少了必需部件（`MissingServletRequestPartException`）、参数类型错（`pageNum=abc`）、文件超上限 | 400 | `40000` |
    | 未预期的意外异常（兜底） | 500 | `50000` |
  - **三处行为变更（2026-10-17 裁决批准，超出原先"只有 404xx 映射真 404"的最小化范围）**：
    ① `@Valid` 校验失败与参数类型错误（如 `pageNum=abc`）从 `HTTP 200 + code 400` 改为**真 HTTP 400** + `40000`；
    ② 未预期的意外异常从 `HTTP 200 + code 500` 改为**真 HTTP 500** + `50000`；
    ③ 业务码 **`40001`（请求体解析失败）、`40002`（multipart 格式错）废止**，统一并入 `40000`。粒度损失可接受——前端拦截器只看 `code` 是否为零，不依赖具体数值。
    批准理由：原"维持现状"是最小化热改动的权宜，不是对 HTTP 200 包裹的认可；真状态码让 nginx 日志和监控能看见错误。
  - **硬约束：所有响应（含上述每一个分支）必须保持 `Result` 信封（code/message/data）不变**，前端拦截器按信封解析的行为不受影响。
  - **仍未偿还的部分**：业务异常 `BusinessException` 里，**`400xx` → 真 HTTP 400（Day 10 新增，区间 [40000, 40100)）**、`404xx` → 真 HTTP 404、`409xx` → 真 HTTP 409（区间映射写法相同），其余业务码（401、400……）**仍然 HTTP 200 + body 里放 code**。这是有意的保守做法：改动会牵动 Day 2~Day 5 已验收的接口与前端拦截器。
  - **400xx 为什么必须真 400（Day 10）**：下载文件夹返回 40003；前端用 axios + blob 下载，HTTP 状态码为 200 时不会走 catch，错误 JSON 会被当成文件内容保存下来。区间起点 40000 意味着历史上的 `BusinessException(400)`（上传空文件）**不在区间内**，行为不变；Day 10 走查确认当时没有任何 `BusinessException(400xx)` 投掷点，影响为零。
  - **后续若统一**：方向是把 401/400 等历史业务码也纳入区间映射，并同步前端拦截器。
  - **实现上的两个坑（实测踩到，别凭直觉改代码）**：① 父类那个 `final handleException` 上挂的是 **20 个具体类型**的 `@ExceptionHandler`，这些类型在本类里**不能再写 `@ExceptionHandler`**，否则**启动即抛** `IllegalStateException: Ambiguous @ExceptionHandler method mapped for [...]`——想改提示只能覆写同签名的 `protected` 钩子（`handleMaxUploadSizeExceededException` / `handleMethodArgumentNotValid` / `handleTypeMismatch`）。② **`Exception.class` 父类不管**，所以本类必须自己保留 `@ExceptionHandler(Exception.class)` 兜底，否则意外异常会绕过 advice 走 Spring Boot 默认 `/error`，响应结构就不再是 `Result` 信封了。
- **`DELETE /api/file/{userFileId}` 会改变单段假路径的状态码（Day 8 引入，已知行为）**
  - 现象：新增 `@DeleteMapping("/{userFileId}")` 后，`GET /api/file/zzz` 从 404/40400 变成 **405/40500**（Spring 先按路径找到 DELETE 映射，再报方法不支持）；`DELETE /api/file/list` 带 token 会走进 `/{userFileId}`，`list` 转 Long 失败 → **400/40000**（无 token 时仍是拦截器先返回 401）。
  - 真正无映射的路径（如 `GET /api/file/zzz/zzz`）仍是 404/40400；异常处理分支本身没有回归。
  - 影响：只影响"拿单段假路径当 404 用例"的测试；业务接口不受影响。以后复跑异常回归时，404 用例要用两段以上假路径，避免被 405 干扰。
- **`docs/schema.sql` 建不出能跑的库（Day 6 发现 → 2026-10-17 已修）**
  - 现象（修复前）：`CREATE TABLE user (...)` 在文件末尾被注释掉了（标注"已有的 user 表结构，仅供对照，不要重复执行"），文件里实际只有 `real_file`、`user_file` 两张可执行的建表语句。但项目约定写明"三张表以 `docs/schema.sql` 为准"，两者对不上。
  - 影响（修复前）：拿这个文件在一台干净机器/空库上初始化，得到的库缺 `user` 表，注册和登录会直接报 `Table 'xxx.user' doesn't exist`。Day 6 起临时验证库时就踩到了，只能临时 `CREATE TABLE net_disk_verify.user LIKE net_disk.user` 兜住才把回归跑完。
  - **已修（2026-10-17）**：① `user` 表取消注释，DDL 由 `SHOW CREATE TABLE net_disk.user` **原样抄回**，含反引号与**列级 `COLLATE utf8mb4_unicode_ci`**——这一条不能省：有些环境建库的默认 collation 不是 `unicode_ci`（实测新建库默认 `utf8mb4_general_ci`），靠列级 COLLATE 才能保证无论库怎么建，这张表的排序规则都与线上一致；唯一没照抄的是 `AUTO_INCREMENT=3`，那是运行时的自增值、属于数据不属于结构，写进去会让新库从 3 开始编号。② 三张表统一 `CREATE TABLE IF NOT EXISTS`，重复执行不报错。③ 章节顺序调整为 `user` → `real_file` → `user_file`。
  - **验收（空库冒烟，2026-10-17）**：新建 `net_disk_smoke`（只指定 utf8mb4、库默认 collation 是 `utf8mb4_general_ci`）→ **只跑 `docs/schema.sql`** → 三表齐 → `SHOW CREATE TABLE user` 与线上净掉 `AUTO_INCREMENT` 后**逐字一致** → 注册 `smoke_user` 成功（BCrypt `$2a$10$`、60 字符）→ 登录/上传/列表/下载三张表全通 → DROP 库、删临时存储目录。
  - 遗留观察（不需要修）：`file_info`（Day 2 旧表）的 `DROP` 语句仍是注释，需要人工确认是空表后手工执行——旧表清理本来就不该进自动化脚本。
- **admin 端点暂无角色控制（Day 7 引入）**
  - 现象：`/api/admin/**` 只走 JWT 登录校验（现有拦截器拦 `/api/**`，只放行注册/登录），**任何登录用户都能调用**巡检接口，能看到存储相对路径和文件元信息。
  - 风险：当前接口纯只读、不泄露文件内容，个人本地项目可接受；一旦多人部署就是越权可观测，属于信息泄露面。
  - 收紧方向：引入角色体系（如 `user.role` 字段或独立角色表），拦截器按路径前缀校验 ADMIN 角色。本次不做。
- **`@Scheduled` 自动清理挂起（Day 7 决定）**
  - 现状：巡检接口已能稳定输出孤儿清单，但没有定时清理，孤儿只能靠人工处理。
  - 为什么不现在做：清理动作会**删物理文件**，属于不可逆操作；必须先让手动清理（带"早于 N 天"的时间阈值）稳定跑一段时间，确认不会误删"刚落盘、尚未入库"的上传中文件，再谈自动化。
  - 启动条件：手动清理稳定运行 + 至少一次人工核对清理结果无意外。
- **清空回收站一次性全量处理（Day 9 引入，未修）**
  - 现象：`purgeAllRecycle` 在单个请求内把该用户全部 `is_delete=1` 行取回内存、逐条彻底删除；回收站有几千条时会长时间占用请求与连接。
  - 为什么现在不改：个人项目量级远达不到；改分批/异步需要前端轮询或任务进度接口，属于接口形态变更。
  - 建议方向：超过阈值（如 200 条）改为分批 + 异步任务 + 进度查询；单条接口本身不受影响。

## 踩坑记录（排查案例）
- **Day 10：回收站里只有文件夹时列表 500，真凶是 `Map.of().get(null)`**
  - 现象：临时档矩阵跑到"回收站含文件夹行"用例时，`GET /api/file/recycle` 返回 **HTTP 500 + 50000**。此前同样的接口在回收站里只有文件时一直正常。
  - 堆栈：`NullPointerException at ImmutableCollections$MapN.get ← FileServiceImpl.listRecycle`。
  - 成因：文件夹行的 `real_file_id` 是 NULL，组装 VO 时直接 `realFileMap.get(userFile.getRealFileId())`。而 `loadRealFileMap` 在没有有效 id 时返回的是 `Map.of()` —— **不可变 Map 对 null 键抛 NPE**，`HashMap` 则只是返回 null。于是同一行代码在不同页面上表现不同。
  - **为什么只有"纯文件夹页"炸**：只要同一页里有一个文件，`Collectors.toMap` 产生的就是 `HashMap`，`get(null)` 返回 null，问题被掩盖；只有当整页全是文件夹（`realFileIds` 为空 → `Map.of()`）时才必现。文件列表接口有同样的写法，只是测试里根目录一直有文件，属于**潜伏缺陷**，这次一并修掉。
  - 修法：新增 `realFileOf(map, userFile)`，`realFileId == null` 直接返回 null，列表和回收站统一走它，不再依赖 Map 实现是否允许 null 键。教训：**`Map.of()` 的"空 map"不是 `HashMap` 的空 map**，涉及可能为 null 的键时不要用它，或者先把判空收敛到一个方法里。
- **Day 9：事务边界与自调用，两个坑一起踩**
  - **`@Transactional` 同类自调用失效**：清空回收站要逐条调用单条彻底删除逻辑，如果把两者写在同一个类里、只在单条方法上加 `@Transactional`，内部调用不经过 Spring 代理，事务注解**静默不生效**——没有报错，只是回滚不再发生。解法是用 `TransactionTemplate` 把数据库块显式包起来，`execute()` 正常返回即已提交。
  - **物理删除必须在事务提交之后**：如果给整个方法加 `@Transactional`、在方法体内删盘，删盘发生在提交之前；一旦后续回滚，磁盘文件已经没了，产生"库有盘无"的失踪文件（巡检只能报告、无法恢复）。用 `TransactionTemplate` 把"DB 块"和"删盘"分成前后两段，提交成功后才删盘，最坏情况只产生可被巡检发现的孤儿。
  - **验收脚本自己的坑**：清空混合场景里断言"磁盘数不变"是错的——共享文件会保留，但独占文件仍会正常删盘，正确断言是 `D2 - 独占文件数`。看到 FAIL 先核对断言口径，不要先怀疑实现。
- **Day 6：8080 上下载报"系统繁忙"，真凶是旧构建 + 假 500**
  - 现象：前端点下载弹出"系统繁忙，请稍后重试"，Network 里看是 `GET /api/file/download/1` → **HTTP 200** + 一小段 JSON（不是 404、不是 500）。临时环境（8081 + 临时库 + 临时存储）同一功能全绿，只有"真实例"挂。
  - 排查手法（关键是不猜、用对照实验缩小范围）：
    1. 不带 token 打一个**确定存在**的接口（`/api/file/list`）→ 401：说明拦截器正常，且"有映射"时会先过拦截器。
    2. 不带 token 打 `/api/file/download/1` → **假 500**，和一个**编造的假路径** `/api/file/zzz` 的响应字节级一致 → 说明这个路径上**根本没有处理器**（有处理器就会像第 1 步那样先返回 401）。
    3. 对比进程与构建的时间戳：8080 进程是 01:27:34 由 IDEA 启动的（classpath 指向 `target/classes`），而 Day 6 的类 02:26:54 才编译出来 → **JVM 里是启动那一刻加载的旧类，根本没有 `/api/file/download` 这个映射**。
    4. 用同一份配置（只覆盖端口）起新实例验证真数据：三个真实文件下载全部 200，size 与 md5 跟库里记录逐字节一致。
  - 两层根因，缺一不可：
    - **触发层**：生产实例跑的是旧构建（改了代码没重启）。
    - **掩盖层（真缺陷）**：Spring 对"路径不存在"抛的 `NoResourceFoundException` 被 `@ExceptionHandler(Exception.class)` 兜底吞掉，翻译成 `HTTP 200 + code:500 系统繁忙`——把一个"你 URL 打错了/接口没加载"伪装成"服务端故障"，把排查方向彻底带偏。已在同一轮回补丁修掉（见技术债里的状态码条目）。
  - 教训：① 兜底异常处理不能贪宽，框架异常的语义要放行；② "临时环境全绿、真实例全挂"时，第一件要核对的是**进程加载的构建版本**，而不是业务数据形态；③ 报告里"没跑生产实例"这一项必须补齐（已写进下面的测试矩阵）。

## 工作方式
- 一次只做一个小功能，完成后停下来等我确认
- 生成代码后，用注释解释关键逻辑，并对新手解释用到的核心概念
- 不使用我技术栈之外的重量级中间件（不用Kafka/ES/Nacos）
- **联调测试纪律：临时库 + 临时存储目录必须双覆盖**。跑测试实例时 `--spring.datasource.url` 和 `--net-disk.storage.root-path` 两个参数都要覆盖到临时位置，真实库 `net_disk` 和真实存储目录 `D:\net-disk-storage` 零污染，测试数据用完即删。
  （Day 4 只覆盖了数据源、漏了存储目录，是上面那 4 个孤儿的成因之一，所以这条写死在这里。）
- **验收矩阵必须有两档：临时档 + 真实档**。两档都要过，缺一档不算验收完。
  - **临时档**（临时库 + 临时存储目录）：唯一能放开写操作的地方。上传、删除、注册这类会改数据的用例只在这里跑，可以随便造数据、故意调小参数（如把 `max-file-size` 调成 1KB 来测超限），跑完 DROP 库、删目录。
    - **起临时库必须且只能用 `docs/schema.sql`**：手工 `CREATE TABLE`、或从别的库 `CREATE TABLE ... LIKE` 抄结构，都不算数——那样只能证明"被测代码能跑"，证明不了"这份 schema 文件本身在空库上自举得出来"。schema 漂移（少张表、少个列、collation 不一致）是最容易攒、又最难在开发机上发现的债，而每次起临时库都是一次免费的校验机会。2026-10-17 `docs/schema.sql` 补上 `user` 表后，这条升级为硬纪律（当时就是因为起临时库时只能 `LIKE` 抄表，才暴露了 schema 文件缺 `user` 表）。
  - **真实档**（真实例 + 真库 `net_disk` + 真存储 `D:\net-disk-storage`）：验证"真实数据形态 + 真实配置 + 真实构建"这三件事，临时档天然证明不了。启动时**只覆盖端口**（`--server.port=xxxx`），数据源和存储根路径都用 `application.yml` 原样值；**只发 GET（列表/下载）等只读请求，不写库**。确需写入的用例，先列出会写哪些行、哪些文件，测完核对基线。
  - **动手前先确认实例加载的是最新构建**：对比进程启动时间与 `target/classes` 的时间戳。Day 6 那次"8080 下载假 500"就是旧构建造成的（详见上方踩坑记录），先看这一条能省很多时间。
  - **真实档只读测试必须留指纹**：跑前跑后各取一次"真库三张表行数 + 存储目录 路径/大小/修改时间 的 md5"，两次一致才算零污染。
  - **指纹算法必须版本化、口径变更必须声明并双算**：同一组文件换一个 `find -printf` 格式，md5 必然不同。历史基线 `b8020321…` 的口径是 `find … -printf '%P %s %T@\n' | sort | md5sum`（相对路径 + 字节数 + 秒.纳秒时间戳）。2026-10-18 P3 复核时误用了 `'%P|%s|%TY-%Tm-%Td %TH:%TM:%TS\n'` 得到 `24e9e272…`，一度被当成"基线断裂"；用旧口径重算立即复现 `b8020321…`，逐文件 mtime 仍停在 10-16/10-17、无任何变更 → 结案为**仪器漂移**，不是数据变更。以后换口径必须同时给出旧口径的重算值（双算），否则不得下"指纹变了"的结论。