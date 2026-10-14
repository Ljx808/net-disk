# 云盘系统项目说明

## 项目定位
个人学习项目，用于校招简历，技术栈必须严格遵守以下约定。

## 技术栈
- 后端：Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis + JWT
- 前端：Vue3 组合式 API + Vite + Element Plus + Pinia + Axios
- 存储：本地磁盘存储，但需抽象 StorageService 接口便于将来切换 OSS

## 开发约定
- 统一返回结构 Result<T>（code/message/data）
- 全局异常处理 @RestControllerAdvice
- 前端 API 统一封装在 src/api 目录
- 数据库已有三张表：user、real_file、user_file，结构见 docs/schema.sql
- 每完成一个功能模块，为我总结：做了什么、关键设计、可能的面试考点

## 工作方式
- 一次只做一个小功能，完成后停下来等我确认
- 生成代码后，用注释解释关键逻辑，并对新手解释用到的核心概念
- 不使用我技术栈之外的重量级中间件（不用Kafka/ES/Nacos）``