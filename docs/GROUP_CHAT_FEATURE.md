# 啾信 · 新建群聊功能交付文档

## 一、功能概述

在现有「啾信」单聊架构基础上，新增**新建群聊**能力：用户可创建由多位舰娘人格组成的群聊会话，群内多舰娘基于各自人格独立回复用户消息，支持语音与表情包，群聊信息完整持久化，与会话列表、聊天页无缝集成。

本实现**完全复用并扩展现有啾信架构**（未引入任何新框架 / 第三方库 / 独立 ViewModel），保持与单聊一致的代码风格、配置隔离机制与竞态防护策略。

## 二、架构设计

### 2.1 数据模型（`data/model/JiuxinModels.kt`）

| 新增/扩展 | 说明 |
| --- | --- |
| `SessionType` 枚举 | `PRIVATE`（私聊）/ `GROUP`（群聊） |
| `GroupMember` 数据类 | 群成员完整配置快照：人格提示词、语音舰娘、语音概率/关键词、表情包开关/概率 |
| `ChatSession.sessionType` | 会话类型标识，默认 `PRIVATE`（向后兼容旧数据） |
| `ChatSession.groupMembers` | 群成员快照列表，仅群聊有效 |
| `ChatSession.isGroup` | 计算属性，便捷判断群聊 |

> **配置快照隔离**：与单聊一致，群成员在创建群聊时固化配置快照。后续即便人格配置被修改/删除，群聊历史消息与各成员行为仍稳定，不受外部影响。

### 2.2 ViewModel（`viewmodel/JiuxinViewModel.kt`）

**群聊创建**
- `createGroupSession(groupName, memberPersonaIds)`：校验成员 ≥2 → 解析成员快照 → 复用 `persistAndActivateSession` 持久化并激活。
- 群名留空时自动生成 `群聊-MMDD-HHmm`。

**群成员管理**
- `updateGroupMembers(sessionId, memberPersonaIds)`：增删成员（≥2 约束），同步持久化。
- `renameGroupSession(sessionId, newName)`：群改名（同步 `name` 与 `jiuxinName`）。

**群聊消息流（核心）**
- `sendMessage` 检测 `session.isGroup` → 进入 `handleGroupMessage` 分支。
- 回复成员选择：≤2 人全部回复；>2 人随机选 2 位（平衡群活跃度与 API 成本，避免刷屏）。
- `callGroupApi(userMessage, member, session)`：以**成员视角**构造 system prompt（人格 + 群聊场景 + 成员名单 + 发言约束），历史消息按 `shipName` 区分"自己（assistant)/他人（user 上下文）"，使模型理解多角色对话。
- 每位成员回复后独立触发**自身配置的**语音（`triggerGroupMemberVoice`）与表情包。
- **竞态防护**：与单聊相同的代际计数器（`_chatGeneration`）+ 会话 ID 双重校验，成员依次回复中若会话被清空/删除/切换，立即丢弃后续回复；仅当全部成员回复失败（`successCount==0`）才向用户报错。

**既有逻辑修复（群聊适配）**
- `computeShipKey`：群聊返回 `group:{id}`，群聊作为独立实体参与去重，不与私聊合并。
- `deleteSession`：删除当前群聊后切换/清空，**不再回退创建私聊兜底会话**。
- `createNewSessionForCurrentShip`：群聊历史面板点"+"时复制完整群配置（含成员）创建新群聊。
- `renameSession`：群聊重命名同步 `name` + `jiuxinName`（会话列表以 `jiuxinName` 显示）。

### 2.3 会话列表（`ui/screens/ConversationListScreen.kt`）

- 加号菜单新增「新建群聊」入口 → `JuusNewGroupSheet`：
  - 群聊名称输入（选填，24 字上限）。
  - 舰娘人格**多选**（复选），实时显示已选摘要。
  - 创建按钮在选中 ≥2 位成员时可用；API 未配置时弹出配置缺失提示。
- `JuusConversationItem` 群聊适配：
  - 头像区显示成员 **2x2 宫格组合头像**（最多 4 位），无成员回退群图标。
  - 预览行显示「N 位成员 · 时间」。

### 2.4 聊天页（`ui/screens/JiuxinChatScreen.kt`）

- **消息分组**：`senderOf` 群聊按 `ai_{shipName}` 标识，不同舰娘的连续消息各自独立分组、各自显示头像（私聊行为不变）。
- **MessageBubble**：新增 `isGroup`；AI / 语音 / 表情包消息在组首上方显示**发送者名称**，群聊使用消息自带头像（各成员头像）。
- **顶栏**：群聊显示群名 + 副标题「N 位成员」；设置按钮在群聊中改为打开 `GroupMemberPanel`。
- **GroupMemberPanel（群信息面板）**：群名编辑保存、成员增删（≥2 约束、点击即生效）、展示成员头像与人格。

## 三、数据流向

```
新建群聊：ConversationListScreen(多选成员)
  → JiuxinViewModel.createGroupSession
  → ChatSession(GROUP, groupMembers=快照)
  → PlayerSettingsDataStore.setAiChatSessions（持久化）
  → 激活会话 → 进入 JiuxinChatScreen

群聊收发：用户输入
  → sendMessage → addMessage(USER)
  → handleGroupMessage → 逐成员 callGroupApi（OpenAI 兼容接口）
  → addMessage(AI, shipName/avatarUrl=成员)
  → saveCurrentSessionMessages → DataStore 持久化
  → （可选）triggerGroupMemberVoice / addStickerMessage
```

## 四、异常与边界处理

| 场景 | 处理 |
| --- | --- |
| 成员 < 2 | 创建/更新被拒绝，按钮禁用或返回空并 Toast 提示 |
| API 未配置 | 创建前校验，弹出配置缺失引导对话框 |
| 部分成员回复失败 | 仅记录日志，不打扰用户；全部失败才展示错误 |
| 回复中切换/删除/清空会话 | 代际+会话 ID 校验，丢弃过期回复与表情包 |
| 人格配置被删除 | 群聊使用创建时快照，历史显示与行为不受影响 |
| 群聊删除当前会话 | 切换到剩余最新会话或清空，不创建私聊兜底 |
| 群名重复/为空 | 允许重名（按会话 ID 区分），空名自动生成 |

## 五、验证结果

- `./gradlew :app:compileDebugKotlin` —— 通过
- `./gradlew :app:assembleDebug` —— 通过，APK 生成成功（约 46 MB）
- 改动文件（4 个，均为既有文件扩展）：
  - `data/model/JiuxinModels.kt`
  - `viewmodel/JiuxinViewModel.kt`
  - `ui/screens/ConversationListScreen.kt`
  - `ui/screens/JiuxinChatScreen.kt`
- 无新增依赖、无新增第三方库、无独立新文件，代码风格与原项目一致。
