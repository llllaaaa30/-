# \# 邻里优选

# 基于SpringBoot + Redis + RocketMQ的本地生活团购后端项目

# 

# \## 技术栈

# \- SpringBoot 2.x

# \- MyBatis-Plus

# \- Redis（String、Hash、Lua脚本、Stream）

# \- RocketMQ消息队列

# \- MySQL

# 

# \## 功能模块

# 1\. 用户模块：短信登录、用户信息维护

# 2\. 社交模块：笔记发布、点赞、用户关注、粉丝滚动分页

# 3\. 秒杀模块：优惠券限时秒杀

# &#x20;   - Lua脚本原子校验库存、一人一单，防止超卖

# &#x20;   - Redis预扣库存提升并发

# &#x20;   - RocketMQ异步创建订单，削峰解耦

# 4\. 其他：实现布隆过滤器防止缓存穿透

# 

# \## 项目亮点

# 1\. 使用Lua脚本，保证Redis多条操作原子性，避免并发超卖

# 2\. RocketMQ异步下单，秒杀请求快速返回，提升吞吐

# 3\. SetNX分布式锁解决集群下超卖问题

# 4\. 基于Redis实现滚动分页，优化大分页查询性能

# 5\. 缓存穿透、缓存击穿防护

# 

# \## 环境准备

# \- JDK 8

# \- MySQL 8.0

# \- Redis

# \- RocketMQ

# 

# \## 启动说明

# 1\. 创建数据库，执行sql脚本

# 2\. 修改application.yml的数据库、Redis、RocketMQ地址

# 3\. 启动SpringBoot项目

