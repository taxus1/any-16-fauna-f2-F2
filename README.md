# some-pro · 纯净脚手架

基于 **Spring Boot 3.4 WebFlux（响应式）+ Spring Security + MyBatis-Plus + PageHelper + 响应式 Redis** 的纯净后端脚手架。

> 持久层说明：Web 层是全响应式（WebFlux + 响应式 Redis），但持久层是 **MyBatis-Plus（阻塞 JDBC）**，
> 通过 `subscribeOn(Schedulers.boundedElastic())` 桥接进响应式链路 —— 即「响应式外壳 + 阻塞内核」。
> 分页统一用 **PageHelper**。详见下文「响应式 × 阻塞 JDBC 桥接约定」。

## 运行环境（重要 · 先读这段）

本工程运行在**容器**里，但**数据库与缓存不在本容器内**，由**宿主机 Docker** 提供，已经起好、无需你再装：

| 依赖 | 宿主机容器 | 宿主地址 | 容器内地址 | 账号 |
|---|---|---|---|---|
| MySQL 8 | `some-pro-mysql` | `127.0.0.1:3306` | `host.docker.internal:3306` | root / root（库名见 `application-dev.yml`） |
| Redis 7 | `some-pro-redis` | `127.0.0.1:6379` | `host.docker.internal:6379` | 无密码 |

- 应用的 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `REDIS_HOST` / `REDIS_PORT` 已由运行环境**作为环境变量注入**，`application-*.yml` 里的 `${...}` 会解析到正确地址。**直接用即可。**
- **不要**在本容器里 `apt-get install` MySQL / MariaDB / Redis，**也不要**去下载免安装的数据库包；表已经建好（见 `doc/schema/`）。
- 容器在 Docker bridge 网络里，`localhost` 指向容器自己，所以连宿主一律用 `host.docker.internal`。
- 要跑测试或起服务：`mvn test` / `mvn spring-boot:run` 直接跑，连的是宿主那套库；需要数据库客户端时，命令形如 `mysql --protocol=TCP -h host.docker.internal -uroot -proot <库名>`。

## 已内置约定

| 能力 | 约定 |
|---|---|
| 响应式 Web | `spring-boot-starter-webflux`，Controller 方法返回 `Mono<Result<T>>` / `Flux<...>` |
| 统一返回 | `com.somepro.common.Result<T>`，`code=0` 成功，非 0 失败 |
| 全局异常 | `GlobalExceptionHandler`（`@RestControllerAdvice`，WebFlux 版）统一收口，业务异常抛 `BizException`；校验失败为 `WebExchangeBindException` |
| 持久层 | **MyBatis-Plus**（`BaseMapper` + `LambdaQueryWrapper`）；Mapper 放 `infrastructure/persistence`，由 `MybatisPlusConfig` 上的 `@MapperScan` 扫描 |
| 对象分层 | **PO / 领域对象 / VO 三层分离**：PO 带表映射注解、只在基础设施层；领域对象纯业务、**不含任何框架注解**；VO 只含对外字段、只在接口层。转换只发生在仓储适配器（PO↔领域）与接口层（领域→VO）。详见「PO / 领域 / VO 三层约定」 |
| record 用法 | VO 与领域值对象（如 `PageResult`）用 **record**（纯数据、无行为、不可变）；**PO 与领域聚合根不能用 record** —— PO 要被 MyBatis-Plus 反射实例化 + setter 填充，聚合根有可变状态与继承关系 |
| 软删除 | `BaseEntity.delFlag` 上加 `@TableLogic`（`0` 正常 / `1` 删除）：查询自动追加 `del_flag=0`，`deleteById()` 自动改写为 `UPDATE ... SET del_flag=1`。**不要再手写 del_flag 条件** |
| 操作人审计 | MyBatis-Plus `MetaObjectHandler`（`AutoFillMetaObjectHandler`）填 `createBy/updateBy/createTime/updateTime`；操作人来源是 **Reactor Context** —— `OperatorWebFilter` 写入，仓储适配器切线程前取出放进 `AuditContextHolder`（ThreadLocal）。**不是前端参数，业务代码不要手动 set** |
| 分页 | 统一 **PageHelper**：`PageHelper.startPage(pageNum, pageSize)` + `mapper.selectList(wrapper)`，`pageNum/pageSize` 透传，不要写死。**不要用 MyBatis-Plus 的 `IPage`**（`PaginationInnerInterceptor` 未注册，与 PageHelper 共存会互相干扰） |
| Redis | 容器里取同一个 `ReactiveRedisTemplate<String,Object>`，key 用 String，value 用 JSON |
| Security / CORS | `@EnableWebFluxSecurity`，全路由需认证（formLogin 拿 SESSION cookie）；CORS 允许跨域 |
| 多环境 | `dev / test / prod` 三套 yml；凭据用环境变量注入（`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`REDIS_HOST`...）。⚠️ `DB_URL` 需为**完整 JDBC URL**（`jdbc:mysql://host:3306/db?...`） |
| 定时任务 | `@EnableScheduling`，持久化以便重启恢复 |

