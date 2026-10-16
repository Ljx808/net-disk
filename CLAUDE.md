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
    - 联调补丁：全局异常处理新增 404/405/415/请求体解析四类框架异常的处理器，修掉"URL 打错却显示系统繁忙"的假 500（起因见下方"踩坑记录"）
- 当前状态：**Day 6 已完成（含上述补丁）**，下一步 Day 7 做对账/巡检任务
- 已定路线（顺序不要随意调整）：**Day 6 下载（已完成）→ Day 7 对账/巡检任务 → Day 8 删除/回收站**
  - 对账任务排在删除之前的原因：删除功能一上线，回收站、物理删除、`ref_count` 归零这几个动作会立刻让孤儿文件的成因复杂化，先对账更容易定位
  - **Day 6 范围**（已完成）：按 `user_file` id 下载；校验该记录归属当前登录用户（越权防线）；按 `file_path` + `mime_type` 流式返回（不能整个读进内存）
  - **Day 6 明确不做**：分享链接、断点续传、在线预览、（实现时另定）批量下载、下载计数
- 后续待办：分享、容量配额、分片上传、在线预览

## 已知技术债
- **文件列表分页的排序字段没有索引（Day 5 发现，未修）**
  - 现象：`user_file` 现有索引 `idx_user_parent (user_id, parent_id, is_delete)` 与列表接口的 WHERE 条件完全匹配，但 `order by create_time desc, id desc` 不在索引里，数据量大时 MySQL 要额外做一次 filesort（把结果集读出来再排序）。
  - 建议形态：`(user_id, parent_id, is_delete, create_time)` —— 把排序字段放到索引末尾，让优化器直接利用索引的有序性跳过 filesort。
  - 暂不修的原因：当前数据量极小感觉不到，且改索引要动已验收的 Day 4 数据表；等做压测或数据量上来再改。
- **存储目录里有孤儿文件（Day 5 发现，未清理）**
  - 现象：`D:\net-disk-storage` 下的物理文件数多于 `real_file` 的记录数，多出来的文件没有任何数据库记录引用。截至 2026-10-17：磁盘 7 个文件、`real_file` 3 行，**孤儿 4 个**（全部在 `2026/10/16/` 下，都是 18/33 字节的小文件，明显是 curl 测试残留）。
  - **已确认的孤儿清单，作为对账任务的验收标准**（对账任务上线后必须精确检出这 4 个，一个不多一个不少；**这 4 个文件目前不手工删除，留给对账任务清理**）：
    ```
    2026/10/16/16c076956c3b4ae2bb72aea2ab9acfde   18 B    2026-10-16 15:01:00
    2026/10/16/4ce351bdc57b4aa8835c508d1551f267   33 B    2026-10-16 15:01:01
    2026/10/16/e1f6c725d5124e0485336f1f6edd7f24   33 B    2026-10-16 14:55:15
    2026/10/16/e3adf7e21a2344dab443a21b44071537   18 B    2026-10-16 14:55:15
    ```
  - 反向对账截至 2026-10-17 的基线：**为空**（没有"`real_file` 有记录但磁盘无文件"的情况）。这 4 个孤儿是唯一的差集。
  - 成因：**测试残留，不是代码缺陷**。两个可能来源，特征一致、无法再区分，都发生在 Day 4：① 建表 SQL 还没执行时上传，文件已经落盘但插入 `real_file` 报"表不存在"而失败；② 我用临时验证库联调时只覆盖了数据源、没有同时覆盖 `net-disk.storage.root-path`，文件落进了真实存储目录，元数据却留在了事后被 DROP 掉的临时库里。
  - 影响：只占磁盘空间，不影响功能（没有任何接口会读到它们）。
  - 清理方案（挂账到"对账任务"）：扫描存储根目录得到磁盘文件集合，查 `real_file` 得到应存集合，取差集就是孤儿候选；删除前必须满足两个条件 —— ① 该文件确实没有任何记录引用，② 文件创建时间早于 N 天。第 ② 条不能省：`saveNewRealFile` 是"先落盘、再插库"，中间存在短暂的时间窗口，不讲时间条件的对账会误删正在上传中的文件。
  - 反向对账（同样属于这个任务）：`real_file` 有记录但磁盘文件不存在的情况（Day 4 已记录过这个风险），以及 `ref_count` 与实际引用它的 `user_file` 行数是否对得上。
