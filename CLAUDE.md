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
- 当前状态：**Day 5 已完成**，下一步做下载或删除/回收站
- 待办：下载、删除/回收站、分享、容量配额、分片上传

## 已知技术债
- **文件列表分页的排序字段没有索引（Day 5 发现，未修）**
  - 现象：`user_file` 现有索引 `idx_user_parent (user_id, parent_id, is_delete)` 与列表接口的 WHERE 条件完全匹配，但 `order by create_time desc, id desc` 不在索引里，数据量大时 MySQL 要额外做一次 filesort（把结果集读出来再排序）。
  - 建议形态：`(user_id, parent_id, is_delete, create_time)` —— 把排序字段放到索引末尾，让优化器直接利用索引的有序性跳过 filesort。
  - 暂不修的原因：当前数据量极小感觉不到，且改索引要动已验收的 Day 4 数据表；等做压测或数据量上来再改。
- **存储目录里有孤儿文件（Day 5 发现，未清理）**
  - 现象：`D:\net-disk-storage` 下的物理文件数多于 `real_file` 的记录数，多出来的文件没有任何数据库记录引用。截至 2026-10-17：磁盘 7 个文件、`real_file` 3 行，**孤儿 4 个**（都在 `2026/10/16/` 目录下）。
  - 成因：**测试残留，不是代码缺陷**。两个可能来源，特征一致、无法再区分，都发生在 Day 4：① 建表 SQL 还没执行时上传，文件已经落盘但插入 `real_file` 报"表不存在"而失败；② 我用临时验证库联调时只覆盖了数据源、没有同时覆盖 `net-disk.storage.root-path`，文件落进了真实存储目录，元数据却留在了事后被 DROP 掉的临时库里。
  - 影响：只占磁盘空间，不影响功能（没有任何接口会读到它们）。
  - 清理方案（挂账到"对账任务"）：扫描存储根目录得到磁盘文件集合，查 `real_file` 得到应存集合，取差集就是孤儿候选；删除前必须满足两个条件 —— ① 该文件确实没有任何记录引用，② 文件创建时间早于 N 天。第 ② 条不能省：`saveNewRealFile` 是"先落盘、再插库"，中间存在短暂的时间窗口，不讲时间条件的对账会误删正在上传中的文件。
  - 反向对账（同样属于这个任务）：`real_file` 有记录但磁盘文件不存在的情况（Day 4 已记录过这个风险），以及 `ref_count` 与实际引用它的 `user_file` 行数是否对得上。

## 工作方式
- 一次只做一个小功能，完成后停下来等我确认
- 生成代码后，用注释解释关键逻辑，并对新手解释用到的核心概念
- 不使用我技术栈之外的重量级中间件（不用Kafka/ES/Nacos）``