## 一键起环境（已容器化，可重跑）

```bash
cd some-pro
docker compose up -d        # 起 mysql:8.2.0 + redis:7.2（宿主机执行；容器内一般已经起好，无需再起）
# 建表：demo 示例模块的表 + 本题库业务表（建表脚本在 doc/schema/ 下）
mysql -h127.0.0.1 -uroot -proot some_pro < doc/schema/demo.sql
# 业务表见 doc/schema/ 中本题库的建表脚本（如 transcode.sql）
mvn clean package -DskipTests
java -jar target/some-pro-1.0.0.jar
# 校验：curl http://localhost:8080/api/demo/ping  -> {"code":0,"msg":"success","data":"pong"}
# 注意：全路由需认证，未登录访问会被重定向到登录页；测试可用 admin/admin123 登录后带 SESSION cookie。
```

## 响应式 × 阻塞 JDBC 桥接约定

Web 层仍是全响应式（WebFlux + 响应式 Redis），但 MyBatis-Plus 是**阻塞 JDBC**。二者混用有一条硬红线：

**绝不能在 Netty event-loop 线程上执行 JDBC。** 一旦在 event-loop 上阻塞，整个服务的并发会直接塌掉，
而且这种错误不会报错、只会表现为「吞吐莫名很低」，极难排查。

### 唯一正确写法

所有 DB 调用都必须经由仓储适配器里的 `blocking(...)` 桥接器（`DemoItemRepositoryImpl#blocking`）：

```java
private <T> Mono<T> blocking(Supplier<T> supplier) {
    return Mono.deferContextual(ctx -> {
        String operator = ReactiveOperatorContext.getOperator(ctx);   // ① 切线程前先取操作人
        return Mono.fromCallable(() -> {
            AuditContextHolder.setOperator(operator);                 // ② 放进 ThreadLocal 供审计填充
            try {
                return supplier.get();                                // ③ 阻塞 JDBC
            } finally {
                AuditContextHolder.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());                  // ④ 切到阻塞线程池
    });
}
```

顺序不能颠倒：**先 `deferContextual` 取 Reactor Context，再 `subscribeOn`**。
反过来写，Callable 跑在 boundedElastic 线程上就读不到上游的 Reactor Context 了，审计会静默退化成 `system`。

### 四条硬规则

1. **Mapper 只能在 `blocking(...)` 里调用**，不要在 Controller / AppService / 领域层直接注入 Mapper。
2. **`subscribeOn(Schedulers.boundedElastic())`** —— 不要用 `Schedulers.parallel()`（那是为 CPU 密集设计的），也不要用 `block()`/`blockFirst()` 把响应式代码倒退回同步。
3. **`PageHelper.startPage()` 后必须 `finally { PageHelper.clearPage(); }`** —— 分页参数靠 ThreadLocal 传递，不清理会污染线程池里的下一次调用（表现为「别人莫名其妙被分页了」）。
4. **连接池大小要和 boundedElastic 实际并发匹配**（`spring.datasource.hikari.maximum-pool-size`）。池太小 → 线程排队等连接；池太大 → 占满 MySQL 连接数。dev 默认 `20`。

