-- ============================================================================
-- 云盘系统 · 文件表结构（Day 4）
-- 在 DataGrip 里连上 net_disk 库后执行本文件
-- ============================================================================
-- 设计要点（面试可讲）：把"物理文件"和"用户的文件记录"拆成两张表。
--   real_file ：磁盘上真实存在的文件，按 md5 唯一，全站只存一份
--   user_file ：用户看到的文件，一个用户一条记录，指向 real_file
-- 好处：同一个文件被 10 个用户上传，磁盘上只有 1 份；
--       用户改名/移动/删除自己的记录，不影响别人的那份物理文件。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. 物理文件表
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
-- 2. 用户文件表
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
-- 3. 可选：删掉 Day2 遗留的空表 file_info
--    该表是旧的单表设计（物理文件信息挂在用户记录上），已改用上面两张表。
--    确认它确实是空表、且你不再需要后再执行：
-- ----------------------------------------------------------------------------
-- SELECT COUNT(*) FROM file_info;   -- 先确认是 0
-- DROP TABLE `file_info`;


-- ============================================================================
-- 附：已有的 user 表结构（仅供对照，不要重复执行）
-- ============================================================================
-- CREATE TABLE `user` (
--   `id`            bigint(20)   NOT NULL AUTO_INCREMENT,
--   `username`      varchar(50)  NOT NULL COMMENT '用户名',
--   `password`      varchar(100) NOT NULL COMMENT '密码(BCrypt加密)',
--   `email`         varchar(100) DEFAULT NULL,
--   `storage_limit` bigint(20)   NOT NULL DEFAULT '1073741824' COMMENT '网盘容量上限,默认1GB',
--   `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
--   `update_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
--   PRIMARY KEY (`id`),
--   UNIQUE KEY `username` (`username`)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
