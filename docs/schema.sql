-- ============================================================================
-- 云盘系统 · 建表脚本（三张表）
-- 空库可自举：新库连上去直接跑本文件即可，已有的库重复跑也不会报错
--   （模板：mysql -uroot -p <库名> < docs/schema.sql）
-- ============================================================================
-- 设计要点（面试可讲）：把"物理文件"和"用户的文件记录"拆成两张表。
--   user      ：用户账号，密码存 BCrypt 密文，容量上限挂在用户上
--   real_file ：磁盘上真实存在的文件，按 md5 唯一，全站只存一份
--   user_file ：用户看到的文件，一个用户一条记录，指向 real_file
-- 好处：同一个文件被 10 个用户上传，磁盘上只有 1 份；
--       用户改名/移动/删除自己的记录，不影响别人的那份物理文件。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. 用户表
-- ----------------------------------------------------------------------------
-- 说明：本表的 DDL 由 `SHOW CREATE TABLE net_disk.user` 原样抄回，
--       包括列级 COLLATE utf8mb4_unicode_ci —— 不能省。
--       有些环境建库时用的不是 utf8mb4，列级 COLLATE 能保证无论库的默认字符集
--       是什么，这张表的排序规则都和线上一致（用户名唯一索引按这个规则比较）。
--       唯一没有照抄的是 `AUTO_INCREMENT=3`：那是运行时的自增值，属于数据不属于结构，
--       写进来会让新库从 3 开始编号。
CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码(BCrypt加密)',
  `email` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `storage_limit` bigint(20) NOT NULL DEFAULT '1073741824' COMMENT '网盘容量上限,默认1GB',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';


-- ----------------------------------------------------------------------------
-- 2. 物理文件表
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `real_file` (
  `id`          bigint(20)   NOT NULL AUTO_INCREMENT,
  `md5`         char(32)     NOT NULL COMMENT '文件内容MD5，去重和秒传的依据',
  `file_path`   varchar(500) NOT NULL COMMENT '相对存储根目录的路径，如 2026/10/16/ab12cd...',
  `file_size`   bigint(20)   NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
  `mime_type`   varchar(128) DEFAULT NULL COMMENT '内容类型，如 image/png',
  `ref_count`   int(11)      NOT NULL DEFAULT 0 COMMENT '被多少条 user_file 引用，为0时可清理物理文件',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 唯一索引：既保证一份内容只有一条记录，也是并发上传同一文件时的最终防线
  UNIQUE KEY `uk_md5` (`md5`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='物理文件表(按md5去重)';


-- ----------------------------------------------------------------------------
-- 3. 用户文件表
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_file` (
  `id`           bigint(20)   NOT NULL AUTO_INCREMENT,
  `user_id`      bigint(20)   NOT NULL COMMENT '所属用户',
  `real_file_id` bigint(20)   NOT NULL COMMENT '指向 real_file.id',
  `filename`     varchar(255) NOT NULL COMMENT '原始文件名，用户看到的那个名字',
  `parent_id`    bigint(20)   NOT NULL DEFAULT 0 COMMENT '父目录id，0=根目录。Day4 不用，为 Day5 目录结构预留',
  `is_delete`    tinyint(4)   NOT NULL DEFAULT 0 COMMENT '0正常 1在回收站',
  `create_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 文件列表页的查询条件是"某用户 + 某目录 + 未删除"，做成联合索引
  KEY `idx_user_parent` (`user_id`, `parent_id`, `is_delete`),
  -- 删物理文件前要反查有没有人还在用
  KEY `idx_real_file_id` (`real_file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户文件表(逻辑记录)';


-- ----------------------------------------------------------------------------
-- 4. 可选：删掉 Day2 遗留的空表 file_info
--    该表是旧的单表设计（物理文件信息挂在用户记录上），已改用上面两张表。
--    确认它确实是空表、且你不再需要后再执行（注意：本文件不包含这条，需手工执行）：
-- ----------------------------------------------------------------------------
-- SELECT COUNT(*) FROM file_info;   -- 先确认是 0
-- DROP TABLE `file_info`;