## PO / 领域对象 / VO 三层约定

同一个业务概念在三层各有一个类，**不要合并成一个类走完全程**：

| 类 | 所在层 | 职责 | 能不能用 record |
|---|---|---|---|
| `DemoItemPO` | `infrastructure/persistence/demo/po` | 「表」的形状：`@TableName` / `@TableId` / `@TableField`，字段与列一一对应，不放业务规则 | ❌ 不能。MyBatis-Plus 要反射实例化并调 setter 填充（审计字段靠 MetaObjectHandler 写入） |
| `DemoItem`（领域） | `domain/demo/model` | 「业务」的形状：聚合根 + 不变量 + 领域行为（`create()` / `rename()`） | ❌ 不能。有可变状态（`rename` 改字段），且要继承 `BaseEntity`（record 是 final、不能继承类） |
| `DemoItemVO` | `interfaces/rest/demo/vo` | 「对外」的形状：只含允许暴露的字段 | ✅ 推荐。纯数据、无行为、创建后不变 |
| `PageResult`（领域值对象） | `domain/shared/model` | 领域分页结果，避免领域层依赖 Spring Data 的 `Page` | ✅ 推荐。不可变值对象 |
| `PageVO` | `interfaces/rest/demo/vo` | 对外分页结构，比 `PageResult` 多一个 `totalPages` | ✅ 推荐 |

### 转换规则

- **PO ↔ 领域**：只在仓储适配器里做（`DemoItemPoConverter`）。领域层与接口层**不应看到任何 PO**。
- **领域 → VO**：只在接口层做（`DemoItemVoConverter`）。
  **Controller 不许直接把领域对象塞进 `Result` 返回** —— 否则 `delFlag` / `createBy` / `updateBy` / `updateTime`
  会被无意识序列化出去，且改库表会连带改 API 契约。
- 应用层出入参都是领域对象，既不认识 PO 也不认识 VO。

### 一个分层取舍的例子（为什么要 `PageVO`）

`PageResult` 是 record，**Jackson 只序列化 record 组件**。想让 `totalPages` 出现在 JSON 里，
就得给它加 `@JsonProperty` —— 但那会把 Jackson 引进领域层，破坏「领域不依赖框架」。

所以派生字段放在接口层：`PageVO` 多带一个 `totalPages`，`PageResult` 保持零框架依赖。
序列化相关的取舍留在接口层，这正是分层的意义。

## 目录（DDD 四层）

```
src/main/java/com/somepro
  interfaces/rest/<ctx>      用户接口层：Controller（协议适配 + VO 转换，Mono<Result<T>>）
                             vo/（对外 VO，不可变 record）
                             converter/（领域对象 → VO）
  application/<ctx>          应用层：AppService（用例编排）+ port/（应用端口）
  domain/<ctx>/model         领域层：聚合根/实体/值对象（纯领域，无框架注解）
  domain/<ctx>/repository    领域层：仓储端口（接口）
  domain/shared/model        领域层：共享基类 BaseEntity + 分页值对象 PageResult
  infrastructure/config      基础设施层：SecurityConfig(CORS)/RedisConfig/OperatorWebFilter
                             MybatisPlusConfig(@MapperScan + PageInterceptor)
  infrastructure/persistence 基础设施层：仓储适配器（MyBatis-Plus 实现领域端口）
                             base/BasePO（PO 基类，带 @TableLogic 等注解）
                             <ctx>/po/（PO，表映射）
                             <ctx>/converter/（PO ↔ 领域对象）
                             audit/（AuditContextHolder + AutoFillMetaObjectHandler）
  infrastructure/cache       基础设施层：缓存适配器（响应式 Redis）
  common/                    横切：Result, BizException, GlobalExceptionHandler
doc/schema/ 建表 SQL（create 阶段建好，模型不碰）
docker-compose.yml  一键起 mysql+redis
```