- **HTTP 状态码与业务码不完全一致（Day 6 引入，部分修补）**
  - 现状：全局异常处理只把 `40400~40499` 区间映射成真实 HTTP 404（为了配合浏览器和 axios 的通用错误处理），其余**业务异常**一律 **HTTP 200 + `Result.code` 里放业务码**。所以同一个业务错误，可能出现"HTTP 200 但 code=400"（如 `userFileId=abc` 的参数格式错误）这种表里不一的情况。
  - 影响：接口语义不统一，前端不能只看 HTTP 状态码判断成败，必须同时解析响应体；接入第三方或做网关限流/监控时容易误判。好处是改动面小、不影响既有前端拦截器逻辑（它本来就优先看 `code`）。
  - **已修补的部分（Day 6 联调补丁）**：框架自己已经给出语义的异常不再掉进兜底 500，改为返回真实状态码 + 可识别业务码 —— `NoResourceFoundException` → 404/`40400`、`HttpRequestMethodNotSupportedException` → 405/`40500`、`HttpMediaTypeNotSupportedException` → 415/`41500`、`HttpMessageNotReadableException` → 400/`40001`、`MultipartException` → 400/`40002`。
  - **仍未覆盖的（挂账）**：`MissingServletRequestPartException`（multipart 请求里少了必需的 `file` 部件）等其余 Servlet 绑定类异常仍会掉进兜底 500。实测复现：`curl -X POST /api/file/upload -H 'Content-Type: multipart/form-data; boundary=B1' --data-binary` 一个只有 `name="other"` 部件的报文 → `HTTP 200 + code:500 系统繁忙`（真实异常是 `Required part 'file' is not present.`，语义应是 400）。
  - **彻底做法（建议）**：让 `GlobalExceptionHandler` 继承 `ResponseEntityExceptionHandler` 并覆写 `handleExceptionInternal`，一次性接管整族框架异常，就不用再逐个枚举（枚举法天然会漏）。等 Day 8 之后接口定型再做。
  - 建议形态：后续统一成"HTTP 状态码 = 业务错误的语义类别（400/401/403/404/409/500），`Result.code` 保留更细的业务码"，即把 `toHttpStatus` 从"只认 404xx"扩展成按区间/枚举映射，并同步前端拦截器。
  - 暂不修的原因：Day 6 已按裁决只放开 404xx（+ 上述框架异常），全量改造会牵动 Day 2~Day 5 已验收的所有接口和前端拦截器，风险大于收益；等接口稳定或接入网关时一并做。
- **`docs/schema.sql` 建不出能跑的库（Day 6 发现，未修）**
  - 现象：`CREATE TABLE user (...)` 在文件末尾被注释掉了（标注"已有的 user 表结构，仅供对照，不要重复执行"），文件里实际只有 `real_file`、`user_file` 两张可执行的建表语句。但项目约定写明"三张表以 `docs/schema.sql` 为准"，两者对不上。
  - 影响：拿这个文件在一台干净机器/空库上初始化，得到的库缺 `user` 表，注册和登录会直接报 `Table 'xxx.user' doesn't exist`。Day 6 用临时验证库时就踩到了：临时库是我用这个文件建的，注册直接 500，只能临时 `CREATE TABLE net_disk_verify.user LIKE net_disk.user` 兜住才把回归跑完。
  - 建议形态：取消注释并改成 `CREATE TABLE IF NOT EXISTS \`user\` (...)`，这样"空库能建、已有库不报错"两个诉求同时满足，"不要重复执行"的顾虑就没了。
  - 暂不修的原因：`user` 表是 Day 2 手工建的，改不改由作者定；且文件是"唯一真相来源"，改动前要确认一遍与真实库结构完全一致（可直接用 `SHOW CREATE TABLE net_disk.user` 的输出核对）。

## 踩坑记录（排查案例）
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
  - **真实档**（真实例 + 真库 `net_disk` + 真存储 `D:\net-disk-storage`）：验证"真实数据形态 + 真实配置 + 真实构建"这三件事，临时档天然证明不了。启动时**只覆盖端口**（`--server.port=xxxx`），数据源和存储根路径都用 `application.yml` 原样值；**只发 GET（列表/下载）等只读请求，不写库**。确需写入的用例，先列出会写哪些行、哪些文件，测完核对基线。
  - **动手前先确认实例加载的是最新构建**：对比进程启动时间与 `target/classes` 的时间戳。Day 6 那次"8080 下载假 500"就是旧构建造成的（详见上方踩坑记录），先看这一条能省很多时间。
  - **真实档只读测试必须留指纹**：跑前跑后各取一次"真库三张表行数 + 存储目录 路径/大小/修改时间 的 md5"，两次一致才算零污染。