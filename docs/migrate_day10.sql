-- ============================================================================
-- Day 10（P2 目录结构）· 现库迁移脚本
-- 目标库：net_disk（执行前必须先 mysqldump 备份！）
-- 一次性完成三件事：
--   1) real_file_id 改为可空（文件夹行没有物理文件）
--   2) 新增 is_folder 列
--   3) 索引升级：idx_user_parent -> idx_user_parent_time
--      - 新索引把排序字段都纳入：is_folder + create_time，真正消掉文件列表的 filesort
--      - 旧索引是新索引的严格前缀，留着只会增加写放大，同一笔 ALTER 里删掉
-- 执行：mysql -uroot -p net_disk < docs/migrate_day10.sql
-- 说明：MySQL 5.7 支持一条 ALTER 里做多个变更，整条语句是一个原子操作。
-- ============================================================================

ALTER TABLE `user_file`
  MODIFY COLUMN `real_file_id` bigint(20) DEFAULT NULL COMMENT '指向 real_file.id；文件夹行为 NULL',
  -- parent_id 的类型不变，只更新注释：旧注释写着"Day4 不用，为 Day5 目录结构预留"，
  -- 目录结构已上线，注释必须与 docs/schema.sql 保持一致，否则 SHOW CREATE TABLE 逐字对不上
  MODIFY COLUMN `parent_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '父目录id，0=根目录',
  ADD COLUMN `is_folder` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0文件 1文件夹' AFTER `is_delete`,
  ADD KEY `idx_user_parent_time` (`user_id`, `parent_id`, `is_delete`, `is_folder`, `create_time`),
  DROP KEY `idx_user_parent`;