依赖方向：`interfaces → application → domain`；`infrastructure` 实现 `domain`/`application` 的端口；domain 不依赖 infrastructure。

## 本机运行说明（仅宿主开发机；容器内请忽略）

在本机（macOS）实际跑这套脚手架时，注意以下环境事实：

- **Maven 3.6.3** 真实路径 `/usr/local/maven`（另有 `~/.m2/bin/mvn` 封装脚本）。
  - ⚠️ 本仓库配套的 Bash / 沙箱工具默认是**非登录、非交互 shell，不加载 `~/.zshrc`**，因此直接 `which mvn` 会报 not found。调用方式二选一：
    - 走登录 shell：`zsh -lic 'mvn ...'`
    - 绝对路径：`/usr/local/maven/bin/mvn`
- **构建需 JDK 17**：`pom.xml` 的 `java.version=17`，但本机默认 `java` 是 OpenJDK 26，不匹配。构建前设 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`（本机已装 17.0.12）。
- **Docker 可用**：Docker 28.1.1 + Compose v2.35.1（桌面版，`/usr/local/bin/docker`）。上方「一键起环境」的 `docker compose up -d` 能起 `mysql:8.2.0` + `redis:7.2`，MySQL / Redis 不再需要单独安装。注意 daemon **不会随 shell 自动拉起**，报 `Cannot connect to the Docker daemon` 时先启动 Docker Desktop。
- Maven 3.6.3 刚好是 Spring Boot 3.4 的最低线，实测 `mvn clean package -DskipTests` 可正常完成。
- ⚠️ **编译插件已锁 3.14.0**：父工程继承的 `maven-compiler-plugin` 3.13.0 在 JDK 17 上以进程内方式编译时，会反射 javac 内部字段失败，报
  `Fatal error compiling: Cannot load from object array because "this.hashes" is null`。`pom.xml` 已覆盖为 3.14.0 修复之，**不要删掉这段覆盖**。
  若因其它原因仍撞上该错误，可用 fork 编译绕开：
  `-Dmaven.compiler.fork=true -Dmaven.compiler.executable=$JAVA_HOME/bin/javac`

## 持久层改造的验证状态

已在**真实 MySQL（Docker 起的 9.6.0）+ HTTP 端到端**验证：

| 验证项 | 结果 |
|---|---|
| `mvn clean compile` / `mvn clean package -DskipTests` | ✅ 通过（JDK 17 + Maven 3.6.3） |
| Spring 上下文启动（MyBatis-Plus 与 PageHelper 自动配置不冲突） | ✅ 通过 |
| 登录 → 写 7 条 → 落库 | ✅ 7 次请求 = 7 行（无重复写入） |
| 审计链路：`OperatorWebFilter` → Reactor Context → `AuditContextHolder` → `MetaObjectHandler` | ✅ `create_by` / `update_by` 全部为登录人 `admin`，非 `system` |
| PageHelper 分页：第 1 页 5/7、第 2 页 2/7、越界页 0/7、带 `like` 条件 1/1 | ✅ 通过 |
| `@TableLogic` 逻辑删除：`selectById` 查不到、总数降 1、`del_flag=1`、物理行不减 | ✅ 通过 |
| 三层分离后 VO 字段白名单：接口只返回 `id / name / score / createTime` | ✅ 通过，`delFlag`/`createBy`/`updateBy`/`updateTime` 不再外泄 |
| 领域层依赖方向：`domain` 下无 `baomidou` / `springframework` 依赖 | ✅ 通过，只剩 Reactor 与业务异常 |

## 业务模块：野生动物监测底账（station / site / species）

三块底账按脚手架既有分层落地（domain / application / infrastructure / interfaces 四层，
PO↔领域↔VO 三层分离，DB 调用统一走仓储适配器的 `blocking(...)` 桥接）：

| 模块 | 路由 | 说明 |
|---|---|---|
| 监测站 | `/api/stations` | `POST` 新立（编号 `ST-YYYY-NNNN` 自动生成，也可显式指定，撞号返回业务失败不甩底层错）、`GET /{id}` 详情（带名下在册/停测点位数）、`PUT /{id}` 改资料、`POST /{id}/suspend` 停用、`POST /{id}/close` 关闭（名下有在册点位时拒绝）、`GET` 条件分页（name/level/region/status 全空翻整份名册） |
| 监测点 | `/api/sites` | `POST` 登记（编号 `MP-YYYY-NNNN`，目标站必须存在且未停用未关闭）、`GET /{id}`、`PUT /{id}`、`POST /{id}/deactivate` 停测、`POST /{id}/activate` 恢复在册、`DELETE /{id}` 撤点（逻辑删除）、`GET` 条件分页（stationId/siteType/habitat/status） |
| 物种名录 | `/api/species` | `POST` 录入（编码 `SP-NNNN`，保护级别默认 COMMON、状态默认 ENABLED）、`GET /{id}`、`PUT /{id}`、`POST /{id}/disable` 停用（不删除）、`GET` 条件分页（name/protectionLevel/status） |
| 巡护任务 | `/api/tasks` | `POST` 派发（编号 `PT-YYYY-NNNN` 自动生成，也可显式指定，撞号返回业务失败不甩底层错；默认待执行）、`GET /{id}` 详情、`PUT /{id}` 改任务、`POST /{id}/start` 开工（待执行→执行中，记开工时刻）、`POST /{id}/complete` 回报完成（执行中→已完成，记完成时刻并把观测账 obsCount/abnormalCount 数清写回）、`POST /{id}/cancel` 取消（置已取消并逻辑销账：名单翻不到、账留在表里）、`GET` 条件分页（stationId/siteId/patrolType/status/plannedDate 全空翻整份任务，每行带任务编号） |

约定：
- 编号生成「取号→落库」一体化重试（`BizNoGenerator`）：并发撞号重新取号，唯一索引兜底，
  一个号只落一份；显式指定的编号撞号时返回业务失败（code≠0），不抛底层 DuplicateKeyException。
- 状态机只按明确动作单向流转，重复停用/关闭/停测都是幂等空操作；CLOSED 是终态，不再改、不再停用。
- 任务执行生命周期：开工只有待执行的开得了（PENDING→IN_PROGRESS，记 started_at），回报完成只有
  执行中的报得了（IN_PROGRESS→DONE，记 finished_at）；手快点两下是幂等空操作——第二下没什么可动的，
  不重复计数、时刻不翻动，并发双击由仓储层守卫式流转（UPDATE ... WHERE status=期望值）兜底，只流转一次。
- 收尾归账：回报完成时按任务底下的观测记录（t_wildlife_obs，与观测录入同一张账）数清总条数与异常条数
  （受伤/死亡/疑似疫病）写回 obs_count/abnormal_count；任务落 DONE 后账即封存，已结束的任务不再接新观测，
  想补录得另开任务（观测录入侧只往执行中的任务下录，两边共同守住）。
- 站详情的在册/停测点位数与点位模块读同一张表、同一套 del_flag 过滤，两边数字一致。
- 派任务看两头：站得在运行（ACTIVE）、点得在册（ACTIVE），站停用/关闭、点停测都派不进去；
  同一个点同一天只挂一条还没走完（待执行/执行中）的任务，前面那条完了或撤了才派得下一条，
  已取消的不占位；计划日期不早于今天。
- 已在真实 MySQL 上端到端验证：69 项空库全流程用例 + 13 项存量数据（any_16_fauna 种子库）用例全部通过，
  含 10 路并发建站、8 路并发建点的编号唯一性验证。

